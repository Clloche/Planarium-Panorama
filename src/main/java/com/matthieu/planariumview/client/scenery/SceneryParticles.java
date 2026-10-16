package com.matthieu.planariumview.client.scenery;

import com.matthieu.planariumview.compat.ColorCompat;

import com.matthieu.planariumview.mixin.ParticleAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Particules du decor geant et des miniatures : fumee des torches et des feux de camp, flammes, lave, gouttes,
 * explosions... Une particule du decor est 32 fois plus grande, va 32 fois plus vite et tombe 32 fois plus vite, comme
 * le decor ; une particule de miniature est 32 fois plus petite. Elle ne touche pas les vrais blocs, s'arrete sur ceux
 * de son faux monde, et prend la lumiere de l'endroit ou elle nait.
 */
public final class SceneryParticles {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private static final RandomSource RANDOM = RandomSource.create();
    /** Particules du decor ou d'une miniature, avec leur lumiere (format de la lightmap) et leur espace. */
    private static final Map<Particle, Integer> LIGHT = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Particle, Space> SPACE = Collections.synchronizedMap(new WeakHashMap<>());

    private SceneryParticles() {
    }

    /**
     * Ou un faux monde est dessine : un point du gabarit (x, y, z) apparait en (x * scale + ox, y * scale + oy,
     * z * scale + oz) dans le vrai monde.
     */
    public record Space(LitRenderingWorld world, double scale, double ox, double oy, double oz,
                        net.minecraft.world.phys.AABB bounds, boolean liveLight, java.util.function.IntUnaryOperator adjust) {
    }

    /**
     * Couleur de Contraption Lights dans un faux monde (seulement les bits de couleur, sans les niveaux de lumiere).
     * Contraption Lights ne sait lire qu'un monde a la fois, et changer de monde vide tout son cache : le faire pendant
     * le jeu faisait clignoter la lumiere. On la lit donc seulement pendant la construction du decor ou de la miniature
     * (quand Contraption Lights lit deja le faux monde), aux endroits ou naissent les particules : les blocs lumineux et
     * celui au-dessus, et le sol de chaque colonne (pour la pluie et la neige).
     */
    private static final class Hues {
        final Map<Long, Integer> cells = new java.util.HashMap<>();
        final Map<Long, Integer> columns = new java.util.HashMap<>();
    }

    private static final Map<LitRenderingWorld, Hues> HUES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final int COLOR_BITS = ~0x00F000F0;
    private static final int MAX_HUE_CELLS = 20000;

    /**
     * Appele pendant que Contraption Lights lit ce faux monde (ColorCompat.begin). {@code columns} : aussi le sol des
     * colonnes de {@code center - view} a {@code center + view} (decor geant).
     */
    public static void readHues(LitRenderingWorld world, Iterable<com.hollingsworth.arsnouveau.client.renderer.world.CulledStatePos> states,
                         boolean columns, int center, int view) {
        if (world == null) {
            return;
        }
        if (!ColorCompat.building()) {
            HUES.remove(world);
            return;
        }
        Hues hues = new Hues();
        net.minecraft.core.BlockPos.MutableBlockPos at = new net.minecraft.core.BlockPos.MutableBlockPos();
        try {
            for (com.hollingsworth.arsnouveau.client.renderer.world.CulledStatePos statePos : states) {
                if (hues.cells.size() >= MAX_HUE_CELLS) {
                    break;
                }
                net.minecraft.core.BlockPos pos = statePos.pos;
                if (world.getBlockState(pos).getLightEmission(world, pos) <= 0) {
                    continue;
                }
                for (int dy = 0; dy <= 1; dy++) {
                    at.set(pos.getX(), pos.getY() + dy, pos.getZ());
                    hues.cells.putIfAbsent(at.asLong(), hueAt(world, at));
                }
            }
            if (columns) {
                for (int x = center - view; x <= center + view; x++) {
                    for (int z = center - view; z <= center + view; z++) {
                        int y = world.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z);
                        int hue = hueAt(world, at.set(x, y, z));
                        if (hue != 0) {
                            hues.columns.put(columnKey(x, z), hue);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // un bloc particulier : on garde ce qui est lu
        }
        HUES.put(world, hues);
    }

    private static int hueAt(LitRenderingWorld world, net.minecraft.core.BlockPos pos) {
        try {
            return net.minecraft.client.renderer.LevelRenderer.getLightColor(world, pos) & COLOR_BITS;
        } catch (Exception e) {
            return 0;
        }
    }

    private static long columnKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    /** Couleur la ou nait une particule (son bloc, ou celui d'en dessous : la flamme au-dessus d'une torche). */
    private static int hueOfSpawn(LitRenderingWorld world, double x, double y, double z) {
        Hues hues = world == null ? null : HUES.get(world);
        if (hues == null || hues.cells.isEmpty()) {
            return 0;
        }
        net.minecraft.core.BlockPos pos = net.minecraft.core.BlockPos.containing(x, y, z);
        Integer hue = hues.cells.get(pos.asLong());
        if (hue == null) {
            hue = hues.cells.get(pos.below().asLong());
        }
        return hue != null ? hue : 0;
    }

    /** Couleur du sol de cette colonne du decor (pluie et neige), 0 sans couleur. */
    public static int columnHue(LitRenderingWorld world, int x, int z) {
        Hues hues = world == null ? null : HUES.get(world);
        if (hues == null) {
            return 0;
        }
        Integer hue = hues.columns.get(columnKey(x, z));
        return hue != null ? hue : 0;
    }

    /** Lumiere d'un faux monde a cet endroit, sans couleur (comme LevelRenderer.getLightColor, sans Contraption Lights). */
    public static int rawLight(LitRenderingWorld world, net.minecraft.core.BlockPos pos) {
        int block = world.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, pos);
        int emission = world.getBlockState(pos).getLightEmission(world, pos);
        int sky = world.getBrightness(net.minecraft.world.level.LightLayer.SKY, pos);
        return LightTexture.pack(Math.max(block, emission), sky);
    }

    /** Couleur de chaque particule (bits de couleur seulement), voir Hues. */
    private static final Map<Particle, Integer> HUE = Collections.synchronizedMap(new WeakHashMap<>());

    /** Lumiere du faux monde la ou se trouve maintenant la particule (la fumee s'assombrit en s'eloignant du feu). */
    private static Integer currentLight(Particle particle, Space space) {
        net.minecraft.world.phys.AABB box = particle.getBoundingBox();
        double scale = space.scale();
        net.minecraft.core.BlockPos pos = net.minecraft.core.BlockPos.containing(
                ((box.minX + box.maxX) * 0.5 - space.ox()) / scale,
                ((box.minY + box.maxY) * 0.5 - space.oy()) / scale,
                ((box.minZ + box.maxZ) * 0.5 - space.oz()) / scale);
        int light = rawLight(space.world(), pos);
        Integer hue = HUE.get(particle);
        if (hue != null && hue != 0 && (light & 0xF0) != 0) {
            light |= hue;
        }
        return space.adjust() != null ? space.adjust().applyAsInt(light) : light;
    }

    /** Le decor geant (positions du gabarit, Planarium au rayon {@code radius}). */
    static Space giantSpace(LitRenderingWorld world, int radius) {
        double giant = ClientView.GIANT;
        double origin = -(radius + ClientView.ARS_OFFSET) * giant;
        return new Space(world, giant, origin, origin - ClientView.Y_SHIFT, origin, null, true, null);
    }

    /** Lumiere d'une particule du decor, ou null pour une particule normale. */
    public static Integer lightOf(Particle particle) {
        if (LIGHT.isEmpty()) {
            return null;
        }
        Space space = SPACE.get(particle);
        if (space != null && space.liveLight() && space.world() != null) {
            try {
                return currentLight(particle, space);
            } catch (Exception ignored) {
                // position hors du faux monde : la lumiere de depart
            }
        }
        return LIGHT.get(particle);
    }

    /** Reglage "Particules" de Minecraft : rien en Minimal, une sur trois en Reduit. */
    public static boolean allowed() {
        return switch (Minecraft.getInstance().options.particles().get()) {
            case MINIMAL -> false;
            case DECREASED -> RANDOM.nextInt(3) == 0;
            default -> true;
        };
    }

    /**
     * Cree une particule dans un faux monde. {@code x, y, z} : position dans le gabarit, {@code vx, vy, vz} : vitesse
     * d'origine (en blocs du gabarit par tick), {@code light} : lumiere au format lightmap.
     */
    public static void spawn(Space space, ParticleOptions options, double x, double y, double z, double vx, double vy, double vz,
                      int light) {
        Particle particle = spawnAt(space, options, x * space.scale() + space.ox(), y * space.scale() + space.oy(),
                z * space.scale() + space.oz(), vx, vy, vz, light);
        if (particle != null && space.liveLight()) {
            int hue = hueOfSpawn(space.world(), x, y, z);
            if (hue != 0) {
                HUE.put(particle, hue);
            }
        }
    }

    /** Comme spawn, mais a une position deja dans le vrai monde (la vitesse reste celle du gabarit). */
    private static Particle spawnAt(Space space, ParticleOptions options, double x, double y, double z, double vx, double vy,
                                    double vz, int light) {
        Minecraft mc = Minecraft.getInstance();
        Particle particle;
        List<Particle> added = new java.util.ArrayList<>(1);
        List<Particle> outer = creating;
        creating = added;
        try {
            particle = mc.particleEngine.createParticle(options, x, y, z, vx, vy, vz);
        } catch (Exception e) {
            return null;
        } finally {
            creating = outer;
        }
        if (particle == null || !adopt(particle, space, light)) {
            return null;
        }
        // Particules creees en meme temps par celle-ci (un autre mod qui en ajoute une seconde directement) : aussi a
        // l'echelle du faux monde.
        for (Particle other : added) {
            if (other != particle) {
                adopt(other, space, light);
            }
        }
        return particle;
    }

    /** Particules ajoutees au moteur pendant que spawnAt en cree une (voir onEngineAdd), ou null. */
    private static List<Particle> creating;

    /**
     * Chaque particule ajoutee au moteur de Minecraft (voir ParticleEngineTickMixin). Pendant qu'une particule du decor
     * bouge, celles qu'elle ajoute directement au moteur (sans passer par le monde, comme le font certains mods) sont aussi
     * des particules du decor : sinon elles apparaissent a taille normale, geantes dans une miniature.
     */
    public static void onEngineAdd(Particle particle) {
        if (particle == null) {
            return;
        }
        if (creating != null) {
            creating.add(particle);
            return;
        }
        Particle parent = ticking;
        if (parent == null || SPACE.containsKey(particle)) {
            return;
        }
        Space space = SPACE.get(parent);
        if (space == null) {
            return;
        }
        Integer light = LIGHT.get(parent);
        if (adopt(particle, space, light != null ? light : LightTexture.pack(15, 15))) {
            Integer hue = HUE.get(parent);
            if (hue != null) {
                HUE.put(particle, hue);
            }
        }
    }

    /** Met une particule deja creee a l'echelle du faux monde. Faux (et la particule est enlevee) si c'est impossible. */
    private static boolean adopt(Particle particle, Space space, int light) {
        float scale = (float) space.scale();
        float before = particle instanceof com.matthieu.planariumview.mixin.SingleQuadParticleAccessor quad
                ? quad.planariumview$getQuadSize() : -1.0F;
        particle.scale(scale);
        if (!resized(particle, before, scale)) {
            // Particule d'un autre mod qui garde sa taille (ou se dessine a sa facon) : dans une miniature, elle
            // apparaitrait 32 fois trop grande. On l'enleve.
            particle.remove();
            return false;
        }
        if (particle instanceof ParticleAccessor access) {
            // La vitesse (y compris la part au hasard ajoutee par la particule) et la gravite, a l'echelle du faux monde.
            access.planariumview$setXd(access.planariumview$getXd() * scale);
            access.planariumview$setYd(access.planariumview$getYd() * scale);
            access.planariumview$setZd(access.planariumview$getZd() * scale);
            access.planariumview$setGravity(access.planariumview$getGravity() * scale);
            access.planariumview$setHasPhysics(false);
        }
        LIGHT.put(particle, light);
        SPACE.put(particle, space);
        return true;
    }

    /** Classes de particules deja signalees dans le log (voir resized). */
    private static final java.util.Set<Class<?>> UNSCALED = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    /**
     * Vrai si la particule a bien pris la taille du faux monde. Seulement verifie pour les miniatures (plus petites) :
     * une particule restee a sa taille normale y serait geante. Dans le decor geant, une particule trop petite ne gene pas.
     */
    private static boolean resized(Particle particle, float before, float scale) {
        if (scale >= 1.0F) {
            return true;
        }
        // La taille de base (quadSize) et pas getQuadSize : la fumee, par exemple, part d'une taille 0 et grossit.
        boolean ok = particle instanceof com.matthieu.planariumview.mixin.SingleQuadParticleAccessor quad && before > 0.0F
                && quad.planariumview$getQuadSize() <= before * scale * 2.0F + 1.0E-4F;
        if (!ok && UNSCALED.add(particle.getClass())) {
            LOGGER.info("[Planarium Panorama] Particule ignoree dans la miniature (sa taille ne change pas) : {}",
                    particle.getClass().getName());
        }
        return ok;
    }

    /** Particule du decor en train de bouger (voir ParticleEngineTickMixin), ou null. */
    private static Particle ticking;

    public static void beginTick(Particle particle) {
        ticking = SPACE.isEmpty() || !SPACE.containsKey(particle) ? null : particle;
    }

    public static void endTick() {
        ticking = null;
    }

    /**
     * Une particule du decor en cree une autre (goutte qui se detache, puis qui s'ecrase au sol...) : la nouvelle est
     * aussi une particule du decor, a la meme echelle. Vrai si elle est prise en charge ici.
     */
    public static boolean spawnChild(ParticleOptions options, double x, double y, double z, double vx, double vy, double vz) {
        Particle parent = ticking;
        if (parent == null) {
            return false;
        }
        Space space = SPACE.get(parent);
        if (space == null) {
            return false;
        }
        Integer light = LIGHT.get(parent);
        double scale = space.scale();
        // La vitesse du parent est deja a l'echelle du faux monde ; spawnAt la remet a l'echelle.
        Particle child = spawnAt(space, options, x, y, z, vx / scale, vy / scale, vz / scale,
                light != null ? light : LightTexture.pack(15, 15));
        Integer hue = HUE.get(parent);
        if (child != null && hue != null) {
            HUE.put(child, hue);
        }
        return true;
    }

    /**
     * Sol du faux monde : une particule qui descend dans un de ses blocs s'arrete dessus (comme les vraies particules
     * sur les blocs), au lieu de le traverser. {@code dy} : deplacement vertical de ce tick.
     */
    public static void collide(Particle particle, double dy) {
        if (SPACE.isEmpty()) {
            return;
        }
        Space space = SPACE.get(particle);
        if (space == null || space.world() == null) {
            return;
        }
        if (space.bounds() != null) {
            // Miniature : une particule qui sort du bocal (fumee qui monte au plafond) disparait.
            net.minecraft.world.phys.AABB box = particle.getBoundingBox();
            double scale = space.scale();
            if (!space.bounds().contains((box.minX + box.maxX) * 0.5 / scale - space.ox() / scale,
                    (box.minY - space.oy()) / scale, (box.minZ + box.maxZ) * 0.5 / scale - space.oz() / scale)) {
                particle.remove();
                return;
            }
        }
        if (dy == 0.0) {
            return;
        }
        if (dy > 0.0) {
            ceiling(particle, space, dy);
            return;
        }
        LitRenderingWorld world = space.world();
        double scale = space.scale();
        net.minecraft.world.phys.AABB box = particle.getBoundingBox();
        double wx = (box.minX + box.maxX) * 0.5;
        double wz = (box.minZ + box.maxZ) * 0.5;
        double tx = (wx - space.ox()) / scale;
        double ty = (box.minY - space.oy()) / scale;
        double tz = (wz - space.oz()) / scale;
        net.minecraft.core.BlockPos pos = net.minecraft.core.BlockPos.containing(tx, ty, tz);
        try {
            net.minecraft.world.level.block.state.BlockState state = world.getBlockState(pos);
            if (state.isAir()) {
                return;
            }
            double top = Double.NaN;
            for (net.minecraft.world.phys.AABB part : state.getCollisionShape(world, pos).toAabbs()) {
                if (part.contains(tx - pos.getX(), ty - pos.getY(), tz - pos.getZ())) {
                    double partTop = pos.getY() + part.maxY;
                    top = Double.isNaN(top) ? partTop : Math.max(top, partTop);
                }
            }
            // Deja dans le bloc avant ce tick (nee dedans) : on la laisse.
            if (Double.isNaN(top) || ty - dy / scale < top - 1.0E-4) {
                return;
            }
            particle.setPos(wx, top * scale + space.oy(), wz);
            if (particle instanceof ParticleAccessor access) {
                access.planariumview$setYd(0.0);
                access.planariumview$setOnGround(true);
            }
        } catch (Exception ignored) {
            // Bloc modde qui suppose un vrai monde : la particule continue sans sol.
        }
    }

    /** Plafond du faux monde : une particule qui monte (fumee) s'arrete sous le bloc, comme sous un vrai bloc. */
    private static void ceiling(Particle particle, Space space, double dy) {
        LitRenderingWorld world = space.world();
        double scale = space.scale();
        net.minecraft.world.phys.AABB box = particle.getBoundingBox();
        double wx = (box.minX + box.maxX) * 0.5;
        double wz = (box.minZ + box.maxZ) * 0.5;
        double tx = (wx - space.ox()) / scale;
        double ty = (box.maxY - space.oy()) / scale;
        double tz = (wz - space.oz()) / scale;
        net.minecraft.core.BlockPos pos = net.minecraft.core.BlockPos.containing(tx, ty, tz);
        try {
            net.minecraft.world.level.block.state.BlockState state = world.getBlockState(pos);
            if (state.isAir()) {
                return;
            }
            double bottom = Double.NaN;
            for (net.minecraft.world.phys.AABB part : state.getCollisionShape(world, pos).toAabbs()) {
                if (part.contains(tx - pos.getX(), ty - pos.getY(), tz - pos.getZ())) {
                    double partBottom = pos.getY() + part.minY;
                    bottom = Double.isNaN(bottom) ? partBottom : Math.min(bottom, partBottom);
                }
            }
            // Deja dans le bloc avant ce tick : on la laisse.
            if (Double.isNaN(bottom) || ty - dy / scale > bottom + 1.0E-4) {
                return;
            }
            particle.setPos(wx, bottom * scale + space.oy() - (box.maxY - box.minY), wz);
            if (particle instanceof ParticleAccessor access) {
                access.planariumview$setYd(0.0);
            }
        } catch (Exception ignored) {
            // Bloc modde qui suppose un vrai monde : la particule continue.
        }
    }

    /** Explosions du dehors en cours, comme la particule EXPLOSION_EMITTER de Minecraft. */
    private static final java.util.List<Emitter> EMITTERS = new java.util.ArrayList<>();

    private static final class Emitter {
        final Space space;
        public final double x;
        public final double y;
        public final double z;
        int age;

        public Emitter(Space space, double x, double y, double z) {
            this.space = space;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    /**
     * Explosion du dehors (position dans le gabarit) : comme Minecraft (EXPLOSION_EMITTER), pendant 8 ticks, 6 boules
     * de fumee par tick autour du centre, de plus en plus petites, a l'echelle du decor.
     */
    static void explosion(Space space, double x, double y, double z) {
        if (EMITTERS.size() < 32) {
            EMITTERS.add(new Emitter(space, x, y, z));
        }
    }

    /** Chaque tick client (hors pause). */
    public static void tick() {
        if (EMITTERS.isEmpty()) {
            return;
        }
        int light = LightTexture.pack(15, 15);
        java.util.Iterator<Emitter> it = EMITTERS.iterator();
        while (it.hasNext()) {
            Emitter emitter = it.next();
            double size = emitter.age / 8.0;
            for (int i = 0; i < 6; i++) {
                if (allowed()) {
                    spawn(emitter.space, ParticleTypes.EXPLOSION,
                            emitter.x + (RANDOM.nextDouble() - RANDOM.nextDouble()) * 4.0,
                            emitter.y + (RANDOM.nextDouble() - RANDOM.nextDouble()) * 4.0,
                            emitter.z + (RANDOM.nextDouble() - RANDOM.nextDouble()) * 4.0, size, 0.0, 0.0, light);
                }
            }
            emitter.age++;
            if (emitter.age >= 8) {
                it.remove();
            }
        }
    }

    public static void clear() {
        LIGHT.clear();
        SPACE.clear();
        HUE.clear();
        HUES.clear();
        EMITTERS.clear();
        ticking = null;
        creating = null;
    }
}

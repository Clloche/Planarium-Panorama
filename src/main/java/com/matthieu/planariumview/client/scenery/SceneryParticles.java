package com.matthieu.planariumview.client.scenery;

import com.matthieu.planariumview.compat.contraption_lights.ColorCompat;
import com.matthieu.planariumview.compat.ars_nouveau.ArsParticles;

import com.matthieu.planariumview.mixin.ParticleAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockCollisions;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
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
    /** Particules qui traversent deja les blocs en vanilla (notamment SPORE_BLOSSOM_AIR). */
    private static final Map<Particle, Boolean> NO_COLLISION = Collections.synchronizedMap(new WeakHashMap<>());
    private static boolean collisionWarned;

    private SceneryParticles() {
    }

    private static FramedParticle.Frame attaching;

    static void framed(FramedParticle.Frame frame, Runnable action) {
        FramedParticle.Frame previous = attaching;
        attaching = frame;
        try { action.run(); }
        finally { attaching = previous; }
    }

    static void tickFramed(Particle particle, FramedParticle.Frame frame) {
        framed(frame, () -> {
            beginTick(particle);
            try { particle.tick(); }
            finally { endTick(particle); }
        });
    }

    private static void add(Particle particle) {
        Minecraft.getInstance().particleEngine.add(attaching == null ? particle : new FramedParticle(particle, attaching));
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
     * On la lit pendant la construction du decor ou de la miniature, dans le contexte isole de ColorCompat,
     * pour ne pas recalculer des sections de couleur a chaque naissance de particule. Les positions gardees sont
     * les blocs lumineux et celui au-dessus, et le sol de chaque colonne (pour la pluie et la neige).
     */
    private static final class Hues {
        final Map<Long, Integer> cells = new java.util.HashMap<>();
        final Map<Long, Integer> columns = new java.util.HashMap<>();
    }

    private static final Map<LitRenderingWorld, Hues> HUES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final int COLOR_BITS = ~0x00F000F0;
    private static final int MAX_HUE_CELLS = 20000;
    private static final long HUE_BUDGET_NANOS = 1_000_000L;
    private static HueWork pendingHues;

    /** Only the terrain has thousands of weather columns; its color preparation is spread across frames. */
    private static final class HueWork {
        final LitRenderingWorld world;
        final Object level = Minecraft.getInstance().level;
        final ColorCompat.Scope colors = ColorCompat.capture();
        final List<com.hollingsworth.arsnouveau.client.renderer.world.CulledStatePos> states = new ArrayList<>();
        final Hues hues = new Hues();
        final net.minecraft.core.BlockPos.MutableBlockPos at = new net.minecraft.core.BlockPos.MutableBlockPos();
        final int min, side;
        int stateIndex, columnIndex;

        HueWork(LitRenderingWorld world,
                Iterable<com.hollingsworth.arsnouveau.client.renderer.world.CulledStatePos> states, int center, int view) {
            this.world = world;
            states.forEach(this.states::add);
            min = center - view;
            side = view * 2 + 1;
        }

        boolean step() {
            if (stateIndex < states.size() && hues.cells.size() < MAX_HUE_CELLS) {
                var pos = states.get(stateIndex++).pos;
                if (world.getBlockState(pos).getLightEmission(world, pos) > 0) {
                    for (int dy = 0; dy <= 1; dy++) {
                        at.set(pos.getX(), pos.getY() + dy, pos.getZ());
                        hues.cells.putIfAbsent(at.asLong(), hueAt(world, at));
                    }
                }
                return true;
            }
            stateIndex = states.size();
            if (columnIndex >= side * side) return false;
            int x = min + columnIndex / side;
            int z = min + columnIndex % side;
            int y = world.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z);
            int hue = hueAt(world, at.set(x, y, z));
            if (hue != 0) hues.columns.put(columnKey(x, z), hue);
            columnIndex++;
            return true;
        }
    }

    static void cancelHues() { pendingHues = null; }

    static void prepareHues() {
        HueWork work = pendingHues;
        if (work == null) return;
        if (work.level != Minecraft.getInstance().level || !com.matthieu.planariumview.config.Config.enabled()
                || !com.matthieu.planariumview.config.Config.coloredLight()) {
            pendingHues = null;
            return;
        }
        long start = System.nanoTime();
        int steps = 0;
        ColorCompat.use(work.colors);
        try {
            do {
                if (!work.step()) {
                    HUES.put(work.world, work.hues);
                    pendingHues = null;
                    break;
                }
                // Check every column, and every 64 inexpensive block checks. A color section already started
                // always finishes; the next frame resumes with the same isolated Contraption Lights cache.
            } while ((work.stateIndex < work.states.size() && ++steps % 64 != 0)
                    || System.nanoTime() - start < HUE_BUDGET_NANOS);
        } catch (Exception ignored) {
            HUES.put(work.world, work.hues);
            pendingHues = null;
        } finally {
            ColorCompat.end();
        }
    }

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
            if (pendingHues != null && pendingHues.world == world) pendingHues = null;
            return;
        }
        if (columns) {
            // A newer capture replaces unfinished work. Existing colors stay available until publication.
            pendingHues = new HueWork(world, states, center, view);
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

    /** Vanilla's twenty poofs, simulated in captured coordinates and rendered in the mob's frame. */
    static void mobDeath(LitRenderingWorld world, FramedParticle.Frame frame,
                         double x, double y, double z, float width, float height) {
        Space space = new Space(world, frame.scale(), FramedParticle.OFFSET, FramedParticle.OFFSET,
                FramedParticle.OFFSET, null, true, null);
        int light = com.matthieu.planariumview.config.Config.realLighting()
                ? rawLight(world, net.minecraft.core.BlockPos.containing(x, y, z)) : 0x00F000F0;
        framed(frame, () -> {
            for (int i = 0; i < 20; i++) {
                if (!allowed()) continue;
                spawn(space, ParticleTypes.POOF, x + (RANDOM.nextDouble() * 2 - 1) * width,
                        y + RANDOM.nextDouble() * height, z + (RANDOM.nextDouble() * 2 - 1) * width,
                        RANDOM.nextGaussian() * 0.02, RANDOM.nextGaussian() * 0.02, RANDOM.nextGaussian() * 0.02, light);
            }
        });
    }

    /**
     * Particule loin dans le decor (vent de Windy, a 40-70 blocs du point tire), a sa vraie place, seulement dans le
     * decor dessine (anneau lointain compris) et hors des blocs pleins. Lumiere fixe ({@code light}).
     */
    public static void spawnFar(LitRenderingWorld world, int radius, ParticleOptions options, double x, double y, double z,
                                int light) {
        double center = radius + 0.5;
        double dx = x - center;
        double dz = z - center;
        // A sa vraie place, comme Windy dans le monde : le decor (anneau lointain compris) va maintenant assez loin.
        // Avant, la rafale etait rapprochee du Planarium et pouvait tomber dans une colline ou pres du sol. Au-dela du
        // decor dessine, rien ne la cacherait derriere le relief : on ne la fait pas naitre.
        if (Math.sqrt(dx * dx + dz * dz) > ClientView.edgeRadius() - 2.0) {
            return;
        }
        // Windy ne verifie rien a l'arrivee ; dans le monde, le relief cache les rafales nees dans un bloc. Ici, une
        // rafale nee dans un bloc plein du decor depasserait de ses faces : on l'ecarte.
        net.minecraft.core.BlockPos at = net.minecraft.core.BlockPos.containing(x, y, z);
        if (world.getBlockState(at).isSolidRender(world, at)) {
            return;
        }
        Space giant = giantSpace(world, radius);
        Particle particle = spawnAt(new Space(world, giant.scale(), giant.ox(), giant.oy(), giant.oz(), null, false, null),
                options, x * giant.scale() + giant.ox(), y * giant.scale() + giant.oy(), z * giant.scale() + giant.oz(),
                0.0, 0.0, 0.0, light);
        if (particle != null) {
            // Un peu plus large : vues de loin dans le decor, les rafales paraissaient fines.
            particle.scale(FAR_SIZE);
        }
    }

    /** Agrandissement des particules lointaines (voir spawnFar). */
    private static final float FAR_SIZE = 1.5F;

    /**
     * Liquide du faux monde la ou se trouve une particule du decor, ou null pour une particule normale. Les bulles de
     * Minecraft disparaissent hors de l'eau : elles regardent l'eau du decor, pas le vrai monde (voir BubbleParticleMixin).
     */
    public static net.minecraft.world.level.material.FluidState fluidAt(Particle particle) {
        if (SPACE.isEmpty()) {
            return null;
        }
        Space space = SPACE.get(particle);
        if (space == null || space.world() == null) {
            return null;
        }
        net.minecraft.world.phys.AABB box = particle.getBoundingBox();
        double scale = space.scale();
        return space.world().getFluidState(net.minecraft.core.BlockPos.containing(
                ((box.minX + box.maxX) * 0.5 - space.ox()) / scale, (box.minY - space.oy()) / scale,
                ((box.minZ + box.maxZ) * 0.5 - space.oz()) / scale));
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

    /**
     * Particule qu'un autre mod cree lui-meme, sans type de particule enregistre (feuilles de Falling Leaves) : passee au
     * faux monde comme une particule normale, puis creee a sa place dans le vrai monde par {@code factory}.
     */
    public record Made(java.util.function.Function<net.minecraft.world.phys.Vec3, Particle> factory) implements ParticleOptions {
        @Override
        public net.minecraft.core.particles.ParticleType<?> getType() {
            return ParticleTypes.CRIT;
        }
    }

    /** Comme spawn, mais a une position deja dans le vrai monde (la vitesse reste celle du gabarit). */
    private static Particle spawnAt(Space space, ParticleOptions options, double x, double y, double z, double vx, double vy,
                                    double vz, int light) {
        Minecraft mc = Minecraft.getInstance();
        if (attaching != null && LIGHT.size() >= 2048) return null;
        Particle particle;
        if (options instanceof Made made) {
            try {
                particle = made.factory().apply(new net.minecraft.world.phys.Vec3(x, y, z));
            } catch (Exception e) {
                return null;
            }
            if (particle == null || !adopt(particle, space, light)) {
                return null;
            }
            adding = true;
            try {
                add(particle);
            } finally {
                adding = false;
            }
            return particle;
        }
        boolean mini = space.bounds() != null;
        Particle flame = vanillaFlame(options, x, y, z, vx, vy, vz);
        if (flame != null) {
            if (!adopt(flame, space, light)) {
                return null;
            }
            adding = true;
            try {
                add(flame);
            } finally {
                adding = false;
            }
            return flame;
        }
        List<Particle> added = new java.util.ArrayList<>(1);
        List<Particle> outer = creating;
        boolean outerHold = holding;
        creating = added;
        // Miniature : rien n'entre dans le moteur pendant la creation. Une particule enlevee apres y etre entree restait
        // dessinee jusqu'au tick suivant (les flammes geantes clignotaient) : on n'ajoute que celle qu'on garde.
        holding = mini || attaching != null;
        try {
            particle = mc.particleEngine.createParticle(options, x, y, z, vx, vy, vz);
        } catch (Exception e) {
            return null;
        } finally {
            creating = outer;
            holding = outerHold;
        }
        if (particle != null && particle.getClass().getName().endsWith("FlameToBurnDummyParticle")) {
            // Flamme de Jaden's Nether Expansion venue par un autre chemin (son propre type de particule) : une flamme
            // de Minecraft a la place, et rien de ce qu'elle a cree.
            for (Particle other : added) {
                other.remove();
            }
            particle.remove();
            net.minecraft.resources.ResourceLocation key = net.minecraft.core.registries.BuiltInRegistries.PARTICLE_TYPE
                    .getKey(options.getType());
            String path = key == null ? "" : key.getPath();
            if (!dummyLogged) {
                dummyLogged = true;
                LOGGER.debug("[Planarium Panorama] Flamme de Jaden's Nether Expansion ({}) remplacee par celle de Minecraft", key);
            }
            Particle flame2 = vanillaFlame(path.contains("soul") ? ParticleTypes.SOUL_FIRE_FLAME
                    : path.contains("small") ? ParticleTypes.SMALL_FLAME : ParticleTypes.FLAME, x, y, z, vx, vy, vz);
            if (flame2 == null || !adopt(flame2, space, light)) {
                return null;
            }
            adding = true;
            try {
                add(flame2);
            } finally {
                adding = false;
            }
            return flame2;
        }
        // Particules creees en meme temps par celle-ci (un autre mod qui en ajoute une seconde directement, comme Nether
        // Experience pour la flamme des torches) : dans une miniature, elles apparaitraient a taille normale (geantes).
        if (mini) {
            for (Particle other : added) {
                if (other != particle) {
                    other.remove();
                    noteForeign(other);
                }
            }
        }
        if (particle == null || !adopt(particle, space, light)) {
            return null;
        }
        if ((mini || attaching != null) && added.contains(particle)) {
            // Retenue pendant la creation : on l'ajoute maintenant, a la bonne taille.
            adding = true;
            try {
                add(particle);
            } finally {
                adding = false;
            }
        }
        return particle;
    }

    /**
     * Flammes des torches, bougies et feux (normales, petites et des ames) : toujours celles de Minecraft dans le decor et
     * les miniatures. Un autre mod peut les remplacer par les siennes (Jaden's Nether Expansion : une particule vide qui
     * en cree d'autres plus tard, a sa facon) qui ne se mettent pas a l'echelle du decor : minuscules ou absentes. Null
     * pour les autres particules, ou si la texture de Minecraft manque.
     */
    private static boolean flameWarned;
    private static boolean dummyLogged;
    private static java.lang.reflect.Constructor<net.minecraft.client.particle.FlameParticle> flameConstructor;

    private static Particle vanillaFlame(ParticleOptions options, double x, double y, double z, double vx, double vy, double vz) {
        net.minecraft.core.particles.ParticleType<?> type = options.getType();
        if (type != ParticleTypes.SMALL_FLAME && type != ParticleTypes.FLAME && type != ParticleTypes.SOUL_FIRE_FLAME) {
            return null;
        }
        return vanillaFlame((net.minecraft.core.particles.SimpleParticleType) type, x, y, z, vx, vy, vz);
    }

    private static Particle vanillaFlame(net.minecraft.core.particles.SimpleParticleType simple, double x, double y, double z,
                                         double vx, double vy, double vz) {
        boolean small = simple == ParticleTypes.SMALL_FLAME;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return null;
        }
        net.minecraft.resources.ResourceLocation key = net.minecraft.core.registries.BuiltInRegistries.PARTICLE_TYPE.getKey(simple);
        Object sprites = key == null || !(mc.particleEngine instanceof com.matthieu.planariumview.mixin.ParticleEngineAccessor engine)
                ? null : engine.planariumview$spriteSets().get(key);
        net.minecraft.client.particle.SpriteSet set = sprites instanceof net.minecraft.client.particle.SpriteSet known ? known
                : atlasSprite(simple == ParticleTypes.SOUL_FIRE_FLAME ? "soul_fire_flame" : "flame");
        if (set == null) {
            if (!flameWarned) {
                flameWarned = true;
                LOGGER.info("[Planarium Panorama] Texture de flamme introuvable pour {} : flamme du mod qui la remplace", key);
            }
            return null;
        }
        try {
            // Directement, sans le "Provider" de Minecraft : Jaden's Nether Expansion le detourne vers sa particule vide.
            if (flameConstructor == null) {
                flameConstructor = net.minecraft.client.particle.FlameParticle.class.getDeclaredConstructor(
                        net.minecraft.client.multiplayer.ClientLevel.class, double.class, double.class, double.class,
                        double.class, double.class, double.class);
                flameConstructor.setAccessible(true);
            }
            net.minecraft.client.particle.FlameParticle flame = flameConstructor.newInstance(mc.level, x, y, z, vx, vy, vz);
            flame.pickSprite(set);
            if (small) {
                flame.scale(0.5F);
            }
            return flame;
        } catch (Throwable e) {
            if (!flameWarned) {
                flameWarned = true;
                LOGGER.info("[Planarium Panorama] Flamme de Minecraft impossible a creer", e);
            }
            return null;
        }
    }

    /**
     * Texture de Minecraft prise directement dans l'atlas des particules : un mod qui remplace la flamme sans texture
     * (Jaden's Nether Expansion) retire aussi son jeu de textures du moteur. Null si elle manque.
     */
    private static net.minecraft.client.particle.SpriteSet atlasSprite(String name) {
        try {
            net.minecraft.client.renderer.texture.TextureAtlasSprite sprite = Minecraft.getInstance()
                    .getTextureAtlas(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_PARTICLES)
                    .apply(net.minecraft.resources.ResourceLocation.withDefaultNamespace(name));
            if (sprite == null || sprite.contents().name().getPath().equals("missingno")) {
                return null;
            }
            return new net.minecraft.client.particle.SpriteSet() {
                @Override
                public net.minecraft.client.renderer.texture.TextureAtlasSprite get(int age, int lifetime) {
                    return sprite;
                }

                @Override
                public net.minecraft.client.renderer.texture.TextureAtlasSprite get(net.minecraft.util.RandomSource random) {
                    return sprite;
                }
            };
        } catch (Exception e) {
            return null;
        }
    }

    /** Vrai pendant la creation d'une particule de miniature : ce qui est ajoute au moteur est retenu (voir spawnAt). */
    private static boolean holding;
    /** Vrai pendant que spawnAt ajoute lui-meme sa particule au moteur. */
    private static boolean adding;

    /** Particules ajoutees au moteur pendant que spawnAt en cree une (voir onEngineAdd), ou null. */
    private static List<Particle> creating;

    /**
     * Chaque particule ajoutee au moteur de Minecraft (voir ParticleEngineTickMixin). Pendant qu'une particule de miniature
     * bouge, celles qu'elle ajoute directement au moteur (sans passer par le monde, comme le font certains mods) seraient a
     * taille normale, geantes dans la miniature : on les enleve.
     */
    /** Faux si la particule ne doit pas entrer dans le moteur (voir ParticleEngineTickMixin). */
    public static boolean onEngineAdd(Particle particle) {
        if (particle == null || adding) {
            return true;
        }
        if (creating != null) {
            creating.add(particle);
            return !holding;
        }
        if (miniAnimating) {
            // Un bloc modde de la miniature qui ajoute sa particule directement au moteur, a sa position dans le
            // gabarit : elle ne serait ni au bon endroit ni a la bonne taille.
            noteForeign(particle);
            return false;
        }
        Particle parent = ticking;
        if (parent == null) {
            // Ajoutee plus tard (certains mods, comme Lodestone, creent leurs particules un peu apres) : si elle nait
            // dans un Planarium dont on fait la miniature, elle vient d'un bloc de la miniature, a taille normale.
            // (Sauf les eclats d'un bloc qu'on casse : ceux du Planarium lui-meme naissent dans son bloc.)
            if (!SPACE.containsKey(particle) && !(particle instanceof net.minecraft.client.particle.TerrainParticle)) {
                net.minecraft.world.phys.AABB box = particle.getBoundingBox();
                if (com.matthieu.planariumview.client.mini.MiniParticles.covers((box.minX + box.maxX) * 0.5,
                        (box.minY + box.maxY) * 0.5, (box.minZ + box.maxZ) * 0.5)) {
                    noteForeign(particle);
                    return false;
                }
            }
            return true;
        }
        if (SPACE.containsKey(particle)) {
            return true;
        }
        Space space = SPACE.get(parent);
        if (attaching != null && space != null) {
            if (adopt(particle, space, LIGHT.getOrDefault(parent, 0x00F000F0))) {
                adding = true;
                try { add(particle); }
                finally { adding = false; }
            }
            return false;
        }
        if (space != null && space.bounds() != null) {
            noteForeign(particle);
            return false;
        }
        return true;
    }

    /** Vrai pendant que MiniParticles fait jouer l'ambiance des blocs d'une miniature. */
    private static boolean miniAnimating;

    public static void animatingMini(boolean animating) {
        miniAnimating = animating;
    }

    /** Ecrit une fois dans le log chaque sorte de particule enlevee d'une miniature. */
    private static void noteForeign(Particle particle) {
        if (UNSCALED.add(particle.getClass())) {
            LOGGER.debug("[Planarium Panorama] Particule d'un autre mod ignoree dans la miniature : {}",
                    particle.getClass().getName());
        }
    }

    /** Met une particule deja creee a l'echelle du faux monde. Faux (et la particule est enlevee) si c'est impossible. */
    private static boolean adopt(Particle particle, Space space, int light) {
        float scale = (float) space.scale();
        AABB nativeBox = particle.getBoundingBox();
        float before = particle instanceof com.matthieu.planariumview.mixin.SingleQuadParticleAccessor quad
                ? quad.planariumview$getQuadSize() : -1.0F;
        if (particle instanceof ParticleAccessor nudge && scale != 1.0F) {
            // Certaines particules se decalent un peu au hasard en naissant (flammes des torches : jusqu'a 0,05 bloc,
            // sans deplacer leur boite) : dans une miniature, ce decalage faisait 1,5 bloc et la flamme sautait a cote
            // de la torche pendant un tick. On le met a l'echelle du faux monde.
            net.minecraft.world.phys.AABB box = particle.getBoundingBox();
            double cx = (box.minX + box.maxX) * 0.5;
            double cz = (box.minZ + box.maxZ) * 0.5;
            double nx = cx + (nudge.planariumview$getX() - cx) * scale;
            double ny = box.minY + (nudge.planariumview$getY() - box.minY) * scale;
            double nz = cz + (nudge.planariumview$getZ() - cz) * scale;
            particle.setPos(nx, ny, nz);
            nudge.planariumview$setXo(nx);
            nudge.planariumview$setYo(ny);
            nudge.planariumview$setZo(nz);
        }
        particle.scale(scale);
        ArsParticles.scale(particle, space);
        if (space.bounds() != null && particle instanceof ParticleAccessor access) {
            // Particle.scale remplace la boite par 0,2 x 0,2, meme pour la fumee de feu de camp (0,25 x 0,25).
            // Garder ses dimensions natives a l'echelle pour les collisions avec les bords des blocs.
            access.planariumview$setSize((float) nativeBox.getXsize() * scale, (float) nativeBox.getYsize() * scale);
        }
        if (!resized(particle, before, scale)) {
            // Particule d'un autre mod qui garde sa taille (ou se dessine a sa facon) : dans une miniature, elle
            // apparaitrait 32 fois trop grande. On l'enleve.
            particle.remove();
            return false;
        }
        if (particle instanceof com.matthieu.planariumview.compat.dynamic_surroundings.SceneryFirefly firefly) {
            firefly.planariumview$space(space);
            // Le vol de DS evite deja les blocs : ici, garder seulement les limites du bocal.
            NO_COLLISION.put(particle, Boolean.TRUE);
        }
        if (particle instanceof ParticleAccessor access) {
            // La vitesse (y compris la part au hasard ajoutee par la particule) et la gravite, a l'echelle du faux monde.
            access.planariumview$setXd(access.planariumview$getXd() * scale);
            access.planariumview$setYd(access.planariumview$getYd() * scale);
            access.planariumview$setZd(access.planariumview$getZd() * scale);
            access.planariumview$setGravity(access.planariumview$getGravity() * scale);
            // Sauver le choix natif avant de couper les collisions avec les vrais blocs.
            // Falling Leaves deplace ses feuilles lui-meme : son sol est gere par endTick.
            boolean leaf = com.matthieu.planariumview.compat.falling_leaves.FallingLeavesCompat.isLeaf(particle);
            if (!access.planariumview$getHasPhysics() && !leaf) {
                NO_COLLISION.put(particle, Boolean.TRUE);
            }
            // La miniature garde la physique de Particle.move ; son calcul de collision est redirige vers le faux monde.
            // Le decor geant et les particules au mouvement personnalise gardent les collisions gerees ci-dessous.
            if (space.bounds() == null || leaf || NO_COLLISION.containsKey(particle)) {
                access.planariumview$setHasPhysics(false);
            }
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
        // ParticleGlow nait avec une taille nulle : initScale, adapte dans adopt, fixe sa taille aux ticks suivants.
        if (ArsParticles.supported(particle)) return true;
        // Miniature : particules de Minecraft et integrations dont la mise a l'echelle est geree ici. Les autres
        // se dessinent souvent a leur facon et restent geantes meme rapetissees.
        if (!particle.getClass().getName().startsWith("net.minecraft.")
                && !particle.getClass().getName().endsWith(".HotAirEmberParticle")
                && !com.matthieu.planariumview.compat.falling_leaves.FallingLeavesCompat.isLeaf(particle)
                && !(particle instanceof com.matthieu.planariumview.compat.dynamic_surroundings.SceneryFirefly)) {
            noteForeign(particle);
            return false;
        }
        // La taille de base (quadSize) et pas getQuadSize : la fumee, par exemple, part d'une taille 0 et grossit.
        boolean ok = particle instanceof com.matthieu.planariumview.mixin.SingleQuadParticleAccessor quad && before > 0.0F
                && quad.planariumview$getQuadSize() <= before * scale * 2.0F + 1.0E-4F;
        if (!ok && UNSCALED.add(particle.getClass())) {
            LOGGER.debug("[Planarium Panorama] Particule ignoree dans la miniature (sa taille ne change pas) : {}",
                    particle.getClass().getName());
        }
        return ok;
    }

    /** Particule du decor en train de bouger (voir ParticleEngineTickMixin), ou null. */
    private static Particle ticking;

    /** Vrai si collide a ete appele pendant ce tick (voir endTick). */
    private static boolean collided;
    /** Vent de Falling Leaves avant sa mise a l'echelle pour la feuille qui bouge, ou null. */
    private static float[] savedWind;

    public static void beginTick(Particle particle) {
        ticking = SPACE.isEmpty() || !SPACE.containsKey(particle) ? null : particle;
        collided = false;
        savedWind = null;
        if (ticking != null) {
            // Le vent de Falling Leaves souffle en blocs du vrai monde : a l'echelle du faux monde pour ses feuilles.
            Space space = SPACE.get(particle);
            if (space != null) {
                savedWind = com.matthieu.planariumview.compat.falling_leaves.FallingLeavesCompat.scaleWind(particle, space.scale());
            }
        }
    }

    public static void endTick(Particle particle) {
        Particle was = ticking;
        ticking = null;
        if (savedWind != null) {
            com.matthieu.planariumview.compat.falling_leaves.FallingLeavesCompat.restoreWind(savedWind);
            savedWind = null;
        }
        // Une particule qui bouge a sa facon (sans le move de Minecraft, comme les feuilles de Falling Leaves) : le
        // sol et les bords du faux monde quand meme.
        if (was != null && was == particle && !collided && particle.isAlive()
                && com.matthieu.planariumview.compat.falling_leaves.FallingLeavesCompat.isLeaf(particle) && particle instanceof ParticleAccessor access) {
            collide(particle, access.planariumview$getY() - access.planariumview$getYo());
        }
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

    /** Collision native de la boite entiere, dans les coordonnees du gabarit ; null pour une particule normale. */
    public static Vec3 miniCollision(Particle particle, Vec3 motion, AABB box) {
        Space space = SPACE.isEmpty() ? null : SPACE.get(particle);
        if (space == null || space.bounds() == null || space.world() == null) {
            return null;
        }
        double scale = space.scale();
        AABB localBox = new AABB((box.minX - space.ox()) / scale, (box.minY - space.oy()) / scale,
                (box.minZ - space.oz()) / scale, (box.maxX - space.ox()) / scale,
                (box.maxY - space.oy()) / scale, (box.maxZ - space.oz()) / scale);
        try {
            Vec3 localMotion = motion.scale(1.0 / scale);
            // Ne pas appeler Entity.collideBoundingBox : Lithium le remplace et lit directement les vrais chunks.
            // L'iterateur vanilla passe par notre getChunkForCollisions, qui renvoie les blocs de la capture.
            List<VoxelShape> shapes = new ArrayList<>();
            new BlockCollisions<VoxelShape>(space.world(), null, localBox.expandTowards(localMotion), false,
                    (pos, shape) -> shape).forEachRemaining(shapes::add);
            return collideWithShapes(localMotion, localBox, shapes).scale(scale);
        } catch (Exception error) {
            // Un bloc modde ne supporte pas le faux monde : laisser la particule continuer.
            if (!collisionWarned) {
                collisionWarned = true;
                LOGGER.warn("[Planarium Panorama] Collision d'une particule de miniature impossible", error);
            }
            return motion;
        }
    }

    /** Meme ordre d'axes que Minecraft, avec les formes deja lues dans le faux monde. */
    private static Vec3 collideWithShapes(Vec3 motion, AABB box, List<VoxelShape> shapes) {
        if (shapes.isEmpty()) return motion;
        double dx = motion.x, dy = motion.y, dz = motion.z;
        if (dy != 0.0) {
            dy = Shapes.collide(Direction.Axis.Y, box, shapes, dy);
            if (dy != 0.0) box = box.move(0.0, dy, 0.0);
        }
        boolean zFirst = Math.abs(dx) < Math.abs(dz);
        if (zFirst && dz != 0.0) {
            dz = Shapes.collide(Direction.Axis.Z, box, shapes, dz);
            if (dz != 0.0) box = box.move(0.0, 0.0, dz);
        }
        if (dx != 0.0) {
            dx = Shapes.collide(Direction.Axis.X, box, shapes, dx);
            if (!zFirst && dx != 0.0) box = box.move(dx, 0.0, 0.0);
        }
        if (!zFirst && dz != 0.0) {
            dz = Shapes.collide(Direction.Axis.Z, box, shapes, dz);
        }
        return new Vec3(dx, dy, dz);
    }

    /** Echelle du mouvement, y compris les accelerations ajoutees par une particule apres sa creation. */
    public static float motionScale(Particle particle) {
        Space space = SPACE.isEmpty() ? null : SPACE.get(particle);
        return space != null ? (float) space.scale() : 1.0F;
    }

    /**
     * Sol du faux monde : une particule physique qui descend dans un de ses blocs s'arrete dessus, comme en vanilla.
     * Celles qui ignorent les collisions (spores ambiantes) continuent leur mouvement. {@code dy} : deplacement vertical.
     */
    public static void collide(Particle particle, double dy) {
        collided = true;
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
        if (jar(particle, space, dy)) {
            return;
        }
        if (NO_COLLISION.containsKey(particle)) {
            return;
        }
        boolean nativeMini = space.bounds() != null && particle instanceof ParticleAccessor access
                && access.planariumview$getHasPhysics();
        if (dy == 0.0) {
            return;
        }
        if (dy > 0.0) {
            if (!nativeMini) ceiling(particle, space, dy);
            return;
        }
        LitRenderingWorld world = space.world();
        double scale = space.scale();
        net.minecraft.world.phys.AABB box = particle.getBoundingBox();
        double wx = (box.minX + box.maxX) * 0.5;
        double wz = (box.minZ + box.maxZ) * 0.5;
        // Feuilles de Falling Leaves : dessinees un peu plus haut que leur position.
        double lift = com.matthieu.planariumview.compat.falling_leaves.FallingLeavesCompat.renderLift(particle);
        double tx = (wx - space.ox()) / scale;
        double ty = (box.minY + lift - space.oy()) / scale;
        double tz = (wz - space.oz()) / scale;
        net.minecraft.core.BlockPos pos = net.minecraft.core.BlockPos.containing(tx, ty, tz);
        try {
            net.minecraft.world.level.block.state.BlockState state = world.getBlockState(pos);
            // Les eclaboussures de pluie retombent dans l'eau du decor, absente du vrai monde du client.
            if (particle instanceof net.minecraft.client.particle.WaterDropParticle) {
                var fluid = world.getFluidState(pos);
                if (!fluid.isEmpty() && ty < pos.getY() + fluid.getHeight(world, pos)) {
                    particle.remove();
                    return;
                }
            }
            // Particle.move a deja traite le sol et les vitesses de la miniature.
            if (nativeMini) return;
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
            particle.setPos(wx, top * scale + space.oy() - lift, wz);
            if (particle instanceof ParticleAccessor access) {
                access.planariumview$setYd(0.0);
                access.planariumview$setOnGround(true);
            }
        } catch (Exception ignored) {
            // Bloc modde qui suppose un vrai monde : la particule continue sans sol.
        }
    }

    /**
     * Decor geant : le Planarium lui-meme (le bocal ou se trouve le joueur) est vide dans le faux monde. Une particule
     * qui y tombe (goutte de lave ou d'eau sous un bloc juste au-dessus) s'arrete sur son toit, une qui monte s'arrete
     * sous son pied (la fumee d'un feu de camp en dessous), et une qui y entre par le cote disparait : rien du dehors n'entre dans le bocal. Vrai si la
     * particule a ete arretee ou enlevee.
     */
    private static boolean jar(Particle particle, Space space, double dy) {
        double scale = space.scale();
        if (space.bounds() != null || scale != (double) ClientView.GIANT) {
            return false;
        }
        // Le Planarium dans le vrai monde (la dimension d'Ars) : de son pied (le bas de son bloc dans le decor) au
        // plafond du bocal, parois comprises.
        double minX = com.matthieu.planariumview.client.jar.JarWalls.MIN;
        double maxX = com.matthieu.planariumview.client.jar.JarWalls.MAX + 1;
        double bottom = -ClientView.ARS_OFFSET * (double) ClientView.GIANT - ClientView.Y_SHIFT;
        double top = com.matthieu.planariumview.client.jar.JarWalls.MAX_Y + 1;
        net.minecraft.world.phys.AABB box = particle.getBoundingBox();
        double wx = (box.minX + box.maxX) * 0.5;
        double wz = (box.minZ + box.maxZ) * 0.5;
        if (wx < minX || wx >= maxX || wz < minX || wz >= maxX) {
            return false;
        }
        // Partie dessinee sous sa position : la moitie d'une particule normale (son carre est centre sur elle, et une
        // goutte du decor fait plusieurs blocs) ; rien pour une feuille de Falling Leaves, dessinee un peu plus haut.
        double lift = com.matthieu.planariumview.compat.falling_leaves.FallingLeavesCompat.renderLift(particle);
        double under = lift > 0.0 ? -lift
                : particle instanceof net.minecraft.client.particle.SingleQuadParticle quad ? quad.getQuadSize(0.0F) : 0.0;
        double low = box.minY - under;
        double high = box.maxY;
        if (low >= top || high <= bottom) {
            return false;
        }
        if (NO_COLLISION.containsKey(particle) && box.minY < top) {
            // Une particule d'ambiance traverse le terrain, mais ne doit ni entrer dans le bocal ni s'entasser sur son toit.
            particle.remove();
            return true;
        }
        if (dy < 0.0 && low - dy >= top - 1.0E-4) {
            // Tombee sur le toit du bocal.
            particle.setPos(wx, top + under, wz);
            if (particle instanceof ParticleAccessor access) {
                access.planariumview$setYd(0.0);
                access.planariumview$setOnGround(true);
            }
            return true;
        }
        if (dy > 0.0 && high - dy <= bottom + 1.0E-4) {
            // Montee sous le pied du Planarium.
            particle.setPos(wx, bottom - (box.maxY - box.minY), wz);
            if (particle instanceof ParticleAccessor access) {
                access.planariumview$setYd(0.0);
            }
            return true;
        }
        if (box.minY >= top) {
            // Seul son dessin touche le toit (goutte qui pend sous un bloc juste au-dessus) : on la laisse.
            return false;
        }
        particle.remove();
        return true;
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
        ArsParticles.beginTick();
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
        ArsParticles.clear();
        com.matthieu.planariumview.compat.dynamic_surroundings.DsFirefliesCompat.clear();
        LIGHT.clear();
        SPACE.clear();
        NO_COLLISION.clear();
        HUE.clear();
        HUES.clear();
        pendingHues = null;
        CopyParticles.clear();
        EMITTERS.clear();
        ticking = null;
        savedWind = null;
        creating = null;
        holding = false;
        adding = false;
        attaching = null;
    }
}

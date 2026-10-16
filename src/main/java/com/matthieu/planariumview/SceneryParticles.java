package com.matthieu.planariumview;

import com.matthieu.planariumview.mixin.ParticleAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Particules du decor geant et des miniatures : fumee des torches et des feux de camp, flammes, lave, gouttes,
 * explosions... Une particule du decor est 32 fois plus grande, va 32 fois plus vite et tombe 32 fois plus vite, comme
 * le decor ; une particule de miniature est 32 fois plus petite. Elle ne touche pas les vrais blocs, s'arrete sur ceux
 * de son faux monde, et prend la lumiere de l'endroit ou elle nait.
 */
public final class SceneryParticles {
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
                        net.minecraft.world.phys.AABB bounds, CachedLight light, java.util.function.IntUnaryOperator adjust) {
    }

    /**
     * Lumiere d'un faux monde bloc par bloc, comme celle que Minecraft donne aux particules (LevelRenderer.getLightColor),
     * avec la couleur de Contraption Lights. Gardee en memoire jusqu'au prochain changement du faux monde.
     */
    public static final class CachedLight {
        private final LitRenderingWorld world;
        private final java.util.function.IntSupplier version;
        private final Map<Long, Integer> values = new java.util.HashMap<>();
        private int seen = Integer.MIN_VALUE;

        CachedLight(LitRenderingWorld world, java.util.function.IntSupplier version) {
            this.world = world;
            this.version = version;
        }

        LitRenderingWorld world() {
            return world;
        }

        int at(net.minecraft.core.BlockPos pos) {
            int current = version.getAsInt();
            if (current != seen || values.size() > 32768) {
                values.clear();
                seen = current;
            }
            long key = pos.asLong();
            Integer value = values.get(key);
            if (value == null) {
                if (batch && !begun) {
                    ColorCompat.begin(world);
                    begun = true;
                } else if (!batch) {
                    ColorCompat.begin(world);
                }
                try {
                    value = net.minecraft.client.renderer.LevelRenderer.getLightColor(world, pos);
                } catch (Exception e) {
                    value = LightTexture.pack(15, 15);
                } finally {
                    if (!batch) {
                        ColorCompat.end();
                    }
                }
                values.put(key, value);
            }
            return value;
        }

        private boolean batch;
        private boolean begun;

        /** Beaucoup de lectures de suite (la pluie de tout le decor) : Contraption Lights n'est prepare qu'une fois. */
        void beginBatch() {
            batch = true;
        }

        void endBatch() {
            if (begun) {
                ColorCompat.end();
            }
            batch = false;
            begun = false;
        }
    }

    /** Lumiere du faux monde la ou se trouve maintenant la particule (la fumee s'assombrit en s'eloignant du feu). */
    private static Integer currentLight(Particle particle, Space space) {
        net.minecraft.world.phys.AABB box = particle.getBoundingBox();
        double scale = space.scale();
        net.minecraft.core.BlockPos pos = net.minecraft.core.BlockPos.containing(
                ((box.minX + box.maxX) * 0.5 - space.ox()) / scale,
                ((box.minY + box.maxY) * 0.5 - space.oy()) / scale,
                ((box.minZ + box.maxZ) * 0.5 - space.oz()) / scale);
        int light = space.light().at(pos);
        return space.adjust() != null ? space.adjust().applyAsInt(light) : light;
    }

    private static final Map<LitRenderingWorld, CachedLight> LIGHTS = Collections.synchronizedMap(new WeakHashMap<>());

    /** Lumiere partagee de ce faux monde (voir CachedLight). */
    static CachedLight lightFor(LitRenderingWorld world, java.util.function.IntSupplier version) {
        return LIGHTS.computeIfAbsent(world, w -> new CachedLight(w, version));
    }

    /** Le decor geant (positions du gabarit, Planarium au rayon {@code radius}). */
    static Space giantSpace(LitRenderingWorld world, int radius) {
        double giant = ClientView.GIANT;
        double origin = -(radius + ClientView.ARS_OFFSET) * giant;
        return new Space(world, giant, origin, origin - ClientView.Y_SHIFT, origin, null,
                lightFor(world, ClientView::sceneryVersion), null);
    }

    /** Lumiere d'une particule du decor, ou null pour une particule normale. */
    public static Integer lightOf(Particle particle) {
        if (LIGHT.isEmpty()) {
            return null;
        }
        Space space = SPACE.get(particle);
        if (space != null && space.light() != null) {
            try {
                return currentLight(particle, space);
            } catch (Exception ignored) {
                // position hors du faux monde : la lumiere de depart
            }
        }
        return LIGHT.get(particle);
    }

    /** Reglage "Particules" de Minecraft : rien en Minimal, une sur trois en Reduit. */
    static boolean allowed() {
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
    static void spawn(Space space, ParticleOptions options, double x, double y, double z, double vx, double vy, double vz,
                      int light) {
        spawnAt(space, options, x * space.scale() + space.ox(), y * space.scale() + space.oy(),
                z * space.scale() + space.oz(), vx, vy, vz, light);
    }

    /** Comme spawn, mais a une position deja dans le vrai monde (la vitesse reste celle du gabarit). */
    private static void spawnAt(Space space, ParticleOptions options, double x, double y, double z, double vx, double vy,
                                double vz, int light) {
        Minecraft mc = Minecraft.getInstance();
        Particle particle;
        try {
            particle = mc.particleEngine.createParticle(options, x, y, z, vx, vy, vz);
        } catch (Exception e) {
            return;
        }
        if (particle == null) {
            return;
        }
        float scale = (float) space.scale();
        particle.scale(scale);
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
        spawnAt(space, options, x, y, z, vx / scale, vy / scale, vz / scale,
                light != null ? light : LightTexture.pack(15, 15));
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
        final double x;
        final double y;
        final double z;
        int age;

        Emitter(Space space, double x, double y, double z) {
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
    static void tick() {
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

    static void clear() {
        LIGHT.clear();
        SPACE.clear();
        EMITTERS.clear();
        ticking = null;
    }
}

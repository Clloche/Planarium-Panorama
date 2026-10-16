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
 * Particules du decor geant : fumee des torches et des feux de camp, flammes, lave, explosions...
 * Une particule du decor est 32 fois plus grande, va 32 fois plus vite et tombe 32 fois plus vite, comme le decor.
 * Elle ne touche pas les blocs du bocal, et prend la lumiere du decor la ou elle nait.
 */
public final class SceneryParticles {
    private static final RandomSource RANDOM = RandomSource.create();
    /** Particules du decor et leur lumiere (lumiere des blocs et du ciel, format de la lightmap). */
    private static final Map<Particle, Integer> LIGHT = Collections.synchronizedMap(new WeakHashMap<>());

    private SceneryParticles() {
    }

    /** Lumiere d'une particule du decor, ou null pour une particule normale. */
    public static Integer lightOf(Particle particle) {
        return LIGHT.isEmpty() ? null : LIGHT.get(particle);
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
     * Cree une particule du decor. {@code dx, dy, dz} : position par rapport au Planarium (en blocs du monde),
     * {@code vx, vy, vz} : vitesse d'origine (en blocs du monde par tick), {@code light} : lumiere au format lightmap.
     */
    static void spawn(ParticleOptions options, double dx, double dy, double dz, double vx, double vy, double vz, int light) {
        spawnGiant(options, (dx - ClientView.ARS_OFFSET) * ClientView.GIANT,
                (dy - ClientView.ARS_OFFSET) * ClientView.GIANT - ClientView.Y_SHIFT,
                (dz - ClientView.ARS_OFFSET) * ClientView.GIANT, vx, vy, vz, light);
    }

    /** Comme spawn, mais a une position deja dans le decor geant (la vitesse reste celle d'origine). */
    private static void spawnGiant(ParticleOptions options, double x, double y, double z, double vx, double vy, double vz,
                                   int light) {
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
        float giant = ClientView.GIANT;
        particle.scale(giant);
        if (particle instanceof ParticleAccessor access) {
            // La vitesse (y compris la part au hasard ajoutee par la particule) et la gravite, a l'echelle du decor.
            access.planariumview$setXd(access.planariumview$getXd() * giant);
            access.planariumview$setYd(access.planariumview$getYd() * giant);
            access.planariumview$setZd(access.planariumview$getZd() * giant);
            access.planariumview$setGravity(access.planariumview$getGravity() * giant);
            access.planariumview$setHasPhysics(false);
        }
        LIGHT.put(particle, light);
    }

    /** Particule du decor en train de bouger (voir ParticleEngineTickMixin), ou null. */
    private static Particle ticking;

    public static void beginTick(Particle particle) {
        ticking = LIGHT.isEmpty() || !LIGHT.containsKey(particle) ? null : particle;
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
        Integer light = LIGHT.get(parent);
        float giant = ClientView.GIANT;
        // La vitesse du parent est deja a l'echelle du decor ; spawnGiant la remet a l'echelle.
        spawnGiant(options, x, y, z, vx / giant, vy / giant, vz / giant, light != null ? light : LightTexture.pack(15, 15));
        return true;
    }

    /**
     * Sol du decor : une particule du decor qui descend dans un bloc du decor s'arrete dessus (comme les vraies
     * particules sur les blocs), au lieu de traverser le decor. {@code dy} : deplacement vertical de ce tick.
     */
    public static void collide(Particle particle, double dy) {
        if (dy >= 0.0 || LIGHT.isEmpty() || !LIGHT.containsKey(particle)) {
            return;
        }
        LitRenderingWorld world = ClientView.sceneryWorld();
        if (world == null) {
            return;
        }
        float giant = ClientView.GIANT;
        int radius = ClientView.sceneryRadius();
        net.minecraft.world.phys.AABB box = particle.getBoundingBox();
        double gx = (box.minX + box.maxX) * 0.5;
        double gz = (box.minZ + box.maxZ) * 0.5;
        double tx = gx / giant + ClientView.ARS_OFFSET + radius;
        double ty = (box.minY + ClientView.Y_SHIFT) / giant + ClientView.ARS_OFFSET + radius;
        double tz = gz / giant + ClientView.ARS_OFFSET + radius;
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
            if (Double.isNaN(top) || ty - dy / giant < top - 1.0E-4) {
                return;
            }
            particle.setPos(gx, (top - ClientView.ARS_OFFSET - radius) * giant - ClientView.Y_SHIFT, gz);
            if (particle instanceof ParticleAccessor access) {
                access.planariumview$setYd(0.0);
                access.planariumview$setOnGround(true);
            }
        } catch (Exception ignored) {
            // Bloc modde qui suppose un vrai monde : la particule continue sans sol.
        }
    }

    /** Explosions du dehors en cours (position, tick), comme la particule EXPLOSION_EMITTER de Minecraft. */
    private static final java.util.List<double[]> EMITTERS = new java.util.ArrayList<>();

    /**
     * Explosion du dehors : comme Minecraft (EXPLOSION_EMITTER), pendant 8 ticks, 6 boules de fumee par tick autour du
     * centre, de plus en plus petites, a l'echelle du decor.
     */
    static void explosion(double dx, double dy, double dz) {
        if (EMITTERS.size() < 32) {
            EMITTERS.add(new double[]{dx, dy, dz, 0});
        }
    }

    /** Chaque tick client (hors pause). */
    static void tick() {
        if (EMITTERS.isEmpty()) {
            return;
        }
        int light = LightTexture.pack(15, 15);
        java.util.Iterator<double[]> it = EMITTERS.iterator();
        while (it.hasNext()) {
            double[] emitter = it.next();
            double size = emitter[3] / 8.0;
            for (int i = 0; i < 6; i++) {
                if (allowed()) {
                    spawn(ParticleTypes.EXPLOSION,
                            emitter[0] + (RANDOM.nextDouble() - RANDOM.nextDouble()) * 4.0,
                            emitter[1] + (RANDOM.nextDouble() - RANDOM.nextDouble()) * 4.0,
                            emitter[2] + (RANDOM.nextDouble() - RANDOM.nextDouble()) * 4.0, size, 0.0, 0.0, light);
                }
            }
            emitter[3]++;
            if (emitter[3] >= 8) {
                it.remove();
            }
        }
    }

    static void clear() {
        LIGHT.clear();
        EMITTERS.clear();
        ticking = null;
    }
}

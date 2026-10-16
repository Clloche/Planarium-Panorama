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
        Minecraft mc = Minecraft.getInstance();
        Particle particle;
        try {
            particle = mc.particleEngine.createParticle(options,
                    (dx - ClientView.ARS_OFFSET) * ClientView.GIANT,
                    (dy - ClientView.ARS_OFFSET) * ClientView.GIANT - ClientView.Y_SHIFT,
                    (dz - ClientView.ARS_OFFSET) * ClientView.GIANT, vx, vy, vz);
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

    /** Explosion du dehors : un nuage de grosses boules de fumee, comme celui de Minecraft, a l'echelle du decor. */
    static void explosion(double dx, double dy, double dz) {
        if (!allowed()) {
            return;
        }
        int light = LightTexture.pack(15, 15);
        for (int i = 0; i < 12; i++) {
            spawn(ParticleTypes.EXPLOSION,
                    dx + (RANDOM.nextDouble() - RANDOM.nextDouble()) * 3.0,
                    dy + (RANDOM.nextDouble() - RANDOM.nextDouble()) * 3.0,
                    dz + (RANDOM.nextDouble() - RANDOM.nextDouble()) * 3.0, 0.0, 0.0, 0.0, light);
        }
    }

    static void clear() {
        LIGHT.clear();
    }
}

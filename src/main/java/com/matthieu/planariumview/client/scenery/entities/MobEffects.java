package com.matthieu.planariumview.client.scenery.entities;

import com.matthieu.planariumview.client.scenery.ClientView;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.List;

/** Visual effects for copies which never run LivingEntity.tick or tickEffects. */
public final class MobEffects {
    private MobEffects() {}

    /** Read the synchronized particle list, including modded effects and the server's hide-particles setting. */
    public interface PotionParticles {
        List<ParticleOptions> planariumview$effectParticles();
        boolean planariumview$ambientEffects();
    }

    public record Emission(ParticleOptions options, double x, double y, double z) {}

    /** Same choice, frequency and bounds as LivingEntity.tickEffects; no effect ticking or damage. */
    public static Emission sample(LivingEntity entity, RandomSource random) {
        if (!(entity instanceof PotionParticles data)) return null;
        List<ParticleOptions> particles = data.planariumview$effectParticles();
        if (particles.isEmpty()) return null;
        int interval = (entity.isInvisible() ? 15 : 4) * (data.planariumview$ambientEffects() ? 5 : 1);
        if (random.nextInt(interval) != 0) return null;
        ParticleOptions options = particles.get(random.nextInt(particles.size()));
        return new Emission(options,
                entity.getX() + (random.nextDouble() - 0.5) * entity.getBbWidth(),
                entity.getY() + random.nextDouble() * entity.getBbHeight(),
                entity.getZ() + (random.nextDouble() - 0.5) * entity.getBbWidth());
    }

    /** Called after the copy has been placed at its interpolated source-world position for this tick. */
    static void tick(LivingEntity entity) {
        Emission particle = sample(entity, entity.getRandom());
        if (particle != null) ClientView.onMobParticle(particle.options(), particle.x(), particle.y(), particle.z());
    }

    /** Vanilla flames face the camera around the captured world's vertical, even in a tilted Planarium. */
    public static Quaternionf flameRotation(Matrix4f terrain, Quaternionf camera) {
        Quaternionf local = terrain.getUnnormalizedRotation(new Quaternionf()).normalize().conjugate().mul(camera);
        return Mth.rotationAroundAxis(Mth.Y_AXIS, local, new Quaternionf());
    }
}

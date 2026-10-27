package com.matthieu.planariumview.client.scenery;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.AmbientParticleSettings;

/** The biome portion of ClientLevel.doAnimateTick, using the captured source biome. */
public final class SceneryAmbientParticles {
    private SceneryAmbientParticles() {}

    public record Emission(ParticleOptions options, double x, double y, double z) {}

    /** Called for non-solid sampled positions, including air. Fluid ambience is emitted separately by animateBlock. */
    static void tick(LitRenderingWorld world, BlockPos at, RandomSource random) {
        if (!world.hasBiomes()) return;
        var settings = world.getBiome(at).value().getAmbientParticle();
        if (settings.isEmpty()) return;
        Emission particle = sample(settings.get(), at, random);
        if (particle != null) world.addParticle(particle.options(), particle.x(), particle.y(), particle.z(), 0, 0, 0);
    }

    public static Emission sample(AmbientParticleSettings settings, BlockPos at, RandomSource random) {
        if (!settings.canSpawn(random)) return null;
        return new Emission(settings.getOptions(), at.getX() + random.nextDouble(),
                at.getY() + random.nextDouble(), at.getZ() + random.nextDouble());
    }
}

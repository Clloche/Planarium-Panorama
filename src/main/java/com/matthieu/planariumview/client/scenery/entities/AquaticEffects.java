package com.matthieu.planariumview.client.scenery.entities;

import com.matthieu.planariumview.client.scenery.ClientView;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.GlowSquid;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Dolphin;
import net.minecraft.world.entity.animal.Squid;
import net.minecraft.world.phys.Vec3;

/** Native aquatic visual effects, sampled at the copy's delayed position without ticking its AI. */
public final class AquaticEffects {
    private AquaticEffects() {}

    @FunctionalInterface public interface Sink {
        void emit(ParticleOptions type, double x, double y, double z, double vx, double vy, double vz);
    }

    static void tick(LivingEntity entity, float yaw, float pitch, float bodyYaw, int inkBursts) {
        sample(entity, yaw, pitch, entity.getRandom(), ClientView::onMobParticle);
        if (inkBursts > 0 && entity instanceof Squid squid) {
            for (int i = 0; i < inkBursts; i++) ink(squid, bodyYaw, entity.getRandom(), ClientView::onMobParticle);
        }
    }

    public static void sample(LivingEntity entity, float yaw, float pitch, RandomSource random, Sink sink) {
        if (entity instanceof Dolphin dolphin && !dolphin.isNoAi() && entity.isInWater()
                && entity.getDeltaMovement().lengthSqr() > .03) {
            // Use the interpolated orientation, not fields left in the copy by the previous rendered frame.
            Vec3 look = Vec3.directionFromRotation(pitch, yaw);
            float sideX = Mth.cos(yaw * Mth.DEG_TO_RAD) * .3F;
            float sideZ = Mth.sin(yaw * Mth.DEG_TO_RAD) * .3F;
            float behind = 1.2F - random.nextFloat() * .7F;
            for (int i = 0; i < 2; i++) {
                sink.emit(ParticleTypes.DOLPHIN, entity.getX() - look.x * behind + sideX, entity.getY() - look.y,
                        entity.getZ() - look.z * behind + sideZ, 0, 0, 0);
                sink.emit(ParticleTypes.DOLPHIN, entity.getX() - look.x * behind - sideX, entity.getY() - look.y,
                        entity.getZ() - look.z * behind - sideZ, 0, 0, 0);
            }
        }
        if (entity instanceof GlowSquid) {
            sink.emit(ParticleTypes.GLOW, entity.getX() + (random.nextDouble() * 2 - 1) * entity.getBbWidth() * .6,
                    entity.getY() + random.nextDouble() * entity.getBbHeight(),
                    entity.getZ() + (random.nextDouble() * 2 - 1) * entity.getBbWidth() * .6, 0, 0, 0);
        }
    }

    public static void ink(Squid squid, float bodyYaw, RandomSource random, Sink sink) {
        float pitch = squid.xBodyRot * Mth.DEG_TO_RAD, yaw = -bodyYaw * Mth.DEG_TO_RAD;
        Vec3 origin = new Vec3(0, -1, 0).xRot(pitch).yRot(yaw).add(squid.getX(), squid.getY() + .5, squid.getZ());
        ParticleOptions type = squid instanceof GlowSquid ? ParticleTypes.GLOW_SQUID_INK : ParticleTypes.SQUID_INK;
        for (int i = 0; i < 30; i++) {
            Vec3 velocity = new Vec3(random.nextFloat() * .6 - .3, -1, random.nextFloat() * .6 - .3)
                    .xRot(pitch).yRot(yaw).scale((.3 + random.nextFloat() * 2) * .1);
            sink.emit(type, origin.x, origin.y, origin.z, velocity.x, velocity.y, velocity.z);
        }
    }
}

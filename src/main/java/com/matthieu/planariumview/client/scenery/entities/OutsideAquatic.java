package com.matthieu.planariumview.client.scenery.entities;

import com.matthieu.planariumview.network.entities.MobEnvironment;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Squid;

/** Water/land poses and aquatic animations, without running AI, fluid physics or damage on a copy. */
public final class OutsideAquatic {
    private OutsideAquatic() {}

    public interface EnvironmentCopy {
        void planariumview$environment(MobEnvironment state);
    }

    public static MobEnvironment interpolate(MobEnvironment before, MobEnvironment after, float fraction) {
        if (before == null || after == null) return before;
        MobEnvironment.SquidPose squid = before.squid();
        if (squid != null && after.squid() != null) {
            squid = new MobEnvironment.SquidPose(Mth.rotLerp(fraction, squid.pitch(), after.squid().pitch()),
                    Mth.rotLerp(fraction, squid.roll(), after.squid().roll()),
                    Mth.lerp(fraction, squid.tentacleAngle(), after.squid().tentacleAngle()));
        }
        // Discrete water/land changes belong to their server tick, just like mount changes.
        return new MobEnvironment(before.inWater(), before.underwater(), before.waterOrBubble(), before.onGround(),
                Mth.lerp(fraction, before.velocityX(), after.velocityX()), Mth.lerp(fraction, before.velocityY(), after.velocityY()),
                Mth.lerp(fraction, before.velocityZ(), after.velocityZ()), squid);
    }

    public static void apply(Entity entity, MobEnvironment state) {
        if (state == null) return;
        ((EnvironmentCopy) entity).planariumview$environment(state);
        entity.setDeltaMovement(state.velocityX(), state.velocityY(), state.velocityZ());
        if (entity instanceof Squid squid && state.squid() != null) {
            // Already interpolated on the shared display clock; the renderer must not interpolate again.
            squid.xBodyRot = squid.xBodyRotO = state.squid().pitch();
            squid.zBodyRot = squid.zBodyRotO = state.squid().roll();
            squid.tentacleAngle = squid.oldTentacleAngle = state.squid().tentacleAngle();
        }
    }

    /** GlowSquidRenderer's block-light contribution, including its recovery after being hurt. */
    public static int glowLight(int darkTicks, int ambient) {
        return Math.max(ambient, (int) Mth.clampedLerp(0, 15, 1 - darkTicks / 10.0F));
    }
}

package com.matthieu.planariumview.client.scenery;

import com.matthieu.planariumview.mixin.SceneryEntityAccessor;
import com.matthieu.planariumview.mixin.TntMinecartAccessor;
import com.matthieu.planariumview.network.OutsideMobsPayload.VehicleState;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.entity.vehicle.MinecartTNT;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import net.minecraft.world.level.Level;

/** Rendu et animations des vehicules. Les positions utilisent le meme flux interpole que les mobs. */
public final class OutsideVehicles {
    private OutsideVehicles() {}

    public interface BoatVisuals {
        void planariumview$visuals(float left, float right, float bubble, boolean underwater);
    }

    static final class Animation {
        private float bubble, previousBubble;

        void tick(Entity entity, VehicleState visual) {
            previousBubble = bubble;
            bubble = Mth.clamp(bubble + (visual != null && visual.bubbleTicks() > 0 ? 0.05F : -0.1F), 0, 1);
            if (entity instanceof VehicleEntity vehicle) {
                if (vehicle.getHurtTime() > 0) vehicle.setHurtTime(vehicle.getHurtTime() - 1);
                if (vehicle.getDamage() > 0) vehicle.setDamage(Math.max(0, vehicle.getDamage() - 1));
            }
        }

        void prepare(Entity entity, VehicleState visual, double time, float partialTick) {
            if (visual == null) return;
            if (entity instanceof Boat boat) {
                float angle = 10 * (float) Math.sin(0.5 * time) * Mth.lerp(partialTick, previousBubble, bubble);
                ((BoatVisuals) boat).planariumview$visuals(visual.leftPaddle(), visual.rightPaddle(), angle, visual.underwater());
            } else if (entity instanceof MinecartTNT tnt) {
                ((TntMinecartAccessor) tnt).planariumview$setFuse(visual.fuse());
            }
        }
    }

    static VehicleState interpolate(VehicleState before, VehicleState after, float fraction) {
        if (before == null || after == null) return before;
        // L'arret des rames remet leur phase a zero : ne pas les faire tourner en sens inverse.
        float left = before.leftPaddle() > 0 && after.leftPaddle() >= before.leftPaddle()
                ? Mth.lerp(fraction, before.leftPaddle(), after.leftPaddle()) : before.leftPaddle();
        float right = before.rightPaddle() > 0 && after.rightPaddle() >= before.rightPaddle()
                ? Mth.lerp(fraction, before.rightPaddle(), after.rightPaddle()) : before.rightPaddle();
        int fuse = before.fuse() >= 0 && after.fuse() >= 0 ? Math.round(Mth.lerp(fraction, before.fuse(), after.fuse())) : before.fuse();
        return new VehicleState(left, right, before.bubbleTicks(), before.underwater(), fuse);
    }

    static void render(Entity entity, Level scenery, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
        Level original = entity.level();
        boolean vehicle = entity instanceof VehicleEntity;
        try {
            // MinecartRenderer.getPos/getPosOffs doivent lire les rails de la photo, dans le meme repere que la copie.
            if (vehicle) ((SceneryEntityAccessor) entity).planariumview$setLevel(scenery);
            GiantEntities.render(entity, scenery, partialTick, pose, buffers, light);
        } finally {
            if (vehicle) ((SceneryEntityAccessor) entity).planariumview$setLevel(original);
        }
    }
}

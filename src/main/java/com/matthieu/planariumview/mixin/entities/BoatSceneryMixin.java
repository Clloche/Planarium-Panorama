package com.matthieu.planariumview.mixin.entities;

import com.matthieu.planariumview.client.scenery.entities.OutsideVehicles;
import net.minecraft.world.entity.vehicle.Boat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Les copies ne tickent pas : le rendu lit les animations recues et interpolees, meme si le pilote est un joueur. */
@Mixin(Boat.class)
public abstract class BoatSceneryMixin implements OutsideVehicles.BoatVisuals {
    @Unique private boolean planariumview$copy;
    @Unique private float planariumview$left;
    @Unique private float planariumview$right;
    @Unique private float planariumview$bubble;
    @Unique private boolean planariumview$underwater;

    @Override
    public void planariumview$visuals(float left, float right, float bubble, boolean underwater) {
        planariumview$copy = true;
        planariumview$left = left;
        planariumview$right = right;
        planariumview$bubble = bubble;
        planariumview$underwater = underwater;
    }

    @Inject(method = "getRowingTime", at = @At("HEAD"), cancellable = true)
    private void planariumview$rowing(int paddle, float partialTick, CallbackInfoReturnable<Float> cir) {
        if (planariumview$copy) cir.setReturnValue(paddle == 0 ? planariumview$left : planariumview$right);
    }

    @Inject(method = "getBubbleAngle", at = @At("HEAD"), cancellable = true)
    private void planariumview$bubbles(float partialTick, CallbackInfoReturnable<Float> cir) {
        if (planariumview$copy) cir.setReturnValue(planariumview$bubble);
    }

    @Inject(method = "isUnderWater", at = @At("HEAD"), cancellable = true)
    private void planariumview$waterMask(CallbackInfoReturnable<Boolean> cir) {
        if (planariumview$copy) cir.setReturnValue(planariumview$underwater);
    }
}

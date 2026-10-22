package com.matthieu.planariumview.mixin;

import com.matthieu.planariumview.client.scenery.ClientView;
import com.matthieu.planariumview.client.scenery.OutsideFluidView;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Camera;
import net.minecraft.world.level.material.FogType;

import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Decor geant : dans le bocal, le jeu dessine assez loin pour le voir en entier, anneau lointain compris (comme Voxy
 * le fait pour son propre rendu). Avant, le decor etait limite par la distance d'affichage. Ailleurs, rien ne change.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererFarMixin {
    @Inject(method = "getDepthFar", at = @At("RETURN"), cancellable = true)
    private void planariumview$sceneryFar(CallbackInfoReturnable<Float> cir) {
        float needed = ClientView.sceneryDepthFar();
        if (needed > cir.getReturnValueF()) {
            cir.setReturnValue(needed);
        }
    }

    /** Champ de vision : le vrai liquide de la camera, sans l'eau du dehors vue a travers le bocal. */
    @WrapOperation(method = "getFov", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Camera;getFluidInCamera()Lnet/minecraft/world/level/material/FogType;"))
    private FogType planariumview$dryFov(Camera camera, Operation<FogType> original) {
        OutsideFluidView.fovQuery(true);
        try {
            return original.call(camera);
        } finally {
            OutsideFluidView.fovQuery(false);
        }
    }
}

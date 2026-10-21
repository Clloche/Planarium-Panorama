package com.matthieu.planariumview.mixin;

import com.matthieu.planariumview.client.scenery.ClientView;

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
}

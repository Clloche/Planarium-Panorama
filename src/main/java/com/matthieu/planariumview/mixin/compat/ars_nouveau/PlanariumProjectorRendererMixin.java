package com.matthieu.planariumview.mixin.compat.ars_nouveau;

import com.matthieu.planariumview.client.sky.SkySync;

import com.hollingsworth.arsnouveau.client.renderer.tile.PlanariumProjectorRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Dans le bocal, Ars dessine les parois de verre et le plancher (le Planarium geant, a l'envers)
 * toujours a pleine luminosite, ce qui eblouit la nuit. Avec la vraie luminosite, on les eclaire
 * comme le reste du bocal : autant que l'endroit ou se trouve le Planarium.
 */
@Mixin(value = PlanariumProjectorRenderer.class, remap = false)
public abstract class PlanariumProjectorRendererMixin {

    @ModifyArg(method = "render",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/block/BlockRenderDispatcher;renderSingleBlock(Lnet/minecraft/world/level/block/state/BlockState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V"),
            index = 3, require = 0)
    private int planariumview$light(int packedLight) {
        return SkySync.projectorLight(packedLight);
    }

    /** Le modele est redecoupe en carres d'un bloc, chacun avec la vraie lumiere du bocal a cet endroit. */
    @ModifyArg(method = "render",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/block/BlockRenderDispatcher;renderSingleBlock(Lnet/minecraft/world/level/block/state/BlockState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V"),
            index = 2, require = 0)
    private net.minecraft.client.renderer.MultiBufferSource planariumview$buffers(
            net.minecraft.client.renderer.MultiBufferSource source) {
        return com.matthieu.planariumview.client.jar.ProjectorLighting.wrap(source);
    }

    /** Juste apres le dessin du modele : le cache GPU des parois est mis a jour puis dessine (voir ProjectorLighting). */
    @Inject(method = "render",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/block/BlockRenderDispatcher;renderSingleBlock(Lnet/minecraft/world/level/block/state/BlockState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
                    shift = At.Shift.AFTER),
            require = 0)
    private void planariumview$afterModel(CallbackInfo ci) {
        com.matthieu.planariumview.client.jar.ProjectorLighting.afterRender();
    }
}

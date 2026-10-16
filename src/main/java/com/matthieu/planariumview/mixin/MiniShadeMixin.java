package com.matthieu.planariumview.mixin;

import com.matthieu.planariumview.client.scenery.LitRenderingWorld;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Miniature seulement : l'ombrage des coins de Minecraft (copie par Ars) assombrit chaque coin touche par un bloc plein
 * (facteur 0.2). Dans un petit recoin, presque tous les coins en touchent un : il devenait bien plus sombre que dans
 * le bocal (dessine par Sodium). Dans le faux monde de la miniature, un bloc plein assombrit un peu moins.
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class MiniShadeMixin {
    private static final float MINI_SHADE = 0.6F;

    @Inject(method = "getShadeBrightness", at = @At("RETURN"), cancellable = true)
    private void planariumview$softerMiniShade(BlockGetter level, BlockPos pos, CallbackInfoReturnable<Float> cir) {
        if (level instanceof LitRenderingWorld && !LitRenderingWorld.isGiant(level) && cir.getReturnValueF() < MINI_SHADE) {
            cir.setReturnValue(MINI_SHADE);
        }
    }
}

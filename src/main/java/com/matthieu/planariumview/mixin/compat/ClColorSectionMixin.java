package com.matthieu.planariumview.mixin.compat;

import com.matthieu.planariumview.client.jar.JarColor;

import com.llamalad7.mixinextras.sugar.Local;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Contraption Lights : la couleur de la lumiere au milieu du bocal (voir JarColor).
 * Sans ce correctif, la couleur ne depasse pas quelques blocs autour des parois.
 */
@Mixin(targets = "xyz.atmerek.contraptionlights.light.color.ColorLightEngine", remap = false)
public abstract class ClColorSectionMixin {

    @Inject(method = "colorSection(Lxyz/atmerek/contraptionlights/light/color/ColorLightEngine$Scratch;Lnet/minecraft/world/level/BlockAndTintGetter;IIIZ)[S",
            at = @At("RETURN"), cancellable = true, require = 0)
    private static void planariumview$jarColor(CallbackInfoReturnable<short[]> cir,
                                               @Local(ordinal = 0, argsOnly = true) int sx,
                                               @Local(ordinal = 1, argsOnly = true) int sy,
                                               @Local(ordinal = 2, argsOnly = true) int sz) {
        short[] original = cir.getReturnValue();
        short[] result = JarColor.apply(original, sx, sy, sz);
        if (result != original) {
            cir.setReturnValue(result);
        }
    }
}

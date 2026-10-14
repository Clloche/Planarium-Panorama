package com.matthieu.planariumview.mixin;

import com.matthieu.planariumview.SkySync;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Ciel d'une dimension moddee dans le bocal (voir SkySync.sourceEffects) : on garde ce qu'on fait avec notre propre ciel.
 * Pas de nuages (ils seraient a la mauvaise echelle) et la vraie lightmap du bocal.
 * (isFoggyAt est abstraite dans DimensionSpecialEffects : on ne peut pas s'y accrocher ici.)
 */
@Mixin(DimensionSpecialEffects.class)
public abstract class SourceEffectsMixin {

    @Inject(method = "getCloudHeight", at = @At("HEAD"), cancellable = true, require = 0)
    private void planariumview$noClouds(CallbackInfoReturnable<Float> cir) {
        if (SkySync.usesSourceEffects((DimensionSpecialEffects) (Object) this)) {
            cir.setReturnValue(Float.NaN);
        }
    }

    @Inject(method = "forceBrightLightmap", at = @At("HEAD"), cancellable = true, require = 0)
    private void planariumview$realLightmap(CallbackInfoReturnable<Boolean> cir) {
        if (SkySync.usesSourceEffects((DimensionSpecialEffects) (Object) this)) {
            cir.setReturnValue(false);
        }
    }
}

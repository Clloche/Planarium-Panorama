package com.matthieu.planariumview.mixin.compat;

import com.matthieu.planariumview.compat.DsFirefliesCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * DS 0.4.7 et plus : FireflyLights est devenu PointLights, avec un rayon par lumiere. Les lucioles du decor y entrent
 * avec un rayon a leur echelle (voir DsFirefliesCompat.pointRadius). Avant 0.4.7, voir DsFireflyLightsMixin.
 */
@Pseudo
@Mixin(targets = "org.orecruncher.dsurround.effects.particles.PointLights", remap = false)
public abstract class DsPointLightsMixin {
    @Inject(method = "add(DDDFFFFF)V", at = @At("HEAD"), cancellable = true, require = 0)
    private static void planariumview$skip(double x, double y, double z, float r, float g, float b, float strength,
                                           float radius, CallbackInfo ci) {
        if (DsFirefliesCompat.skipPointLight()) ci.cancel();
    }

    @ModifyVariable(method = "add(DDDFFFFF)V", at = @At("HEAD"), argsOnly = true, ordinal = 4, require = 0)
    private static float planariumview$radius(float radius) {
        return DsFirefliesCompat.pointRadius(radius);
    }
}

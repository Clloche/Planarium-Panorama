package com.matthieu.planariumview.mixin.compat;

import com.matthieu.planariumview.compat.DsFirefliesCompat;
import com.mojang.blaze3d.vertex.Tesselator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** DS avant 0.4.7 : reutilise le shader de DS pour chaque echelle, sans changer ses lumieres du vrai monde. */
@Pseudo
@Mixin(targets = "org.orecruncher.dsurround.effects.particles.FireflyLights", remap = false)
public abstract class DsFireflyLightsMixin {
    @Inject(method = "add", at = @At("HEAD"), cancellable = true, require = 0)
    private static void planariumview$collect(double x, double y, double z, float r, float g, float b, float strength,
                                            CallbackInfo ci) {
        if (DsFirefliesCompat.captureLight(x, y, z, r, g, b, strength)) ci.cancel();
    }

    @ModifyConstant(method = "drawLights", constant = @Constant(floatValue = 1.75F), require = 0)
    private static float planariumview$radius(float radius) {
        return DsFirefliesCompat.lightRadius(radius);
    }

    @Inject(method = "draw", at = @At("RETURN"), require = 0)
    private static void planariumview$draw(Tesselator tesselator, CallbackInfo ci) {
        DsFirefliesCompat.drawScaledLights(tesselator);
    }
}

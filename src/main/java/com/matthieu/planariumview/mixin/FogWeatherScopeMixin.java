package com.matthieu.planariumview.mixin;

import com.matthieu.planariumview.client.sky.SkySync;

import net.minecraft.client.renderer.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** La couleur du brouillard et de l'horizon s'assombrit aussi sous la pluie du dehors. */
@Mixin(FogRenderer.class)
public abstract class FogWeatherScopeMixin {

    @Inject(method = "setupColor", at = @At("HEAD"), require = 0)
    private static void planariumview$start(CallbackInfo ci) {
        SkySync.enterWeatherScope();
    }

    @Inject(method = "setupColor", at = @At("RETURN"), require = 0)
    private static void planariumview$end(CallbackInfo ci) {
        SkySync.exitWeatherScope();
    }
}

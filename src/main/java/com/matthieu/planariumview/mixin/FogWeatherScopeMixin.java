package com.matthieu.planariumview.mixin;

import com.matthieu.planariumview.client.sky.SkySync;
import com.matthieu.planariumview.client.scenery.OutsideFluidView;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** La couleur du brouillard et de l'horizon s'assombrit aussi sous la pluie du dehors. */
@Mixin(FogRenderer.class)
public abstract class FogWeatherScopeMixin {

    @Inject(method = "setupFog", at = @At("TAIL"))
    private static void planariumview$fluidDistance(Camera camera, FogRenderer.FogMode mode, float far,
                                                   boolean foggy, float partialTick, CallbackInfo ci) {
        // The sky dome uses vanilla distances; only terrain is enlarged in the panorama.
        if (mode == FogRenderer.FogMode.FOG_TERRAIN) OutsideFluidView.applyFog(camera);
    }

    @Redirect(method = "setupColor", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;getWaterVision()F"))
    private static float planariumview$waterVision(LocalPlayer player) {
        return OutsideFluidView.visualFog(Minecraft.getInstance().gameRenderer.getMainCamera()) == FogType.WATER
                ? 1.0F : player.getWaterVision();
    }

    @Inject(method = "setupColor", at = @At("HEAD"), require = 0)
    private static void planariumview$start(CallbackInfo ci) {
        SkySync.enterWeatherScope();
    }

    @Inject(method = "setupColor", at = @At("RETURN"), require = 0)
    private static void planariumview$end(CallbackInfo ci) {
        SkySync.exitWeatherScope();
    }
}

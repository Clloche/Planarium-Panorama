package com.matthieu.planariumview.mixin;

import com.matthieu.planariumview.client.sky.SkySync;
import com.matthieu.planariumview.client.scenery.OutsideFluidView;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
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
                ? OutsideFluidView.waterVision() : player.getWaterVision();
    }

    @Inject(method = "setupColor", at = @At("HEAD"), require = 0)
    private static void planariumview$start(CallbackInfo ci) {
        SkySync.enterWeatherScope();
    }

    @Shadow
    private static float fogRed;
    @Shadow
    private static float fogGreen;
    @Shadow
    private static float fogBlue;

    /**
     * Option Sombre : Planarium sous l'eau au fond d'une caverne fermee. Le brouillard de l'eau devient noir comme la
     * caverne, au lieu du bleu de vanilla (voir SkySync.blackWater).
     */
    @Inject(method = "setupColor", at = @At("RETURN"), require = 0)
    private static void planariumview$blackWater(Camera camera, float partialTick, ClientLevel level, int renderDistance,
                                                 float darkenWorldAmount, CallbackInfo ci) {
        if (level == null || OutsideFluidView.visualFog(camera) != FogType.WATER || !SkySync.blackWater(level)) return;
        fogRed = 0.015F;
        fogGreen = 0.015F;
        fogBlue = 0.02F;
        RenderSystem.clearColor(fogRed, fogGreen, fogBlue, 0.0F);
    }

    @Inject(method = "setupColor", at = @At("RETURN"), require = 0)
    private static void planariumview$end(CallbackInfo ci) {
        SkySync.exitWeatherScope();
    }
}

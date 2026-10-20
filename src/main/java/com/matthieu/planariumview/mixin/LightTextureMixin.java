package com.matthieu.planariumview.mixin;

import com.matthieu.planariumview.client.sky.SkySync;

import net.minecraft.client.renderer.LightTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Quand Minecraft recalcule la lightmap (au debut de l'image), on prepare aussi celle du decor geant.
 * Rien n'est recalcule pendant le dessin de l'image : le decor geant change seulement de texture.
 */
@Mixin(LightTexture.class)
public abstract class LightTextureMixin {

    /**
     * Apres chaque appel (une fois par image). SkySync detecte lui-meme si Minecraft a recalcule la lightmap :
     * on ne se fie pas au drapeau de mise a jour, que d'autres mods peuvent modifier.
     */
    /** Note si Minecraft va vraiment recalculer la lightmap pendant cet appel. */
    @Inject(method = "updateLightTexture", at = @At("HEAD"))
    private void planariumview$head(float partialTick, CallbackInfo ci) {
        SkySync.lightmapWillRecompute(((LightTextureAccessor) this).planariumview$getUpdateLightTexture());
        // Pendant le calcul de la lightmap, le bocal a la pluie du dehors (voir LevelWeatherMixin). Vanilla ne la lit
        // pas ici (getSkyDarken l'inclut deja), mais Enhanced Celestials 2 si : sans pluie, il teintait la lumiere du
        // ciel du bocal avec la couleur de l'evenement lunaire pendant les orages (sol rose), et pas dehors.
        SkySync.enterWeatherScope();
    }

    @Inject(method = "updateLightTexture", at = @At("TAIL"))
    private void planariumview$tail(float partialTick, CallbackInfo ci) {
        SkySync.afterLightmapUpdate((LightTexture) (Object) this, partialTick, "lightmap");
    }

    /** A chaque sortie (aussi quand Minecraft ne recalcule rien et sort tout de suite). */
    @Inject(method = "updateLightTexture", at = @At("RETURN"))
    private void planariumview$exitWeather(float partialTick, CallbackInfo ci) {
        SkySync.exitWeatherScope();
    }
}

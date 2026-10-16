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
    }

    @Inject(method = "updateLightTexture", at = @At("TAIL"))
    private void planariumview$tail(float partialTick, CallbackInfo ci) {
        SkySync.afterLightmapUpdate((LightTexture) (Object) this, partialTick, "lightmap");
    }
}

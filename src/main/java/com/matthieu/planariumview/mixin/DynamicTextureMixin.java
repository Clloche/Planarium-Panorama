package com.matthieu.planariumview.mixin;

import com.matthieu.planariumview.client.sky.SkySync;

import net.minecraft.client.renderer.texture.DynamicTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Juste avant que la lightmap soit envoyee a la carte graphique, avec sa version finale (apres Minecraft
 * et les mods qui la modifient ou la recalculent eux-memes, comme Iris ou Darkness), on prepare celle du bocal.
 */
@Mixin(DynamicTexture.class)
public abstract class DynamicTextureMixin {

    @Inject(method = "upload", at = @At("HEAD"), require = 0)
    private void planariumview$beforeUpload(CallbackInfo ci) {
        SkySync.beforeTextureUpload((DynamicTexture) (Object) this);
    }
}

package com.matthieu.planariumview.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Acces a la lightmap : forcer son recalcul, lire ses pixels, et changer la texture utilisee pour dessiner. */
@Mixin(LightTexture.class)
public interface LightTextureAccessor {
    @Accessor("updateLightTexture")
    void planariumview$setUpdateLightTexture(boolean update);

    @Accessor("updateLightTexture")
    boolean planariumview$getUpdateLightTexture();

    @Accessor("lightTexture")
    net.minecraft.client.renderer.texture.DynamicTexture planariumview$getTexture();

    @Accessor("lightPixels")
    NativeImage planariumview$getLightPixels();

    @Accessor("lightTextureLocation")
    ResourceLocation planariumview$getLocation();

    @Mutable
    @Accessor("lightTextureLocation")
    void planariumview$setLocation(ResourceLocation location);
}

package com.matthieu.planariumview.mixin;

import com.matthieu.planariumview.client.scenery.OutsideFluidView;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.UnderwaterAmbientSoundHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Vanilla's occasional underwater sounds also follow immersion outside the glass. */
@Mixin(UnderwaterAmbientSoundHandler.class)
public abstract class UnderwaterAmbienceMixin {
    @Redirect(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;isUnderWater()Z"))
    private boolean planariumview$underwater(LocalPlayer player) {
        return player.isUnderWater() || OutsideFluidView.underwaterAmbience();
    }
}

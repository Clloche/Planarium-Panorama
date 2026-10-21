package com.matthieu.planariumview.mixin;

import com.matthieu.planariumview.client.sound.MachineSounds;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SoundManager.class)
public abstract class CopySoundMixin {
    @Inject(method = "play", at = @At("HEAD"), cancellable = true)
    private void planariumview$previewSound(SoundInstance sound, CallbackInfo ci) {
        if (MachineSounds.capture(sound)) ci.cancel();
    }

    @Inject(method = "queueTickingSound", at = @At("HEAD"), cancellable = true)
    private void planariumview$previewLoop(TickableSoundInstance sound, CallbackInfo ci) {
        if (MachineSounds.capture(sound)) ci.cancel();
    }
}

package com.matthieu.planariumview.mixin.compat;

import com.matthieu.planariumview.client.scenery.SceneryReverb;

import net.minecraft.client.resources.sounds.SoundInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Dynamic Surroundings ignore les sons sans attenuation (sons globaux). Les sons du dehors en sont (deja attenues par
 * la distance dans le vrai monde) : on les lui fait traiter, dans le decor (voir SceneryReverb). SoundFXProcessor
 * jusqu'a DS 0.4.6, EnhancedSoundProcessor en 0.4.7.
 */
@Pseudo
@Mixin(targets = {"org.orecruncher.dsurround.runtime.audio.SoundFXProcessor",
        "org.orecruncher.dsurround.runtime.audio.EnhancedSoundProcessor"}, remap = false)
public abstract class DsSoundProcessorMixin {

    @Inject(method = "shouldIgnoreSound", at = @At("HEAD"), cancellable = true, require = 0)
    private static void planariumview$processScenery(SoundInstance sound, CallbackInfoReturnable<Boolean> cir) {
        if (SceneryReverb.handles(sound)) {
            cir.setReturnValue(false);
        }
    }
}

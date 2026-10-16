package com.matthieu.planariumview.mixin.compat;

import com.matthieu.planariumview.compat.AmbientSoundsCompat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** AmbientSounds : dans un bocal, la meteo et la hauteur sont celles de l'endroit du Planarium. */
@Pseudo
@Mixin(targets = "team.creative.ambientsounds.environment.AmbientEnvironment", remap = false)
public abstract class AsEnvironmentMixin {

    @Inject(method = "analyzeFast", at = @At("TAIL"), require = 0)
    private void planariumview$outside(CallbackInfo ci) {
        AmbientSoundsCompat.afterFast(this);
    }
}

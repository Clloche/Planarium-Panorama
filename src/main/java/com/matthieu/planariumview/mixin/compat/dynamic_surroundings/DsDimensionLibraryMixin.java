package com.matthieu.planariumview.mixin.compat.dynamic_surroundings;

import com.matthieu.planariumview.compat.dynamic_surroundings.DsAltitude;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Garde la bibliotheque de dimensions de Dynamic Surroundings, pour lire ses reglages d'altitude (voir DsAltitude). */
@Mixin(targets = "org.orecruncher.dsurround.config.libraries.impl.DimensionLibrary", remap = false)
public abstract class DsDimensionLibraryMixin {

    @Inject(method = "<init>", at = @At("TAIL"), require = 0)
    private void planariumview$keep(CallbackInfo ci) {
        DsAltitude.setLibrary(this);
    }
}

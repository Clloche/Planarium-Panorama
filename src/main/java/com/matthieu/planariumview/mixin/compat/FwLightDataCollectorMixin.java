package com.matthieu.planariumview.mixin.compat;

import com.matthieu.planariumview.compat.FlywheelCompat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Flywheel copie la lumiere de Minecraft section par section dans sa memoire graphique, pour les blocs speciaux
 * qu'il dessine (coffres, lits...). On y ajoute la lumiere qui entre dans le bocal (voir FlywheelCompat).
 */
@Mixin(targets = "dev.engine_room.flywheel.backend.engine.LightDataCollector", remap = false)
public abstract class FwLightDataCollectorMixin {

    @Inject(method = "collectSection", at = @At("TAIL"), require = 0)
    private void planariumview$jarLight(long ptr, long section, CallbackInfo ci) {
        FlywheelCompat.boostSection(ptr, section);
    }
}

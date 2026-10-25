package com.matthieu.planariumview.mixin.compat.dynamic_surroundings;

import com.matthieu.planariumview.client.sky.SkySync;
import com.matthieu.planariumview.client.sound.OutsideAmbience;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** DS 0.4.7 : ses nouveaux simulateurs lisent aussi la meteo du dehors, sans faire pleuvoir dans la poche. */
@Pseudo
@Mixin(targets = {
        "org.orecruncher.dsurround.runtime.weather.WeatherSimulation",
        "org.orecruncher.dsurround.runtime.weather.WindService"
}, remap = false)
public abstract class DsWeatherSimulationMixin {
    private static boolean planariumview$outside(ClientLevel level) {
        return OutsideAmbience.active() && SkySync.inKnownJar(level);
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;getRainLevel(F)F"), require = 0)
    private float planariumview$rain(ClientLevel level, float partialTick) {
        return planariumview$outside(level) ? SkySync.rainLevel(level) : level.getRainLevel(partialTick);
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;isThundering()Z"), require = 0)
    private boolean planariumview$thunder(ClientLevel level) {
        return planariumview$outside(level) ? SkySync.thunderLevel(level) > 0.9F : level.isThundering();
    }
}

package com.matthieu.planariumview.mixin.compat.dynamic_surroundings;

import com.matthieu.planariumview.client.sky.SkySync;
import com.matthieu.planariumview.client.sound.OutsideAmbience;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Dynamic Surroundings : dans un bocal, "weather.isRaining" et compagnie suivent la meteo du dehors.
 * Le bocal dit au jeu qu'il n'y pleut jamais (pour ne pas y faire tomber la vraie pluie) : sans ce correctif,
 * les sons qui ne jouent que par beau temps (oiseaux de la foret...) continuaient sous la pluie.
 * On remplace les appels a la meteo du monde (les champs de cette classe changent selon la version de DS).
 */
@Mixin(targets = "org.orecruncher.dsurround.runtime.variables.WeatherVariables", remap = false)
public abstract class DsWeatherVariablesMixin {

    private static boolean planariumview$outside(Level level) {
        return level instanceof ClientLevel client && OutsideAmbience.active() && SkySync.inKnownJar(client);
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;isRaining()Z"),
            require = 0)
    private boolean planariumview$isRaining(Level level) {
        return planariumview$outside(level) ? SkySync.rainLevel((ClientLevel) level) > 0.2F : level.isRaining();
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;isThundering()Z"),
            require = 0)
    private boolean planariumview$isThundering(Level level) {
        return planariumview$outside(level) ? SkySync.thunderLevel((ClientLevel) level) > 0.9F : level.isThundering();
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getRainLevel(F)F"),
            require = 0)
    private float planariumview$rainLevel(Level level, float partialTick) {
        return planariumview$outside(level) ? SkySync.rainLevel((ClientLevel) level) : level.getRainLevel(partialTick);
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getThunderLevel(F)F"),
            require = 0)
    private float planariumview$thunderLevel(Level level, float partialTick) {
        return planariumview$outside(level) ? SkySync.thunderLevel((ClientLevel) level) : level.getThunderLevel(partialTick);
    }
}

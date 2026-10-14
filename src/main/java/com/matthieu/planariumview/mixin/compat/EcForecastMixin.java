package com.matthieu.planariumview.mixin.compat;

import com.matthieu.planariumview.LunarClient;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * Enhanced Celestials 2 : dans un bocal, le mod trouve le calendrier lunaire du monde d'origine (voir LunarClient),
 * pour la couleur, la taille et la texture de la lune et la teinte de la lumiere. Client seulement.
 */
@Pseudo
@Mixin(targets = "dev.corgitaco.enhancedcelestials2core.EnhancedCelestials", remap = false)
public abstract class EcForecastMixin {

    @Inject(method = "lunarForecastWorldData", at = @At("HEAD"), cancellable = true, require = 0)
    private static void planariumview$jarForecast(Level level, CallbackInfoReturnable<Optional<Object>> cir) {
        if (level == null || !level.isClientSide()) {
            return;
        }
        Optional<Object> forecast = LunarClient.forecastFor(level);
        if (forecast.isPresent()) {
            cir.setReturnValue(forecast);
        }
    }
}

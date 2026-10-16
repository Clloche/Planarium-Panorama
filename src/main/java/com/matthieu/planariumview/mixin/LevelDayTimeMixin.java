package com.matthieu.planariumview.mixin;

import com.matthieu.planariumview.client.sky.SkySync;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Cote client, dans le bocal : l'heure du monde d'origine. Certains ciels moddes (l'Aether) lisent l'heure
 * directement (soleil et lune qui apparaissent et disparaissent), pas seulement la position du soleil.
 */
@Mixin(Level.class)
public abstract class LevelDayTimeMixin {

    @Inject(method = "getDayTime", at = @At("HEAD"), cancellable = true)
    private void planariumview$dayTime(CallbackInfoReturnable<Long> cir) {
        if ((Object) this instanceof ClientLevel level && SkySync.skyActive(level)) {
            cir.setReturnValue(SkySync.dayTime(level));
        }
    }

    /** Meme heure par l'autre methode (Enhanced Celestials lit celle-ci). */
    @Inject(method = "dayTime", at = @At("HEAD"), cancellable = true, require = 0)
    private void planariumview$dayTimeShort(CallbackInfoReturnable<Long> cir) {
        if ((Object) this instanceof ClientLevel level && SkySync.skyActive(level)) {
            cir.setReturnValue(SkySync.dayTime(level));
        }
    }
}

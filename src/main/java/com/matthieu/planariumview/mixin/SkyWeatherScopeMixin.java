package com.matthieu.planariumview.mixin;

import com.matthieu.planariumview.client.sky.SkySync;

import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Pendant le dessin du ciel, le bocal voit la pluie du dehors (voir LevelWeatherMixin) :
 * le soleil, la lune et les etoiles s'effacent quand il pleut, comme dehors.
 * Si un autre mod annule le dessin du ciel, la fin n'est pas appelee : SkySync.resetWeatherScope remet tout a zero
 * apres le ciel, sinon la vraie pluie tomberait partout dans le bocal.
 */
@Mixin(LevelRenderer.class)
public abstract class SkyWeatherScopeMixin {

    @Inject(method = "renderSky", at = @At("HEAD"))
    private void planariumview$skyStart(CallbackInfo ci) {
        SkySync.enterWeatherScope();
    }

    @Inject(method = "renderSky", at = @At("RETURN"))
    private void planariumview$skyEnd(CallbackInfo ci) {
        SkySync.exitWeatherScope();
    }
}

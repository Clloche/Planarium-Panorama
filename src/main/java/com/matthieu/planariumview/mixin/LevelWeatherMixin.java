package com.matthieu.planariumview.mixin;

import com.hollingsworth.arsnouveau.ArsNouveau;
import com.matthieu.planariumview.SkySync;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Les bocaux partagent la meteo du monde principal : quand il pleut dehors, Minecraft faisait
 * pleuvoir aussi dans la dimension du bocal, en vraie taille, autour de la camera. On voyait donc
 * de la pluie juste derriere les parois. Cote client, il ne pleut plus dans un bocal :
 * la meteo exterieure est dessinee par le decor geant.
 *
 * Exception : pendant le dessin du ciel, des nuages et du brouillard (SkyWeatherScopeMixin, FogWeatherScopeMixin),
 * on rend la pluie du dehors, pour que le ciel du bocal soit couvert comme dehors (pas d'etoiles ni de lune).
 */
@Mixin(Level.class)
public abstract class LevelWeatherMixin {

    private boolean planariumview$isJar() {
        Object self = this;
        return self instanceof ClientLevel level && level.dimensionTypeRegistration().is(ArsNouveau.DIMENSION_TYPE_KEY);
    }

    @Inject(method = "getRainLevel", at = @At("HEAD"), cancellable = true)
    private void planariumview$rain(float partialTick, CallbackInfoReturnable<Float> cir) {
        if (planariumview$isJar()) {
            ClientLevel level = (ClientLevel) (Object) this;
            cir.setReturnValue(SkySync.inWeatherScope(level) ? SkySync.rainLevel(level) : 0.0F);
        }
    }

    @Inject(method = "getThunderLevel", at = @At("HEAD"), cancellable = true)
    private void planariumview$thunder(float partialTick, CallbackInfoReturnable<Float> cir) {
        if (planariumview$isJar()) {
            ClientLevel level = (ClientLevel) (Object) this;
            cir.setReturnValue(SkySync.inWeatherScope(level) ? SkySync.thunderLevel(level) : 0.0F);
        }
    }
}

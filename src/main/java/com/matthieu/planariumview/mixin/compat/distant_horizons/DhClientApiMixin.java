package com.matthieu.planariumview.mixin.compat.distant_horizons;

import com.matthieu.planariumview.config.Config;

import com.hollingsworth.arsnouveau.ArsNouveau;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Distant Horizons dans un bocal : il dessinait le vide autour du bocal comme un terrain lointain (une grande bande
 * noire a l'horizon, par-dessus le decor geant). Dans un bocal, il ne dessine rien ; partout ailleurs, rien ne change.
 * Ses reglages ne sont jamais touches.
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.api.internal.ClientApi", remap = false)
public abstract class DhClientApiMixin {

    private static boolean planariumview$inJar() {
        ClientLevel level = Minecraft.getInstance().level;
        return level != null && Config.enabled() && level.dimensionTypeRegistration().is(ArsNouveau.DIMENSION_TYPE_KEY);
    }

    @Inject(method = {"renderLods", "renderDeferredLodsForShaders", "renderFadeOpaque", "renderFadeTransparent"},
            at = @At("HEAD"), cancellable = true, require = 0)
    private void planariumview$noLodsInJar(CallbackInfo ci) {
        if (planariumview$inJar()) {
            ci.cancel();
        }
    }
}

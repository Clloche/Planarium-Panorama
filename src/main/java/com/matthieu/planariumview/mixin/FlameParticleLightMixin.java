package com.matthieu.planariumview.mixin;

import com.matthieu.planariumview.client.scenery.SceneryParticles;

import net.minecraft.client.particle.FlameParticle;
import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Flammes du decor (torches, bougies, feux, ames) avec la couleur de Contraption Lights. FlameParticle eclaircit la
 * lumiere recue (ParticleLightMixin) en n'en gardant que les octets du bloc et du ciel : une partie des bits de couleur
 * disparaissait, et la flamme d'une torche des ames restait cyan au lieu de prendre la teinte bleue de sa lumiere,
 * comme dans le vrai monde. On remet la couleur entiere sur la lumiere eclaircie.
 */
@Mixin(FlameParticle.class)
public abstract class FlameParticleLightMixin {

    @Inject(method = "getLightColor", at = @At("RETURN"), cancellable = true, require = 0)
    private void planariumview$sceneryHue(float partialTick, CallbackInfoReturnable<Integer> cir) {
        int hue = SceneryParticles.hueOf((Particle) (Object) this);
        if (hue != 0) {
            cir.setReturnValue((cir.getReturnValueI() & 0x00F000F0) | hue);
        }
    }
}

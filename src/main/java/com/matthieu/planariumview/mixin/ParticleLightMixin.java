package com.matthieu.planariumview.mixin;

import com.matthieu.planariumview.SceneryParticles;
import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Particules du decor geant : elles flottent loin du bocal, la ou le monde du bocal est vide et noir. Elles prennent
 * donc la lumiere du bloc du decor d'ou elles viennent (les particules qui brillent, comme les flammes, gardent la leur).
 */
@Mixin(Particle.class)
public abstract class ParticleLightMixin {

    @Inject(method = "getLightColor", at = @At("HEAD"), cancellable = true, require = 0)
    private void planariumview$sceneryLight(float partialTick, CallbackInfoReturnable<Integer> cir) {
        Integer light = SceneryParticles.lightOf((Particle) (Object) this);
        if (light != null) {
            cir.setReturnValue(light);
        }
    }
}

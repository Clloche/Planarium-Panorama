package com.matthieu.planariumview.mixin;

import com.matthieu.planariumview.SceneryParticles;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Retient quelle particule bouge, pour que celles creees par une particule du decor le soient aussi (gouttes). */
@Mixin(ParticleEngine.class)
public abstract class ParticleEngineTickMixin {

    @Inject(method = "tickParticle", at = @At("HEAD"), require = 0)
    private void planariumview$beginTick(Particle particle, CallbackInfo ci) {
        SceneryParticles.beginTick(particle);
    }

    @Inject(method = "tickParticle", at = @At("RETURN"), require = 0)
    private void planariumview$endTick(Particle particle, CallbackInfo ci) {
        SceneryParticles.endTick();
    }
}

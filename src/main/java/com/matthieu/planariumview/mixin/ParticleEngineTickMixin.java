package com.matthieu.planariumview.mixin;

import com.matthieu.planariumview.client.scenery.SceneryParticles;

import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Retient quelle particule bouge, pour que celles creees par une particule du decor le soient aussi (gouttes), et voit
 * chaque particule ajoutee (celles que certains mods ajoutent directement au moteur).
 */
@Mixin(ParticleEngine.class)
public abstract class ParticleEngineTickMixin {

    @Inject(method = "tickParticle", at = @At("HEAD"), require = 0)
    private void planariumview$beginTick(Particle particle, CallbackInfo ci) {
        SceneryParticles.beginTick(particle);
    }

    @Inject(method = "tickParticle", at = @At("RETURN"), require = 0)
    private void planariumview$endTick(Particle particle, CallbackInfo ci) {
        SceneryParticles.endTick(particle);
    }

    @org.spongepowered.asm.mixin.Unique
    private static float planariumview$fogStart;
    @org.spongepowered.asm.mixin.Unique
    private static float planariumview$fogEnd;
    @org.spongepowered.asm.mixin.Unique
    private static com.mojang.blaze3d.shaders.FogShape planariumview$fogShape;
    /** Appels imbriques (une version de render peut en appeler une autre). */
    @org.spongepowered.asm.mixin.Unique
    private static int planariumview$fogDepth;

    /** Brouillard du decor geant pour les particules (voir ClientView.particleFog). */
    @Inject(method = "render", at = @At("HEAD"), require = 0)
    private void planariumview$sceneryFog(CallbackInfo ci) {
        if (planariumview$fogDepth++ > 0) {
            return;
        }
        float start = com.mojang.blaze3d.systems.RenderSystem.getShaderFogStart();
        float end = com.mojang.blaze3d.systems.RenderSystem.getShaderFogEnd();
        com.mojang.blaze3d.shaders.FogShape shape = com.mojang.blaze3d.systems.RenderSystem.getShaderFogShape();
        if (com.matthieu.planariumview.client.scenery.ClientView.particleFog()) {
            planariumview$fogStart = start;
            planariumview$fogEnd = end;
            planariumview$fogShape = shape;
        } else {
            planariumview$fogShape = null;
        }
    }

    @Inject(method = "render", at = @At("RETURN"), require = 0)
    private void planariumview$restoreFog(CallbackInfo ci) {
        if (--planariumview$fogDepth > 0) {
            return;
        }
        planariumview$fogDepth = 0;
        if (planariumview$fogShape != null) {
            com.mojang.blaze3d.systems.RenderSystem.setShaderFogStart(planariumview$fogStart);
            com.mojang.blaze3d.systems.RenderSystem.setShaderFogEnd(planariumview$fogEnd);
            com.mojang.blaze3d.systems.RenderSystem.setShaderFogShape(planariumview$fogShape);
            planariumview$fogShape = null;
        }
    }

    @Inject(method = "add(Lnet/minecraft/client/particle/Particle;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void planariumview$add(Particle particle, CallbackInfo ci) {
        if (!SceneryParticles.onEngineAdd(particle)) {
            ci.cancel();
        }
    }
}

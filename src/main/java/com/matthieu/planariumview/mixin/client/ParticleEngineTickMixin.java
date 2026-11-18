package com.matthieu.planariumview.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.matthieu.planariumview.client.scenery.ClientView;
import com.matthieu.planariumview.client.scenery.FramedParticle;
import com.matthieu.planariumview.client.scenery.SceneryParticles;

import net.minecraft.client.Camera;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.culling.Frustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Predicate;

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

    // La surcharge vanilla delegue aussi a ce point d'entree NeoForge.
    @WrapMethod(method = "render(Lnet/minecraft/client/renderer/LightTexture;Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/culling/Frustum;Ljava/util/function/Predicate;)V")
    private void planariumview$sceneryFog(LightTexture light, Camera camera, float partialTick, Frustum frustum,
                                          Predicate<ParticleRenderType> filter, Operation<Void> original) {
        FramedParticle.renderPass(camera, () -> ClientView.renderParticles(
                () -> original.call(light, camera, partialTick, frustum, filter)));
    }

    @Inject(method = "add(Lnet/minecraft/client/particle/Particle;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void planariumview$add(Particle particle, CallbackInfo ci) {
        if (!SceneryParticles.onEngineAdd(particle)) {
            ci.cancel();
        }
    }
}

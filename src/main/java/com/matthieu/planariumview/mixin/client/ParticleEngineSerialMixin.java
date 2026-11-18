package com.matthieu.planariumview.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.matthieu.planariumview.integration.particle_core.ParticleCoreCompat;
import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Map;
import java.util.function.BiConsumer;

// Particle Core wraps this traversal at priority 100000.
@Mixin(value = ParticleEngine.class, priority = 100100)
abstract class ParticleEngineSerialMixin {
    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
            target = "Ljava/util/Map;forEach(Ljava/util/function/BiConsumer;)V"), require = 0)
    private <K, V> void planariumview$serialParticleTick(Map<K, V> particles, BiConsumer<? super K, ? super V> tick,
                                                         Operation<Void> original) {
        if (ParticleCoreCompat.requiresSerialTick()) {
            particles.forEach(tick);
        } else {
            original.call(particles, tick);
        }
    }
}

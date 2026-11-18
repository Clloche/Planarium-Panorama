package com.matthieu.planariumview.mixin.integration.visuality;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.matthieu.planariumview.integration.visuality.VisualityCompat;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "plus.dragons.visuality.event.ParticleEventHandler", remap = false)
abstract class VisualityChargeMixin {
    @WrapOperation(method = "spawnChargeParticles", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V"), require = 0)
    private static void planariumview$routeCharge(Level level, ParticleOptions options, double x, double y, double z,
                                                double vx, double vy, double vz, Operation<Void> original) {
        if (!VisualityCompat.captureMobParticle(level, options, x, y, z, vx, vy, vz)) {
            original.call(level, options, x, y, z, vx, vy, vz);
        }
    }
}

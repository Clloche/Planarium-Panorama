package com.matthieu.planariumview.mixin.integration.visuality;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.matthieu.planariumview.client.scenery.LitRenderingWorld;
import com.matthieu.planariumview.integration.visuality.VisualityCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "plus.dragons.visuality.config.BlockAmbientParticleConfig", remap = false)
abstract class VisualityBlockAmbientMixin {
    @WrapOperation(method = "spawnParticles", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;isAreaLoaded(Lnet/minecraft/core/BlockPos;I)Z"), require = 0)
    private boolean planariumview$useCapturedBlocks(Level level, BlockPos pos, int radius, Operation<Boolean> original) {
        // Visuality's Block.animateTick hook may also run for a captured block. Only admit the explicit sample.
        return level instanceof LitRenderingWorld world ? VisualityCompat.sampling(world) : original.call(level, pos, radius);
    }
}

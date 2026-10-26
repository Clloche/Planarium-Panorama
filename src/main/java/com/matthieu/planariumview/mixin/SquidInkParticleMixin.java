package com.matthieu.planariumview.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.matthieu.planariumview.client.scenery.SceneryParticles;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.SquidInkParticle;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

@Mixin(SquidInkParticle.class)
public abstract class SquidInkParticleMixin {
    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/client/multiplayer/ClientLevel;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState planariumview$inkWater(ClientLevel level, BlockPos pos, Operation<BlockState> original) {
        BlockState captured = SceneryParticles.blockAt((Particle) (Object) this);
        return captured != null ? captured : original.call(level, pos);
    }

    // javac promotes this float literal to double when subtracting it from Particle.yd.
    @ModifyConstant(method = "tick", constant = @Constant(doubleValue = (double) 0.0074F))
    private double planariumview$inkGravity(double gravity) {
        return gravity * SceneryParticles.motionScale((Particle) (Object) this);
    }
}

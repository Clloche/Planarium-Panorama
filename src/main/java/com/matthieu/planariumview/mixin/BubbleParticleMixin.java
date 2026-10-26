package com.matthieu.planariumview.mixin;

import com.matthieu.planariumview.client.scenery.SceneryParticles;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.BubbleParticle;
import net.minecraft.client.particle.BubbleColumnUpParticle;
import net.minecraft.client.particle.WaterCurrentDownParticle;
import net.minecraft.client.particle.Particle;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** Une bulle du decor geant regarde l'eau du decor (sinon, hors de l'eau du vrai monde, elle disparait tout de suite). */
@Mixin({BubbleParticle.class, BubbleColumnUpParticle.class, WaterCurrentDownParticle.class})
public abstract class BubbleParticleMixin {

    @Redirect(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;getFluidState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/material/FluidState;"),
            require = 0)
    private FluidState planariumview$sceneryWater(ClientLevel level, BlockPos pos) {
        FluidState scenery = SceneryParticles.fluidAt((Particle) (Object) this);
        return scenery != null ? scenery : level.getFluidState(pos);
    }

    // Each constant occurs in only one of the three targets. Other particles keep their native acceleration.
    @ModifyConstant(method = "tick", constant = @Constant(doubleValue = 0.002), require = 0)
    private double planariumview$bubbleRise(double acceleration) {
        return acceleration * SceneryParticles.motionScale((Particle) (Object) this);
    }

    @ModifyConstant(method = "tick", constant = @Constant(floatValue = 0.6F), require = 0)
    private float planariumview$whirlpool(float acceleration) {
        return acceleration * SceneryParticles.motionScale((Particle) (Object) this);
    }
}

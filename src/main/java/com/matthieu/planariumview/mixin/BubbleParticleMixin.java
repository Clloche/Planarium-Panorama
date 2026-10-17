package com.matthieu.planariumview.mixin;

import com.matthieu.planariumview.client.scenery.SceneryParticles;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.BubbleParticle;
import net.minecraft.client.particle.Particle;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Une bulle du decor geant regarde l'eau du decor (sinon, hors de l'eau du vrai monde, elle disparait tout de suite). */
@Mixin(BubbleParticle.class)
public abstract class BubbleParticleMixin {

    @Redirect(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;getFluidState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/material/FluidState;"),
            require = 0)
    private FluidState planariumview$sceneryWater(ClientLevel level, BlockPos pos) {
        FluidState scenery = SceneryParticles.fluidAt((Particle) (Object) this);
        return scenery != null ? scenery : level.getFluidState(pos);
    }
}

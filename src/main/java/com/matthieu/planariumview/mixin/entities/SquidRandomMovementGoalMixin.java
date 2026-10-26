package com.matthieu.planariumview.mixin.entities;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.matthieu.planariumview.server.entities.OutsideMobsServer;
import net.minecraft.world.entity.animal.Squid;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Squids have their own idle cutoff, separate from RandomStrollGoal. Keep their swimming while observed. */
@Mixin(targets = "net.minecraft.world.entity.animal.Squid$SquidRandomMovementGoal")
public abstract class SquidRandomMovementGoalMixin {
    @Shadow @Final private Squid squid;

    @ModifyExpressionValue(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/animal/Squid;getNoActionTime()I"))
    private int planariumview$swimWhileObserved(int original) {
        return original > 100 && OutsideMobsServer.observed(squid) ? 0 : original;
    }
}

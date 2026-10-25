package com.matthieu.planariumview.mixin.entities;

import com.matthieu.planariumview.server.entities.OutsideMobsServer;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Un mob regarde depuis un bocal (voir OutsideMobsServer.observed) continue de se promener sans joueur a cote :
 * RandomStrollGoal ne refuse plus apres 100 ticks d'inactivite pour lui. Le compteur n'est pas touche, le despawn
 * aleatoire reste celui de Minecraft.
 */
@Mixin(RandomStrollGoal.class)
public abstract class RandomStrollGoalMixin {
    @Shadow
    @Final
    protected PathfinderMob mob;

    @ModifyExpressionValue(method = "canUse", at = {
            @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/PathfinderMob;getNoActionTime()I"),
            @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getNoActionTime()I")},
            require = 1)
    private int planariumview$strollWhileObserved(int original) {
        return original >= 100 && OutsideMobsServer.observed(mob) ? 0 : original;
    }
}

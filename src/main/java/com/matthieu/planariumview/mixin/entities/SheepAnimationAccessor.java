package com.matthieu.planariumview.mixin.entities;

import net.minecraft.world.entity.animal.Sheep;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Compteurs d'animation de Sheep, decrementes par aiStep (voir MobEventAnimations). */
@Mixin(Sheep.class)
public interface SheepAnimationAccessor {
    @Accessor("eatAnimationTick")
    int planariumview$getEatAnimationTick();

    @Accessor("eatAnimationTick")
    void planariumview$setEatAnimationTick(int value);
}

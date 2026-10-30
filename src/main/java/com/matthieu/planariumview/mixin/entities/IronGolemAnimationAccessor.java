package com.matthieu.planariumview.mixin.entities;

import net.minecraft.world.entity.animal.IronGolem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Compteurs d'animation de IronGolem, decrementes par aiStep (voir MobEventAnimations). */
@Mixin(IronGolem.class)
public interface IronGolemAnimationAccessor {
    @Accessor("attackAnimationTick")
    int planariumview$getAttackAnimationTick();

    @Accessor("attackAnimationTick")
    void planariumview$setAttackAnimationTick(int value);

    @Accessor("offerFlowerTick")
    int planariumview$getOfferFlowerTick();

    @Accessor("offerFlowerTick")
    void planariumview$setOfferFlowerTick(int value);
}

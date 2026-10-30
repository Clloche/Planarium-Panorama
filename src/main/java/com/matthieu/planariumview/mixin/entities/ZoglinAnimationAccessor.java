package com.matthieu.planariumview.mixin.entities;

import net.minecraft.world.entity.monster.Zoglin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Compteurs d'animation de Zoglin, decrementes par aiStep (voir MobEventAnimations). */
@Mixin(Zoglin.class)
public interface ZoglinAnimationAccessor {
    @Accessor("attackAnimationRemainingTicks")
    int planariumview$getAttackAnimationRemainingTicks();

    @Accessor("attackAnimationRemainingTicks")
    void planariumview$setAttackAnimationRemainingTicks(int value);
}

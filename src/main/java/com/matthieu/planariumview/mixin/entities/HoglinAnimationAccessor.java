package com.matthieu.planariumview.mixin.entities;

import net.minecraft.world.entity.monster.hoglin.Hoglin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Compteurs d'animation de Hoglin, decrementes par aiStep (voir MobEventAnimations). */
@Mixin(Hoglin.class)
public interface HoglinAnimationAccessor {
    @Accessor("attackAnimationRemainingTicks")
    int planariumview$getAttackAnimationRemainingTicks();

    @Accessor("attackAnimationRemainingTicks")
    void planariumview$setAttackAnimationRemainingTicks(int value);
}

package com.matthieu.planariumview.mixin.entities;

import net.minecraft.world.entity.monster.Ravager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Compteurs d'animation de Ravager, decrementes par aiStep (voir MobEventAnimations). */
@Mixin(Ravager.class)
public interface RavagerAnimationAccessor {
    @Accessor("attackTick")
    int planariumview$getAttackTick();

    @Accessor("attackTick")
    void planariumview$setAttackTick(int value);

    @Accessor("stunnedTick")
    int planariumview$getStunnedTick();

    @Accessor("stunnedTick")
    void planariumview$setStunnedTick(int value);
}

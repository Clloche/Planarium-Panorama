package com.matthieu.planariumview.mixin.entities;

import net.minecraft.world.entity.monster.Zoglin;
import net.minecraft.world.entity.monster.hoglin.Hoglin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Compteur d'attaque du hoglin et du zoglin (meme champ), decremente par aiStep (voir MobEventAnimations). */
@Mixin({Hoglin.class, Zoglin.class})
public interface HoglinAnimationAccessor {
    @Accessor("attackAnimationRemainingTicks")
    int planariumview$getAttackAnimationRemainingTicks();

    @Accessor("attackAnimationRemainingTicks")
    void planariumview$setAttackAnimationRemainingTicks(int value);
}

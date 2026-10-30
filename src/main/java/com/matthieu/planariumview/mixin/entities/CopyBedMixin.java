package com.matthieu.planariumview.mixin.entities;

import com.matthieu.planariumview.client.scenery.entities.GiantEntities;

import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Un mob qui dort, dans le decor geant ou une miniature : son lit est cherche dans le faux monde ou il est dessine, pas
 * dans le monde du client a sa position de sommeil du monde d'origine (voir GiantEntities.bedOrientation).
 */
@Mixin(LivingEntity.class)
public abstract class CopyBedMixin {
    @Inject(method = "getBedOrientation", at = @At("HEAD"), cancellable = true)
    private void planariumview$copyBed(CallbackInfoReturnable<Direction> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (GiantEntities.renderingCopy(self)) cir.setReturnValue(GiantEntities.bedOrientation(self));
    }
}

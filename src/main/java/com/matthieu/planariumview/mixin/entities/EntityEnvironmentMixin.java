package com.matthieu.planariumview.mixin.entities;

import com.matthieu.planariumview.client.scenery.entities.OutsideAquatic;
import com.matthieu.planariumview.network.entities.MobEnvironment;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Copies use their source environment; their local coordinates must never query the pocket's water or ground. */
@Mixin(Entity.class)
public abstract class EntityEnvironmentMixin implements OutsideAquatic.EnvironmentCopy {
    @Shadow protected boolean wasTouchingWater;
    @Shadow protected boolean wasEyeInWater;
    @Shadow private boolean onGround;
    @Unique private MobEnvironment planariumview$environment;

    @Override
    public void planariumview$environment(MobEnvironment state) {
        planariumview$environment = state;
        wasTouchingWater = state.inWater();
        wasEyeInWater = state.underwater();
        onGround = state.onGround(); // setOnGround would query supporting blocks in the real client world.
    }

    @Inject(method = "isInWaterOrBubble", at = @At("HEAD"), cancellable = true)
    private void planariumview$waterOrBubble(CallbackInfoReturnable<Boolean> cir) {
        if (planariumview$environment != null) cir.setReturnValue(planariumview$environment.waterOrBubble());
    }
}

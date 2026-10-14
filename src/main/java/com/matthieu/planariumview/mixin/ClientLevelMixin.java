package com.matthieu.planariumview.mixin;

import com.matthieu.planariumview.SkySync;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {

    @Inject(method = "effects", at = @At("HEAD"), cancellable = true)
    private void planariumview$effects(CallbackInfoReturnable<DimensionSpecialEffects> cir) {
        if (SkySync.isActive((ClientLevel) (Object) this)) {
            cir.setReturnValue(SkySync.jarEffects());
        }
    }

    @Inject(method = "getSkyColor", at = @At("HEAD"), cancellable = true)
    private void planariumview$skyColor(Vec3 pos, float partialTick, CallbackInfoReturnable<Vec3> cir) {
        ClientLevel self = (ClientLevel) (Object) this;
        if (SkySync.skyActive(self)) {
            cir.setReturnValue(SkySync.skyColor(self));
        }
    }

    /**
     * Assombrissement du ciel (lightmap) : le bocal dit au jeu qu'il n'y pleut jamais, donc l'interieur restait
     * aussi clair sous l'orage. Meme calcul que vanilla, avec la pluie et l'orage du dehors.
     */
    @Inject(method = "getSkyDarken", at = @At("HEAD"), cancellable = true)
    private void planariumview$skyDarken(float partialTick, CallbackInfoReturnable<Float> cir) {
        ClientLevel self = (ClientLevel) (Object) this;
        if (!SkySync.skyActive(self)) {
            return;
        }
        float time = self.getTimeOfDay(partialTick);
        float f = 1.0F - (net.minecraft.util.Mth.cos(time * ((float) Math.PI * 2F)) * 2.0F + 0.2F);
        f = net.minecraft.util.Mth.clamp(f, 0.0F, 1.0F);
        f = 1.0F - f;
        f *= 1.0F - SkySync.rainLevel(self) * 5.0F / 16.0F;
        f *= 1.0F - SkySync.thunderLevel(self) * 5.0F / 16.0F;
        cir.setReturnValue(f * 0.8F + 0.2F);
    }
}

package com.matthieu.planariumview.mixin;

import com.matthieu.planariumview.SkySync;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.dimension.DimensionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * La dimension du bocal a une heure fixe (minuit). Cote rendu client seulement,
 * on la remplace par l'heure du monde d'origine.
 */
@Mixin(DimensionType.class)
public abstract class DimensionTypeMixin {

    private static ClientLevel planariumview$level(Object self) {
        if (!RenderSystem.isOnRenderThread()) {
            return null;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || level.dimensionType() != self || !SkySync.skyActive(level)) {
            return null;
        }
        return level;
    }

    @Inject(method = "timeOfDay", at = @At("HEAD"), cancellable = true)
    private void planariumview$timeOfDay(long dayTime, CallbackInfoReturnable<Float> cir) {
        ClientLevel level = planariumview$level(this);
        if (level != null) {
            cir.setReturnValue(SkySync.timeOfDay(level));
        }
    }

    @Inject(method = "moonPhase", at = @At("HEAD"), cancellable = true)
    private void planariumview$moonPhase(long dayTime, CallbackInfoReturnable<Integer> cir) {
        ClientLevel level = planariumview$level(this);
        if (level != null) {
            cir.setReturnValue(SkySync.moonPhase(level));
        }
    }

    /**
     * Citadel (la bibliotheque d'Alex's Caves) remplace le calcul de l'heure du ciel par le sien, qui lit l'heure fixe
     * de la dimension avant tout : celle d'un bocal d'Ars est minuit, d'ou un ciel immobile et la lune juste au-dessus.
     * Pendant le dessin du ciel du bocal seulement, l'heure "fixe" devient l'heure synchronisee du dehors, qui avance
     * normalement, convertie en jours de 24000 ticks comme Citadel les compte (l'Aether aux jours plus longs reste juste).
     * Ailleurs, rien ne change : d'autres mods s'en servent pour savoir s'il fait jour.
     */
    @Inject(method = "fixedTime", at = @At("HEAD"), cancellable = true, require = 0)
    private void planariumview$fixedTime(CallbackInfoReturnable<java.util.OptionalLong> cir) {
        ClientLevel level = planariumview$level(this);
        if (level != null && SkySync.inWeatherScope(level)) {
            cir.setReturnValue(java.util.OptionalLong.of(SkySync.vanillaTicks(level)));
        }
    }
}

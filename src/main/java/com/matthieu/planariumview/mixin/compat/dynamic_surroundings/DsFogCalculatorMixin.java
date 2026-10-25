package com.matthieu.planariumview.mixin.compat.dynamic_surroundings;

import com.matthieu.planariumview.client.scenery.OutsideFluidView;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Dynamic Surroundings n'ajuste pas le brouillard quand la camera est dans un liquide. Dans un bocal immerge, la
 * camera est au sec mais le brouillard est celui de l'eau (debut negatif) : ses calculs le jugeaient invalide et
 * ecrivaient un avertissement par calcul et par image (des milliers de lignes, donc des saccades). On garde le
 * brouillard tel quel, comme DS le fait sous l'eau.
 * FogRenderer.FogData n'est pas public : on le recoit comme Object, et on lit le brouillard dans RenderSystem,
 * d'ou DS tire lui-meme ces valeurs (FogCompat.currentShaderFog).
 */
@Pseudo
@Mixin(targets = "org.orecruncher.dsurround.processing.fog.HolisticFogRangeCalculator", remap = false)
public abstract class DsFogCalculatorMixin {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true, require = 0)
    private void planariumview$keepLiquidFog(@Coerce Object data, float renderDistance, float partialTick,
                                             CallbackInfoReturnable<Object> cir) {
        if (RenderSystem.getShaderFogStart() < 0.0F || RenderSystem.getShaderFogEnd() < 0.0F
                || OutsideFluidView.jarFluid() != FogType.NONE) {
            cir.setReturnValue(data);
        }
    }
}

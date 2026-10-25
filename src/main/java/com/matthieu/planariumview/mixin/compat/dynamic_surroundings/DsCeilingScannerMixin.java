package com.matthieu.planariumview.mixin.compat.dynamic_surroundings;

import com.matthieu.planariumview.client.sound.OutsideAmbience;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Dynamic Surroundings, dans un bocal dont le Planarium est a l'air libre : le plafond du bocal (ars_nouveau:dim_boundary)
 * ne compte pas comme un toit. Les vrais toits construits dans le bocal comptent : sous un toit, Dynamic Surroundings
 * passe a l'ambiance "a l'interieur" au lieu des sons du biome, comme dehors. Planarium a l'abri : le plafond compte
 * (on est toujours "a l'interieur").
 */
@Mixin(targets = "org.orecruncher.dsurround.processing.scanner.CeilingScanner", remap = false)
public abstract class DsCeilingScannerMixin {

    @Inject(method = "actsAsCeiling", at = @At("HEAD"), cancellable = true, require = 0)
    private void planariumview$jarCeiling(Level world, BlockPos pos, BlockState state, CallbackInfoReturnable<Boolean> cir) {
        if (OutsideAmbience.active() && !OutsideAmbience.sheltered()
                && "ars_nouveau:dim_boundary".equals(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString())) {
            cir.setReturnValue(false);
        }
    }
}

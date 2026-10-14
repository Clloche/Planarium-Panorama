package com.matthieu.planariumview.mixin.compat;

import com.hollingsworth.arsnouveau.common.block.DimBoundary;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Alex's Caves : l'ambersol pose des blocs de lumiere invisibles sous lui, tant que la lumiere du ciel descend.
 * Les parois du bocal laissent descendre la lumiere du ciel (voir DimBoundaryMixin), donc ces lumieres traversaient
 * le plancher et restaient sous le bocal quand on cassait l'ambersol. Elles s'arretent maintenant aux parois.
 */
@Pseudo
@Mixin(targets = "com.github.alexmodguy.alexscaves.server.block.AmbersolLightBlock", remap = false)
public abstract class AcAmbersolLightMixin {

    @Inject(method = "testSkylight", at = @At("HEAD"), cancellable = true, require = 0)
    private static void planariumview$stopAtJarWalls(LevelReader level, BlockState state, BlockPos pos,
                                                     CallbackInfoReturnable<Boolean> cir) {
        if (state.getBlock() instanceof DimBoundary) {
            cir.setReturnValue(false);
        }
    }
}

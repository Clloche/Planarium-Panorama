package com.matthieu.planariumview.mixin.compat.dynamic_surroundings;

import com.matthieu.planariumview.client.scenery.LitRenderingWorld;
import com.matthieu.planariumview.diagnostics.Alternative;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * DS 0.4.9 : les rayons de reverberation lisent les blocs par une vue qui garde le chunk de sa derniere lecture
 * ({@code level.getChunk(x, z)}). Le decor (voir DsSoundFxMixin) n'a pas de chunks : son {@code getChunk} renvoie
 * null. Attachee au decor, la vue lit donc ses blocs directement.
 */
@Pseudo
@Mixin(targets = "org.orecruncher.dsurround.lib.block.ChunkCachingView", remap = false)
public abstract class DsChunkViewMixin {
    @Shadow
    private Level level;

    @Alternative("ds_fx_blocks")
    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true, require = 0)
    private void planariumview$sceneryBlock(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        if (this.level instanceof LitRenderingWorld scenery) cir.setReturnValue(scenery.getBlockState(pos));
    }

    @Alternative("ds_fx_blocks")
    @Inject(method = "getFluidState", at = @At("HEAD"), cancellable = true, require = 0)
    private void planariumview$sceneryFluid(BlockPos pos, CallbackInfoReturnable<FluidState> cir) {
        if (this.level instanceof LitRenderingWorld scenery) cir.setReturnValue(scenery.getFluidState(pos));
    }
}

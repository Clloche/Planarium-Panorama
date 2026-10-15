package com.matthieu.planariumview.mixin;

import com.matthieu.planariumview.ServerView;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Marks active Planarium scenery dirty when a captured source block changes. */
@Mixin(LevelChunk.class)
public abstract class LevelChunkSceneryMixin {
    @Shadow @Final private Level level;

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void planariumview$markSceneryDirty(BlockPos pos, BlockState state, boolean moved,
                                                 CallbackInfoReturnable<BlockState> cir) {
        BlockState previous = cir.getReturnValue();
        if (previous != null && !previous.equals(state)) {
            ServerView.markSceneryDirty(level, pos);
        }
    }
}

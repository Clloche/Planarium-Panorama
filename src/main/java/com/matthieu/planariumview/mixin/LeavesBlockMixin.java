package com.matthieu.planariumview.mixin;

import com.matthieu.planariumview.client.scenery.LitRenderingWorld;
import com.matthieu.planariumview.compat.FallingLeavesCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.ParticleUtils;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Feuillages du decor et des miniatures avec Falling Leaves : Falling Leaves suppose que le monde du bloc est le vrai
 * monde du client et plante dans le faux monde du decor. On passe avant lui (priorite plus basse) : ses feuilles sont
 * faites par FallingLeavesCompat, et on garde ce que fait Minecraft (les gouttes sous les feuilles quand il pleut).
 */
@Mixin(value = LeavesBlock.class, priority = 500)
public abstract class LeavesBlockMixin {

    @Inject(method = "animateTick", at = @At("HEAD"), cancellable = true, require = 0)
    private void planariumview$sceneryLeaves(BlockState state, Level level, BlockPos pos, RandomSource random, CallbackInfo ci) {
        if (!(level instanceof LitRenderingWorld) || !FallingLeavesCompat.present()) {
            return;
        }
        // Comme LeavesBlock.animateTick.
        if (level.isRainingAt(pos.above()) && random.nextInt(15) == 1) {
            BlockPos below = pos.below();
            BlockState under = level.getBlockState(below);
            if (!under.canOcclude() || !under.isFaceSturdy(level, below, Direction.UP)) {
                ParticleUtils.spawnParticleBelow(level, pos, random, ParticleTypes.DRIPPING_WATER);
            }
        }
        ci.cancel();
    }
}

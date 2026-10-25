package com.matthieu.planariumview.mixin.compat.puddle_flood;

import com.matthieu.planariumview.compat.puddle_flood.PuddlesCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Le tick du decor tire ses blocs autour du Planarium, avec les vraies coordonnees du monde d'origine. */
@Pseudo
@Mixin(targets = "pigcart.puddleflood.ClientBlockPlacementUtil", remap = false)
public abstract class PfPlacementMixin {
    @Inject(method = "getRandomHeightmapPos", at = @At("HEAD"), cancellable = true, require = 0)
    private static void planariumview$surface(Level level, BlockPos.MutableBlockPos destination,
                                             CallbackInfoReturnable<BlockState> cir) {
        if (level instanceof PuddlesCompat.SceneryLevel scenery) {
            cir.setReturnValue(scenery.randomSurface(destination));
        }
    }

    @Inject(method = "getRandomPosition", at = @At("HEAD"), cancellable = true, require = 0)
    private static void planariumview$position(Level level, BlockPos.MutableBlockPos destination, int range,
                                              CallbackInfoReturnable<BlockState> cir) {
        if (level instanceof PuddlesCompat.SceneryLevel scenery) {
            cir.setReturnValue(scenery.randomPosition(destination, range));
        }
    }
}

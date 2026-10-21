package com.matthieu.planariumview.mixin;

import com.matthieu.planariumview.client.scenery.LitRenderingWorld;

import com.hollingsworth.arsnouveau.client.renderer.PlanariumRenderingWorld;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Conserve les etats captures dans les faux mondes et complete les parois de la miniature avec le verre du bocal.
 */
@Mixin(value = PlanariumRenderingWorld.class, remap = false)
public abstract class PlanariumRenderingWorldMixin {
    private static BlockState planariumview$glass;

    /**
     * Les etats recus decrivent deja les blocs et leurs voisins. Les recalculer ici peut supprimer les cultures :
     * Ars lit leur lumiere dans le monde du joueur aux coordonnees locales du gabarit, avant notre table de lumiere.
     * Les bords de la capture peuvent aussi manquer du support d'une plante ou d'un bloc accroche.
     */
    @WrapOperation(method = "<init>(Lnet/minecraft/world/level/Level;Ljava/util/List;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/Block;updateFromNeighbourShapes(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"),
            require = 0)
    private static BlockState planariumview$capturedState(BlockState state, LevelAccessor world, BlockPos pos,
                                                        Operation<BlockState> original) {
        return com.matthieu.planariumview.config.Config.enabled() ? state : original.call(state, world, pos);
    }

    @Inject(method = "getBlockState", at = @At("RETURN"), cancellable = true, require = 0)
    private void planariumview$jarGlass(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        if (!com.matthieu.planariumview.config.Config.enabled() || !cir.getReturnValue().isAir() || LitRenderingWorld.isGiant(this)) {
            return;
        }
        // Positions du gabarit : x et z de 0 a 31, y de 0 a 30 (le plancher du bocal est en y = -1, le plafond en y = 31).
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        if (x < -1 || x > 32 || z < -1 || z > 32 || y < -1 || y > 31) {
            return;
        }
        boolean shell = x == -1 || x == 32 || z == -1 || z == 32 || y == -1 || y == 31;
        if (!shell) {
            return;
        }
        if (planariumview$glass == null) {
            planariumview$glass = BuiltInRegistries.BLOCK
                    .get(ResourceLocation.fromNamespaceAndPath("ars_nouveau", "dim_boundary")).defaultBlockState();
        }
        cir.setReturnValue(planariumview$glass);
    }
}

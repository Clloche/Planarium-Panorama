package com.matthieu.planariumview.mixin;

import com.matthieu.planariumview.client.scenery.LitRenderingWorld;

import com.hollingsworth.arsnouveau.client.renderer.PlanariumRenderingWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Le faux monde de la miniature ne contient que l'interieur du bocal, pas son verre. Ars y fait verifier chaque bloc
 * (updateFromNeighbourShapes) : une torche accrochee au verre n'avait plus de support et devenait de l'air.
 * On lui rend le verre du bocal tout autour (seulement pour la miniature, pas pour le decor geant).
 */
@Mixin(value = PlanariumRenderingWorld.class, remap = false)
public abstract class PlanariumRenderingWorldMixin {
    private static BlockState planariumview$glass;

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

package com.matthieu.planariumview.mixin;

import com.hollingsworth.arsnouveau.common.block.DimBoundary;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Les parois du bocal (ars_nouveau:dim_boundary) sont en verre, mais pour Minecraft c'est un cube plein :
 * la lumiere du ciel perdait 1 niveau par bloc en descendant depuis le plafond, et le centre du bocal
 * finissait dans le noir. Comme le verre vanilla, elles laissent maintenant passer la lumiere du ciel
 * tout droit vers le bas, sans l'affaiblir.
 */
@Mixin(DimBoundary.class)
public abstract class DimBoundaryMixin extends Block {

    private DimBoundaryMixin(Properties properties) {
        super(properties);
    }

    /** Ajoute la lumiere emise par la paroi (voir JarWalls). */
    @Override
    protected void createBlockStateDefinition(net.minecraft.world.level.block.state.StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(com.matthieu.planariumview.JarWalls.LIGHT);
    }

    @Override
    public boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }
}

package com.matthieu.planariumview.compat.contraption_lights;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;

/**
 * Vue simple (pas un "Level") sur notre faux monde, donnee a Contraption Lights comme source.
 *
 * Quand la source est un vrai monde, Contraption Lights lit directement les chunks du monde du client
 * (ceux du bocal), et ne voit donc pas les blocs de la photo. Avec cette vue, il lit les blocs un par un,
 * ce qui passe par notre faux monde.
 *
 * Avec un decalage ({@code dx}, {@code dz}), Contraption Lights voit le faux monde loin du vrai (voir ColorCompat) :
 * ses caches de couleur, ranges par position seulement, ne melangent plus les deux.
 */
public class SourceView implements BlockAndTintGetter {
    private final BlockAndTintGetter world;
    private final int dx;
    private final int dz;

    public SourceView(BlockAndTintGetter world) {
        this(world, 0, 0);
    }

    public SourceView(BlockAndTintGetter world, int dx, int dz) {
        this.world = world;
        this.dx = dx;
        this.dz = dz;
    }

    /** Position dans le faux monde. */
    private BlockPos local(BlockPos pos) {
        return dx == 0 && dz == 0 ? pos : pos.offset(-dx, 0, -dz);
    }

    @Override
    public float getShade(Direction direction, boolean shade) {
        return world.getShade(direction, shade);
    }

    @Override
    public LevelLightEngine getLightEngine() {
        return world.getLightEngine();
    }

    @Override
    public int getBlockTint(BlockPos pos, ColorResolver resolver) {
        return world.getBlockTint(local(pos), resolver);
    }

    @Override
    public int getBrightness(LightLayer layer, BlockPos pos) {
        return world.getBrightness(layer, local(pos));
    }

    @Override
    public int getRawBrightness(BlockPos pos, int amount) {
        BlockPos at = local(pos);
        return Math.max(world.getBrightness(LightLayer.SKY, at) - amount, world.getBrightness(LightLayer.BLOCK, at));
    }

    @Nullable
    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return world.getBlockEntity(local(pos));
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return world.getBlockState(local(pos));
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return world.getBlockState(local(pos)).getFluidState();
    }

    @Override
    public int getHeight() {
        return world.getHeight();
    }

    @Override
    public int getMinBuildHeight() {
        return world.getMinBuildHeight();
    }
}

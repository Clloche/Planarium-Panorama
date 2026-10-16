package com.matthieu.planariumview.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.Level;
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
 */
public final class SourceView implements BlockAndTintGetter {
    private final Level world;

    public SourceView(Level world) {
        this.world = world;
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
        return world.getBlockTint(pos, resolver);
    }

    @Override
    public int getBrightness(LightLayer layer, BlockPos pos) {
        return world.getBrightness(layer, pos);
    }

    @Override
    public int getRawBrightness(BlockPos pos, int amount) {
        return Math.max(world.getBrightness(LightLayer.SKY, pos) - amount, world.getBrightness(LightLayer.BLOCK, pos));
    }

    @Nullable
    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return world.getBlockEntity(pos);
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return world.getBlockState(pos);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return world.getBlockState(pos).getFluidState();
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

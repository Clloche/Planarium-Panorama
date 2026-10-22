package com.matthieu.planariumview.network;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4dc;
import org.joml.Vector3dc;

import java.util.HashMap;
import java.util.Map;

/** Small physical fluid sample, independent of the scenery capture and its flight rebuilds. */
public final class OutsideFluids {
    private record Cell(FluidState state, float height) {}
    private final Map<Long, Cell> cells;

    private OutsideFluids(Map<Long, Cell> cells) { this.cells = Map.copyOf(cells); }

    public static ListTag capture(BlockGetter level, Matrix4dc anchor) {
        // The glass occupies local Y 1..2. Include the base and a margin for the camera near plane.
        AABB bounds = SubLevelTransforms.bounds(anchor, new AABB(0, 0, 0, 1, 2.25, 1).inflate(0.25));
        ListTag result = new ListTag();
        BlockPos min = BlockPos.containing(bounds.minX, bounds.minY, bounds.minZ);
        BlockPos max = BlockPos.containing(bounds.maxX, bounds.maxY, bounds.maxZ);
        long volume = ((long) max.getX() - min.getX() + 1) * ((long) max.getY() - min.getY() + 1)
                * ((long) max.getZ() - min.getZ() + 1);
        if (volume > 512) return result;
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            FluidState fluid = level.getFluidState(pos);
            if (fluid.isEmpty()) continue;
            CompoundTag cell = new CompoundTag();
            cell.putLong("pos", pos.asLong());
            cell.putInt("state", Block.getId(fluid.createLegacyBlock()));
            cell.putFloat("height", fluid.getHeight(level, pos));
            result.add(cell);
        }
        return result;
    }

    public static OutsideFluids read(ListTag tag) {
        if (tag.size() > 512) throw new IllegalArgumentException("Too many outside fluid cells");
        Map<Long, Cell> cells = new HashMap<>();
        for (int i = 0; i < tag.size(); i++) {
            CompoundTag cell = tag.getCompound(i);
            FluidState state = Block.stateById(cell.getInt("state")).getFluidState();
            float height = cell.getFloat("height");
            if (!state.isEmpty() && Float.isFinite(height) && height > 0 && height <= 1) {
                cells.put(cell.getLong("pos"), new Cell(state, height));
            }
        }
        return new OutsideFluids(cells);
    }

    public FluidState at(Vector3dc point) {
        BlockPos pos = BlockPos.containing(point.x(), point.y(), point.z());
        Cell cell = cells.get(pos.asLong());
        return cell != null && point.y() < pos.getY() + cell.height()
                ? cell.state() : Fluids.EMPTY.defaultFluidState();
    }
}

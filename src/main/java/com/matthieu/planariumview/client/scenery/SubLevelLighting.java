package com.matthieu.planariumview.client.scenery;

import com.matthieu.planariumview.compat.SourceView;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.material.FluidState;

import java.util.HashMap;
import java.util.Map;

/** Color emitters and occluders across all captured sections of the same storage plot. */
final class SubLevelLighting extends SourceView {
    static boolean neighbors(int[] origin, int[] other) {
        return origin.length == 3 && other.length == 3
                && Math.abs((long) origin[0] - other[0]) <= 16
                && Math.abs((long) origin[1] - other[1]) <= 16
                && Math.abs((long) origin[2] - other[2]) <= 16;
    }

    /** Lighting and border changes do not reset the native machines inside a section. */
    static boolean sameContents(CompoundTag before, CompoundTag after) {
        return before != null && java.util.Objects.equals(before.get("blocks"), after.get("blocks"))
                && java.util.Objects.equals(before.get("palette"), after.get("palette"))
                && java.util.Objects.equals(before.get("palettes"), after.get("palettes"));
    }

    static final class Section {
        final BlockPos origin;
        final Map<Long, BlockState> states = new HashMap<>();
        final byte[] light;

        Section(CompoundTag tag) {
            int[] at = tag.getIntArray("origin");
            if (at.length != 3) throw new IllegalArgumentException("Missing sub-level section origin");
            origin = new BlockPos(at[0], at[1], at[2]);
            light = tag.getByteArray("light");
            StructureTemplate template = new StructureTemplate();
            template.load(Minecraft.getInstance().level.holderLookup(net.minecraft.core.registries.Registries.BLOCK), tag);
            for (var state : ClientView.statesOf(template)) states.put(state.pos.asLong(), state.state);
        }
    }

    private final BlockPos origin;
    private final Map<Long, Section> sections;

    SubLevelLighting(BlockAndTintGetter own, BlockPos origin, Map<Long, Section> sections) {
        super(own);
        this.origin = origin;
        this.sections = sections;
    }

    private Section section(BlockPos pos) {
        return sections.get(SectionPos.asLong(Math.floorDiv(origin.getX() + pos.getX(), 16),
                Math.floorDiv(origin.getY() + pos.getY(), 16), Math.floorDiv(origin.getZ() + pos.getZ(), 16)));
    }

    @Override public BlockState getBlockState(BlockPos pos) {
        Section section = section(pos);
        return section == null ? super.getBlockState(pos) : section.states.getOrDefault(
                pos.offset(origin).subtract(section.origin).asLong(), Blocks.AIR.defaultBlockState());
    }

    @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }

    @Override public int getBrightness(LightLayer layer, BlockPos pos) {
        Section section = section(pos);
        if (section == null || section.light.length != 18 * 18 * 18) return super.getBrightness(layer, pos);
        BlockPos at = pos.offset(origin).subtract(section.origin);
        int value = Byte.toUnsignedInt(section.light[((at.getX() + 1) * 18 + at.getY() + 1) * 18 + at.getZ() + 1]);
        return layer == LightLayer.SKY ? value >> 4 : value & 15;
    }

    @Override public int getMinBuildHeight() { return -16; }
    @Override public int getHeight() { return 48; }
}

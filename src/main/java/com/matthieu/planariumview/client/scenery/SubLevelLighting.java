package com.matthieu.planariumview.client.scenery;

import com.matthieu.planariumview.compat.contraption_lights.SourceView;
import com.matthieu.planariumview.network.SubLevelTransforms;
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
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4d;
import org.joml.Matrix4dc;
import org.joml.Vector3d;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Color emitters and occluders in neighboring sections and physically nearby sublevels. */
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
        final Map<Long, BlockState> states;
        final byte[] light;

        Section(BlockPos origin, byte[] light) {
            this.origin = origin;
            this.states = new HashMap<>();
            this.light = light;
        }

        /** Same blocks as {@code previous}, new light table (the block table is shared, never modified after). */
        Section(Section previous, byte[] light) {
            this.origin = previous.origin;
            this.light = light;
            this.states = previous.states;
        }

        Section(CompoundTag tag) {
            int[] at = tag.getIntArray("origin");
            if (at.length != 3) throw new IllegalArgumentException("Missing sub-level section origin");
            origin = new BlockPos(at[0], at[1], at[2]);
            states = new HashMap<>();
            light = tag.getByteArray("light");
            StructureTemplate template = new StructureTemplate();
            template.load(Minecraft.getInstance().level.holderLookup(net.minecraft.core.registries.Registries.BLOCK), tag);
            for (var state : ClientView.statesOf(template)) states.put(state.pos.asLong(), state.state);
        }
    }

    record Frame(Section section, Matrix4d pose) {}

    /** Neighboring ships share physical light, even when their storage plots are thousands of blocks apart. */
    static final class Projected {
        final Map<Long, BlockState> states = new HashMap<>();
        final byte[] light;

        Projected(byte[] original) {
            light = original.length == 18 * 18 * 18 ? original.clone() : new byte[18 * 18 * 18];
        }
    }

    static boolean nearby(Matrix4dc own, Matrix4dc other) {
        if (own == null || other == null) return false;
        Matrix4d relative = new Matrix4d(own).invert().mul(other);
        return SubLevelTransforms.bounds(relative, new AABB(0, 0, 0, 16, 16, 16))
                .intersects(new AABB(-16, -16, -16, 32, 32, 32));
    }

    static Projected project(byte[] original, BlockPos origin, Matrix4dc pose, List<Frame> frames) {
        Projected projected = new Projected(original);
        if (pose == null) return projected;
        Vector3d point = new Vector3d();
        int[] own = {origin.getX(), origin.getY(), origin.getZ()};
        for (Frame frame : frames) {
            Section section = frame.section;
            int[] other = {section.origin.getX(), section.origin.getY(), section.origin.getZ()};
            // Adjacent sections of this plot already belong to the ordinary lighting view.
            if (neighbors(own, other) || !nearby(pose, frame.pose)) continue;
            Matrix4d toOwn = new Matrix4d(pose).invert().mul(frame.pose);
            for (var entry : section.states.entrySet()) {
                if (entry.getValue().isAir()) continue;
                BlockPos at = BlockPos.of(entry.getKey());
                toOwn.transformPosition(point.set(at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5));
                if (point.x < -16 || point.x >= 32 || point.y < -16 || point.y >= 32
                        || point.z < -16 || point.z >= 32) continue;
                projected.states.putIfAbsent(BlockPos.containing(point.x, point.y, point.z).asLong(), entry.getValue());
            }
            if (section.light.length != projected.light.length) continue;
            Matrix4d toOther = toOwn.invert();
            for (int x = -1; x <= 16; x++) for (int y = -1; y <= 16; y++) for (int z = -1; z <= 16; z++) {
                toOther.transformPosition(point.set(x + 0.5, y + 0.5, z + 0.5));
                int sx = (int) Math.floor(point.x), sy = (int) Math.floor(point.y), sz = (int) Math.floor(point.z);
                if (sx < -1 || sx > 16 || sy < -1 || sy > 16 || sz < -1 || sz > 16) continue;
                int index = ((x + 1) * 18 + y + 1) * 18 + z + 1;
                int incoming = section.light[((sx + 1) * 18 + sy + 1) * 18 + sz + 1] & 15;
                int previous = projected.light[index] & 255;
                // Preserve this structure's sky occlusion; only share block light from its physical neighbors.
                projected.light[index] = (byte) ((previous & 240) | Math.max(previous & 15, incoming));
            }
        }
        return projected;
    }

    private final BlockPos origin;
    private final Map<Long, Section> sections;
    private final Projected projected;

    SubLevelLighting(BlockAndTintGetter own, BlockPos origin, Map<Long, Section> sections, Projected projected) {
        super(own);
        this.origin = origin;
        this.sections = sections;
        this.projected = projected;
    }

    private Section section(BlockPos pos) {
        return sections.get(SectionPos.asLong(Math.floorDiv(origin.getX() + pos.getX(), 16),
                Math.floorDiv(origin.getY() + pos.getY(), 16), Math.floorDiv(origin.getZ() + pos.getZ(), 16)));
    }

    @Override public BlockState getBlockState(BlockPos pos) {
        Section section = section(pos);
        BlockState state = section == null ? super.getBlockState(pos) : section.states.getOrDefault(
                pos.offset(origin).subtract(section.origin).asLong(), Blocks.AIR.defaultBlockState());
        return state.isAir() ? projected.states.getOrDefault(pos.asLong(), state) : state;
    }

    @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }

    @Override public int getBrightness(LightLayer layer, BlockPos pos) {
        if (pos.getX() >= -1 && pos.getX() <= 16 && pos.getY() >= -1 && pos.getY() <= 16
                && pos.getZ() >= -1 && pos.getZ() <= 16) return super.getBrightness(layer, pos);
        Section section = section(pos);
        if (section == null || section.light.length != 18 * 18 * 18) return super.getBrightness(layer, pos);
        BlockPos at = pos.offset(origin).subtract(section.origin);
        int value = Byte.toUnsignedInt(section.light[((at.getX() + 1) * 18 + at.getY() + 1) * 18 + at.getZ() + 1]);
        return layer == LightLayer.SKY ? value >> 4 : value & 15;
    }

    @Override public int getMinBuildHeight() { return -16; }
    @Override public int getHeight() { return 48; }
}

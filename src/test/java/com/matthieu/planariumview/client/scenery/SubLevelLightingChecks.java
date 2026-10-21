package com.matthieu.planariumview.client.scenery;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.core.BlockPos;
import org.joml.Matrix4d;
import java.util.Arrays;
import java.util.List;

/** Checks that lighting invalidation stays local and preserves unchanged machines. */
public final class SubLevelLightingChecks {
    public static void run() {
        lightCrossesPhysicalSublevels();
        int[] origin = {20_481_024, 128, 20_483_072};
        for (int x = -16; x <= 16; x += 16) for (int y = -16; y <= 16; y += 16)
            for (int z = -16; z <= 16; z += 16) {
                check(SubLevelLighting.neighbors(origin, new int[]{origin[0] + x, origin[1] + y, origin[2] + z}),
                        "The changed section and face, edge and diagonal neighbors can contribute colored light");
            }
        check(!SubLevelLighting.neighbors(origin, new int[]{origin[0] + 32, origin[1], origin[2]}),
                "Sections beyond the light range must keep their mesh");
        check(!SubLevelLighting.neighbors(origin, new int[]{origin[0] + 2048, origin[1], origin[2]}),
                "Another storage plot must keep its meshes");
        check(!SubLevelLighting.neighbors(new int[]{Integer.MIN_VALUE, 0, 0}, new int[]{Integer.MAX_VALUE, 0, 0}),
                "Coordinate subtraction must not overflow into a false neighbor");
        check(!SubLevelLighting.neighbors(origin, new int[0]), "Uninitialized sections have no light contribution");

        CompoundTag original = new CompoundTag();
        CompoundTag machine = new CompoundTag();
        machine.putInt("Speed", 64);
        CompoundTag block = new CompoundTag();
        block.putInt("state", 0);
        block.put("nbt", machine);
        ListTag blocks = new ListTag();
        blocks.add(block);
        original.put("blocks", blocks);
        ListTag palette = new ListTag();
        CompoundTag state = new CompoundTag();
        state.putString("Name", "create:creative_motor");
        palette.add(state);
        original.put("palette", palette);
        CompoundTag relit = original.copy();
        relit.putByteArray("light", new byte[]{15, 14});
        relit.putString("biome", "minecraft:plains");
        relit.put("neighbors", new CompoundTag());
        check(SubLevelLighting.sameContents(original, relit), "Environment changes must preserve native machines");
        CompoundTag changedMachine = relit.copy();
        changedMachine.getList("blocks", 10).getCompound(0).getCompound("nbt").putInt("Speed", 128);
        check(!SubLevelLighting.sameContents(original, changedMachine), "Changed machine settings must still be applied");
        relit.getList("palette", 10).getCompound(0).putString("Name", "aeronautics:adjustable_burner");
        check(!SubLevelLighting.sameContents(original, relit), "Changed blocks require a new fake world");
    }

    private static void lightCrossesPhysicalSublevels() {
        BlockPos enginePlot = new BlockPos(20_481_024, 128, 20_485_120);
        BlockPos burnerPlot = new BlockPos(20_481_024, 112, 20_483_072);
        byte[] engineLight = new byte[18 * 18 * 18];
        Arrays.fill(engineLight, (byte) 0x70);
        byte[] burnerLight = new byte[engineLight.length];
        Arrays.fill(burnerLight, (byte) 0xFD);
        var burner = new SubLevelLighting.Section(burnerPlot, burnerLight);
        Matrix4d ship = new Matrix4d().translation(-1740, 82, 106).rotateXYZ(.1, .8, -.1);
        Matrix4d engine = new Matrix4d(ship).translate(5, 11, -1);
        int motor = ((7 + 1) * 18 + 1) * 18 + 8 + 1;
        var projected = SubLevelLighting.project(engineLight, enginePlot, engine,
                List.of(new SubLevelLighting.Frame(burner, ship)));
        check((projected.light[motor] & 255) == 0x7D,
                "Nearby physical sublevels share block light without replacing the engine's sky occlusion");
        check((engineLight[motor] & 255) == 0x70, "Projection must not mutate the received light snapshot");
        var rotated = SubLevelLighting.project(engineLight, enginePlot,
                new Matrix4d(ship).translate(5, 11, 15).rotateY(Math.PI / 2),
                List.of(new SubLevelLighting.Frame(burner, ship)));
        check((rotated.light[motor] & 255) == 0x7D,
                "A child sublevel rotated on its bearing must sample light in the parent's frame");
        var far = SubLevelLighting.project(engineLight, enginePlot, engine,
                List.of(new SubLevelLighting.Frame(burner, new Matrix4d(ship).translate(100, 0, 0))));
        check((far.light[motor] & 255) == 0x70, "A distant ship must not illuminate the engine");
        Arrays.fill(engineLight, (byte) 0x7F);
        projected = SubLevelLighting.project(engineLight, enginePlot, engine,
                List.of(new SubLevelLighting.Frame(burner, ship)));
        check((projected.light[motor] & 255) == 0x7F, "A stronger light on the engine must be preserved");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

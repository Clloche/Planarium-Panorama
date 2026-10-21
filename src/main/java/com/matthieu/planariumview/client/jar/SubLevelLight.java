package com.matthieu.planariumview.client.jar;

import com.matthieu.planariumview.client.sky.SkySync;
import com.matthieu.planariumview.compat.SubLevelCompat;
import com.matthieu.planariumview.config.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4d;
import org.joml.Vector3d;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.lang.reflect.Method;

/** Light at the physical position of Sable blocks, rather than their distant storage plot. */
public final class SubLevelLight {
    private static final AABB INTERIOR = new AABB(-1, 0, -1, 33, 34, 33);
    private record Frame(SubLevelCompat.Body body, AABB storage, Matrix4d pose) { }
    private record Snapshot(ClientLevel level, List<Frame> frames, byte[] light) { }
    // Published once, read by Sable's mesh workers without accessing live poses or the light engine.
    private static volatile Snapshot snapshot;
    private static final Map<UUID, Matrix4d> remeshed = new HashMap<>();
    private static long lastSample = Long.MIN_VALUE;
    private static int revision = -1;
    private static Method renderData, setDirty;
    private static boolean remeshFailed;

    private SubLevelLight() { }

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (!SkySync.inKnownJar(level) || !Config.realLighting() || !Config.enabled()) {
            Snapshot previous = snapshot;
            clear();
            if (previous != null && previous.level == level) {
                for (Frame frame : previous.frames) {
                    remesh(frame.body);
                }
            }
            return;
        }
        if (mc.isPaused() || !SubLevelCompat.available()) return;
        Snapshot previous = snapshot;
        boolean changedLevel = previous == null || previous.level != level;
        if (changedLevel) {
            remeshed.clear();
            lastSample = Long.MIN_VALUE;
        }
        List<SubLevelCompat.Body> bodies = SubLevelCompat.bodies(level, INTERIOR);
        List<Frame> frames = bodies.stream().map(body -> new Frame(body,
                new AABB(body.min().getX(), body.min().getY(), body.min().getZ(),
                        body.max().getX() + 1, body.max().getY() + 1, body.max().getZ() + 1).inflate(1),
                new Matrix4d(body.transform()))).toList();
        byte[] light = changedLevel ? null : previous.light;
        boolean sample = lastSample == Long.MIN_VALUE || level.getGameTime() - lastSample >= 20;
        boolean changedLight = revision != JarLight.revision();
        if (sample && !bodies.isEmpty()) {
            byte[] next = readLight(level);
            changedLight |= !Arrays.equals(light, next);
            light = next;
            lastSample = level.getGameTime();
        }
        snapshot = new Snapshot(level, frames, light);
        if (!changedLevel) {
            var currentIds = bodies.stream().map(SubLevelCompat.Body::id).toList();
            for (Frame frame : previous.frames) {
                if (!currentIds.contains(frame.body.id())) remesh(frame.body);
            }
        }
        if (changedLight) remeshed.clear();
        revision = JarLight.revision();
        if (sample || changedLight || changedLevel) {
            remeshed.keySet().retainAll(bodies.stream().map(SubLevelCompat.Body::id).toList());
            for (SubLevelCompat.Body body : bodies) {
                Matrix4d previousPose = remeshed.get(body.id());
                if (previousPose != null && previousPose.equals(body.transform(), 1.0e-5)) continue;
                remesh(body);
                remeshed.put(body.id(), new Matrix4d(body.transform()));
            }
        }
    }

    private static byte[] readLight(ClientLevel level) {
        byte[] result = new byte[JarLight.TABLE_SIZE * JarLight.TABLE_HEIGHT * JarLight.TABLE_SIZE];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = 0; x < JarLight.TABLE_SIZE; x++) {
            for (int y = 0; y < JarLight.TABLE_HEIGHT; y++) {
                for (int z = 0; z < JarLight.TABLE_SIZE; z++) {
                    pos.set(x, y, z);
                    result[JarLight.tableIndex(x, y, z)] = (byte) (level.getBrightness(LightLayer.SKY, pos) << 4
                            | level.getBrightness(LightLayer.BLOCK, pos));
                }
            }
        }
        return result;
    }

    public static int packedLight(int packed, BlockPos pos) {
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        Snapshot current = snapshot;
        if (current != null && current.level == Minecraft.getInstance().level
                && JarLight.tableIndex(x, y, z) < 0 && Config.enabled() && Config.realLighting()) {
            for (Frame frame : current.frames) {
                if (!frame.storage.contains(x + 0.5, y + 0.5, z + 0.5)) continue;
                Vector3d world = frame.pose.transformPosition(new Vector3d(x + 0.5, y + 0.5, z + 0.5));
                BlockPos at = BlockPos.containing(world.x, world.y, world.z);
                int index = JarLight.tableIndex(at.getX(), at.getY(), at.getZ());
                if (index < 0) break;
                if (current.light != null) {
                    int physical = Byte.toUnsignedInt(current.light[index]);
                    int block = Math.max((packed >> 4) & 15, physical & 15);
                    int sky = Math.min((packed >> 20) & 15, physical >> 4);
                    packed = (packed & ~0x00F000F0) | block << 4 | sky << 20;
                }
                return JarLight.packedLight(packed, at.getX(), at.getY(), at.getZ());
            }
        }
        return JarLight.packedLight(packed, x, y, z);
    }

    /** Sable compiles its own sections; invalidating only Minecraft's normal chunks misses them. */
    private static void remesh(SubLevelCompat.Body body) {
        if (remeshFailed) return;
        try {
            if (renderData == null) {
                renderData = Class.forName("dev.ryanhcode.sable.sublevel.ClientSubLevel").getMethod("getRenderData");
                setDirty = Class.forName("dev.ryanhcode.sable.sublevel.render.SubLevelRenderData")
                        .getMethod("setDirty", int.class, int.class, int.class, boolean.class);
            }
            Object data = renderData.invoke(body.handle());
            if (data == null) return;
            for (var chunk : body.chunks()) {
                var sections = chunk.getSections();
                for (int i = 0; i < sections.length; i++) {
                    if (!sections[i].hasOnlyAir()) {
                        setDirty.invoke(data, chunk.getPos().x, chunk.getSectionYFromSectionIndex(i), chunk.getPos().z, false);
                    }
                }
            }
        } catch (ReflectiveOperationException | LinkageError e) {
            remeshFailed = true;
            com.mojang.logging.LogUtils.getLogger().warn("[Planarium Panorama] Sable light refresh unavailable", e);
        }
    }

    public static void clear() {
        snapshot = null;
        remeshed.clear();
        lastSample = Long.MIN_VALUE;
        revision = -1;
    }
}

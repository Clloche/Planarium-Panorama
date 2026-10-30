package com.matthieu.planariumview;

import com.matthieu.planariumview.client.jar.ProjectorLighting;
import com.matthieu.planariumview.client.mini.PlanariumFloor;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import java.lang.reflect.*;
import java.util.*;

/** Mesh coverage and camera-independent cache reuse; no OpenGL context is needed. */
public final class RenderingCacheChecks {
    private static int assertions;
    private static void require(boolean ok, String message) { assertions++; if (!ok) throw new AssertionError(message); }
    private static Field field(Class<?> type, String name) throws Exception {
        Field f = type.getDeclaredField(name); f.setAccessible(true); return f;
    }
    private static Object create(Class<?> type) throws Exception {
        Constructor<?> c = type.getDeclaredConstructor(); c.setAccessible(true); return c.newInstance();
    }
    private static final float[][] POS = {{0, 0, 0}, {1, 0, 0}, {1, 0, 1}, {0, 0, 1}};
    private static final float[][] UV = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};

    private static void floorMeshes() throws Exception {
        Class<?> meshType = Class.forName(PlanariumFloor.class.getName() + "$Mesh");
        Method quad = meshType.getDeclaredMethod("quad", float[][].class, float[][].class, int[][].class, int.class);
        quad.setAccessible(true);
        for (int pattern = 0; pattern < 4; pattern++) {
            int[][] light = new int[33][33];
            for (int a = 0; a <= 32; a++) for (int b = 0; b <= 32; b++) {
                light[a][b] = switch (pattern) {
                    case 0 -> 0;
                    case 1 -> 240;
                    case 2 -> a < 16 ? 240 : 0;
                    default -> 16 * (1 + ((a * 7 + b * 3) % 15));
                };
            }
            Object mesh = create(meshType); quad.invoke(mesh, POS, UV, light, 0);
            int count = field(meshType, "count").getInt(mesh);
            float[] xyz = (float[]) field(meshType, "xyz").get(mesh), uv = (float[]) field(meshType, "uv").get(mesh);
            require(count % 4 == 0, "mesh remains compatible with QUADS");
            if (pattern == 0) require(count == 0, "unlit floor does not redraw the Ars plateau");
            if (pattern == 1) require(count == 512, "uniform bright floor uses its boundary fan instead of 4096 vertices");
            double area = 0;
            for (int i = 0; i < count; i += 4) {
                for (int[] tri : new int[][]{{0, 1, 2}, {0, 2, 3}}) {
                    int a = (i + tri[0]) * 3, b = (i + tri[1]) * 3, c = (i + tri[2]) * 3;
                    double signed = ((xyz[b] - xyz[a]) * (xyz[c + 2] - xyz[a + 2])
                            - (xyz[c] - xyz[a]) * (xyz[b + 2] - xyz[a + 2])) / 2;
                    require(signed >= 0, "merged geometry preserves the original winding");
                    area += signed;
                }
            }
            double expected = pattern == 0 ? 0 : pattern == 2 ? .5 : 1;
            require(Math.abs(area - expected) < 1e-7, "merged floor covers exactly the lit area without gaps or overlaps");
            for (int i = 0; i < count; i++) {
                require(xyz[3 * i] >= 0 && xyz[3 * i] <= 1 && xyz[3 * i + 2] >= 0 && xyz[3 * i + 2] <= 1,
                        "floor mesh stays on the plateau");
                require(xyz[3 * i] == uv[2 * i] && xyz[3 * i + 2] == uv[2 * i + 1], "UV interpolation is unchanged");
            }
        }
    }

    private static final class Vertices implements VertexConsumer {
        List<Vec3> positions = new ArrayList<>();
        public VertexConsumer addVertex(float x, float y, float z) { positions.add(new Vec3(x, y, z)); return this; }
        public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
        public VertexConsumer setUv(float u, float v) { return this; }
        public VertexConsumer setUv1(int u, int v) { return this; }
        public VertexConsumer setUv2(int u, int v) { return this; }
        public VertexConsumer setNormal(float x, float y, float z) { return this; }
    }

    @SuppressWarnings("unchecked") private static void projectorReuse() throws Exception {
        Class<?> cacheType = Class.forName(ProjectorLighting.class.getName() + "$QuadCache");
        Class<?> subdividerType = Class.forName(ProjectorLighting.class.getName() + "$Subdivider");
        List<Object> caches = (List<Object>) field(ProjectorLighting.class, "QUADS").get(null);
        List<Object> previous = new ArrayList<>(caches);
        Object cache = create(cacheType);
        double[] corners = (double[]) field(cacheType, "corners").get(cache);
        float[] uv = (float[]) field(cacheType, "cornerUv").get(cache);
        double[] xyz = (double[]) field(cacheType, "xyz").get(cache);
        for (int i = 0; i < 4; i++) {
            corners[3 * i] = xyz[3 * i] = POS[i][0]; corners[3 * i + 2] = xyz[3 * i + 2] = POS[i][2];
            uv[2 * i] = UV[i][0]; uv[2 * i + 1] = UV[i][1];
        }
        for (String name : List.of("stepsA", "stepsB")) field(cacheType, name).setInt(cache, 1);
        field(cacheType, "color").setInt(cache, -1); field(cacheType, "ny").setFloat(cache, 1);
        field(cacheType, "count").setInt(cache, 4);
        field(cacheType, "generation").setInt(cache, field(ProjectorLighting.class, "lightGeneration").getInt(null));
        Constructor<?> ctor = subdividerType.getDeclaredConstructor(VertexConsumer.class, ClientLevel.class, Vec3.class); ctor.setAccessible(true);
        Field dirty = field(ProjectorLighting.class, "gpuDirty"); boolean previousDirty = dirty.getBoolean(null);
        int previousIndex = field(ProjectorLighting.class, "quadIndex").getInt(null);
        try {
            caches.clear(); caches.add(cache);
            for (boolean gpu : List.of(false, true)) for (Vec3 camera : List.of(Vec3.ZERO, new Vec3(7.25, 2.5, -8.75))) {
                Vertices vertices = new Vertices();
                field(cacheType, "gpu").setBoolean(cache, gpu); dirty.setBoolean(null, false);
                field(ProjectorLighting.class, "quadIndex").setInt(null, 0);
                VertexConsumer consumer = (VertexConsumer) ctor.newInstance(gpu ? null : vertices, null, camera);
                for (int i = 0; i < 4; i++) consumer.addVertex(POS[i][0] - (float) camera.x, -(float) camera.y,
                        POS[i][2] - (float) camera.z, -1, UV[i][0], UV[i][1], 0, 0, 0, 1, 0);
                require(caches.get(0) == cache && !dirty.getBoolean(null), "camera movement reuses the GPU mesh without marking it dirty");
                require(vertices.positions.size() == (gpu ? 0 : 4), "GPU path skips replay; fallback still emits every vertex");
                if (!gpu) for (int i = 0; i < 4; i++)
                    require(vertices.positions.get(i).distanceToSqr(new Vec3(POS[i][0] - camera.x, -camera.y, POS[i][2] - camera.z)) < 1e-12,
                            "fallback vertices remain camera relative");
            }
        } finally {
            caches.clear(); caches.addAll(previous); dirty.setBoolean(null, previousDirty);
            field(ProjectorLighting.class, "quadIndex").setInt(null, previousIndex);
        }
    }

    public static void run() throws Exception {
        floorMeshes(); projectorReuse();
        Field quads = field(PlanariumFloor.class, "topQuads"); quads.set(null, List.of());
        PlanariumFloor.resourcesReloaded();
        require(quads.get(null) == null, "resource reload invalidates the cached model and UVs");
        System.out.println("PASS: " + assertions + " floor mesh and projector cache assertions");
    }
}

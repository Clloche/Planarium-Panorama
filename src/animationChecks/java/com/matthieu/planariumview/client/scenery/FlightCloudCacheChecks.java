package com.matthieu.planariumview.client.scenery;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import java.lang.reflect.*;
import java.util.*;

/** Production region traversal compared with per-section culling, plus cloud placement and cache keys. */
public final class FlightCloudCacheChecks {
    private static int assertions;
    private static Field field(Class<?> type, String name) throws Exception {
        Field f = type.getDeclaredField(name); f.setAccessible(true); return f;
    }
    private static void require(boolean ok, String message) { assertions++; if (!ok) throw new AssertionError(message); }
    private static Object fixture(Class<?> type) throws Exception {
        Method m = Class.forName("com.matthieu.planariumview.MobAnimationIntegrationChecks").getDeclaredMethod("fixture", Class.class);
        m.setAccessible(true); return m.invoke(null, type);
    }
    @SuppressWarnings("unchecked") private static void regions() throws Exception {
        Class<?> sectionType = Class.forName(FlightView.class.getName() + "$Section");
        Constructor<?> ctor = sectionType.getDeclaredConstructors()[0]; ctor.setAccessible(true);
        Method originOf = sectionType.getDeclaredMethod("origin"), boxOf = sectionType.getDeclaredMethod("box");
        originOf.setAccessible(true); boxOf.setAccessible(true);
        Map<Long, Object> sections = (Map<Long, Object>) field(FlightView.class, "sections").get(null);
        List<Object> frames = (List<Object>) field(FlightView.class, "frame").get(null);
        SceneryFrustum frustum = (SceneryFrustum) field(FlightView.class, "frustum").get(null);
        Method collect = FlightView.class.getDeclaredMethod("collectVisible", BlockPos.class, Matrix4f.class, FlightFog.Frame.class);
        collect.setAccessible(true);
        Random random = new Random(7325);
        try {
            for (BlockPos origin : List.of(BlockPos.ZERO, new BlockPos(29_999_984, 128, -29_999_984))) {
                sections.clear();
                for (int i = 0; i < 2500; i++) {
                    BlockPos at = origin.offset((random.nextInt(65) - 32) * 16, (random.nextInt(17) - 8) * 16,
                            (random.nextInt(65) - 32) * 16);
                    float[] box = {-2.125f, -.375f, -1.25f, 18.625f, 16.75f, 17.125f};
                    if (i % 99 == 0) box = null;
                    else if (i % 107 == 0) box = SceneryFrustum.emptyBox();
                    else if (i % 113 == 0) box = new float[]{0, Float.NaN, 0, 16, 16, 16};
                    sections.put(SectionPos.asLong(at), ctor.newInstance(at, Map.of(), box));
                }
                field(FlightView.class, "regionsDirty").setBoolean(null, true);
                for (int view = 0; view < 60; view++) {
                    Matrix4f common = new Matrix4f().rotateXYZ(random.nextFloat(), random.nextFloat() * 6, random.nextFloat())
                            .scale(32).translate(random.nextFloat() * 100 - 50, random.nextFloat() * 30 - 15, random.nextFloat() * 100 - 50);
                    Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9, .05f, 8192);
                    frustum.update(common, projection);
                    Set<Object> expected = Collections.newSetFromMap(new IdentityHashMap<>());
                    for (Object section : sections.values()) {
                        BlockPos at = (BlockPos) originOf.invoke(section);
                        float[] b = (float[]) boxOf.invoke(section);
                        int x = at.getX() - origin.getX(), y = at.getY() - origin.getY(), z = at.getZ() - origin.getZ();
                        if (!SceneryFrustum.known(b) || frustum.visible(new float[]{b[0] + x, b[1] + y, b[2] + z,
                                b[3] + x, b[4] + y, b[5] + z})) expected.add(section);
                    }
                    frames.clear(); collect.invoke(null, origin, common, FlightFog.frame(common));
                    Set<Object> actual = Collections.newSetFromMap(new IdentityHashMap<>());
                    for (Object frame : frames) {
                        Method section = frame.getClass().getDeclaredMethod("section"); section.setAccessible(true);
                        actual.add(section.invoke(frame));
                    }
                    require(actual.equals(expected), "region traversal matches conservative per-section visibility at every camera angle");
                    require(!field(FlightView.class, "regionsDirty").getBoolean(null), "camera changes reuse the region index");
                }
                for (Object region : (List<?>) field(FlightView.class, "regions").get(null)) {
                    Method box = region.getClass().getDeclaredMethod("box"), members = region.getClass().getDeclaredMethod("members");
                    box.setAccessible(true); members.setAccessible(true);
                    double[] bounds = (double[]) box.invoke(region);
                    if (bounds == null) continue;
                    for (Object section : (List<?>) members.invoke(region)) {
                        BlockPos at = (BlockPos) originOf.invoke(section); float[] b = (float[]) boxOf.invoke(section);
                        require(bounds[0] <= (double) at.getX() + b[0] && bounds[3] >= (double) at.getX() + b[3]
                                && bounds[1] <= (double) at.getY() + b[1] && bounds[4] >= (double) at.getY() + b[4]
                                && bounds[2] <= (double) at.getZ() + b[2] && bounds[5] >= (double) at.getZ() + b[5],
                                "region bounds enclose actual vertices without rounding source coordinates to floats");
                    }
                }
            }
            FlightView.clear();
            require(((List<?>) field(FlightView.class, "regions").get(null)).isEmpty(), "flight clear releases the region index");
        } finally { sections.clear(); frames.clear(); field(FlightView.class, "regionsDirty").setBoolean(null, true); }
    }
    private static void clouds() throws Exception {
        Random random = new Random(991);
        for (int i = 0; i < 400; i++) {
            double cu = (random.nextDouble() - .5) * 5_000_000, cv = (random.nextDouble() - .5) * 5_000_000;
            int ci = (int) Math.floor(cu), cj = (int) Math.floor(cv), a = ci + random.nextInt(21) - 10, b = cj + random.nextInt(21) - 10;
            float bottom = random.nextFloat() * 40 - 20, y = random.nextBoolean() ? 0 : 4;
            Matrix4f placement = new Matrix4f().translate(20, -25, 7).rotateXYZ(.3f, .7f, -.5f).scale(32);
            Vector3f old = placement.transformPosition(new Vector3f((float) ((a - cu) * 12), bottom + y, (float) ((b - cv) * 12)));
            Vector3f cached = CloudView.localPlacement(placement, cu, cv, ci, cj, bottom)
                    .transformPosition(new Vector3f((a - ci) * 12, y, (b - cj) * 12));
            require(old.distance(cached) < .002, "cell-local cloud mesh preserves drift and tilted placement");
        }
        ClientLevel level = (ClientLevel) fixture(ClientLevel.class);
        Method needs = CloudView.class.getDeclaredMethod("needsRebuild", ClientLevel.class, int.class, int.class, int.class,
                boolean.class, float.class, float.class, long.class); needs.setAccessible(true);
        CloudView.clear();
        field(CloudView.class, "built").setBoolean(null, true);
        field(CloudView.class, "builtLevel").set(null, new java.lang.ref.WeakReference<>(level));
        field(CloudView.class, "builtCi").setInt(null, -3); field(CloudView.class, "builtCj").setInt(null, 4);
        field(CloudView.class, "builtCells").setInt(null, 10); field(CloudView.class, "builtFancy").setBoolean(null, true);
        field(CloudView.class, "builtBottom").setFloat(null, 8); field(CloudView.class, "builtFar").setFloat(null, 1024);
        field(CloudView.class, "builtTime").setLong(null, 22);
        require(!(boolean) needs.invoke(null, level, -3, 4, 10, true, 8f, 1024f, 22L), "stationary cloud mesh is reused");
        require((boolean) needs.invoke(null, level, -2, 4, 10, true, 8f, 1024f, 22L), "new cloud cell invalidates mesh");
        require((boolean) needs.invoke(null, level, -3, 4, 10, false, 8f, 1024f, 22L), "cloud mode invalidates mesh");
        require((boolean) needs.invoke(null, level, -3, 4, 10, true, 8f, 1030f, 22L), "far distance invalidates fade even if cell count remains capped");
        require((boolean) needs.invoke(null, level, -3, 4, 10, true, 9.1f, 1024f, 22L), "vertical movement invalidates baked fade");
        require((boolean) needs.invoke(null, level, -3, 4, 10, true, 8f, 1024f, 23L), "fade is refreshed after ten ticks");
        require((boolean) needs.invoke(null, fixture(ClientLevel.class), -3, 4, 10, true, 8f, 1024f, 22L), "new world invalidates cloud cache");
        field(CloudView.class, "shape").set(null, new boolean[65536]); CloudView.clear();
        require(!field(CloudView.class, "built").getBoolean(null) && field(CloudView.class, "shape").get(null) == null,
                "resource reload forgets the cached mesh and cloud texture shape");
    }
    public static void run() throws Exception {
        regions(); clouds();
        System.out.println("PASS: " + assertions + " flight-region visibility, far-coordinate bounds, cloud placement and invalidation assertions");
    }
}

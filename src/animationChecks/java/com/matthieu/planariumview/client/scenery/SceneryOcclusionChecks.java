package com.matthieu.planariumview.client.scenery;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Pure geometry checks: actual grid traversal, conservative coverage and temporal cache, without a renderer. */
public final class SceneryOcclusionChecks {
    private static int assertions;
    private static final Vec3 CAMERA = new Vec3(2.5, 8.5, 8.5);
    private static final AABB MOB = new AABB(12, 7, 7, 13, 10, 10);

    private static void require(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private static VoxelOcclusion.Grid wall(int axis, int slice) {
        VoxelOcclusion.Grid grid = new VoxelOcclusion.Grid(24);
        for (int u = 0; u < 24; u++) for (int v = 0; v < 24; v++) {
            if (axis == 0) grid.set(slice, u, v);
            else if (axis == 1) grid.set(v, slice, u);
            else grid.set(u, v, slice);
        }
        return grid;
    }

    private static boolean hidden(VoxelOcclusion.Grid grid, Vec3 camera, AABB mob) {
        VoxelOcclusion visibility = new VoxelOcclusion(grid); visibility.beginFrame();
        return visibility.hidden(new Object(), camera, mob);
    }

    private static void geometry() {
        require(!hidden(new VoxelOcclusion.Grid(24), CAMERA, MOB), "open scenery stays visible");
        require(hidden(wall(0, 7), CAMERA, MOB), "full opaque wall hides the expanded box");
        require(!hidden(wall(0, 18), CAMERA, MOB), "wall behind the mob does not hide it");
        require(!hidden(wall(0, 7), new Vec3(7.5, 8.5, 8.5), MOB), "camera inside an opaque cell fails open");
        require(!hidden(wall(0, 7), MOB.getCenter(), MOB), "camera inside mob bounds stays visible");
        require(!hidden(wall(0, 7), new Vec3(Double.NaN, 8, 8), MOB), "invalid camera fails open");
        require(!hidden(wall(0, 7), CAMERA, new AABB(12, 0, 0, Double.POSITIVE_INFINITY, 10, 10)), "infinite renderer bounds fail open");
        require(!hidden(wall(0, 7), CAMERA, new AABB(12, 0, 0, 13, 40, 10)), "oversized renderer bounds fail open");
        for (int axis = 0; axis < 3; axis++) {
            Vec3 low = axis == 0 ? CAMERA : axis == 1 ? new Vec3(8.5, 2.5, 8.5) : new Vec3(8.5, 8.5, 2.5);
            Vec3 high = axis == 0 ? new Vec3(20, 8.5, 8.5) : axis == 1 ? new Vec3(8.5, 20, 8.5) : new Vec3(8.5, 8.5, 20);
            AABB box = axis == 0 ? MOB : axis == 1 ? new AABB(7, 12, 7, 10, 13, 10) : new AABB(7, 7, 12, 10, 10, 13);
            require(hidden(wall(axis, 7), low, box), "positive axis traversal and plane coverage");
            require(hidden(wall(axis, 16), high, box), "negative axis traversal and plane coverage");
        }
        VoxelOcclusion.Grid partial = new VoxelOcclusion.Grid(24);
        for (int y = 0; y < 24; y++) for (int z = 0; z < 8; z++) partial.set(7, y, z);
        require(!hidden(partial, CAMERA, MOB), "a partially exposed mob stays visible");
        // All nine sample rays hit this wall, but a window between them still exposes part of the box.
        VoxelOcclusion.Grid window = new VoxelOcclusion.Grid(24);
        for (int y = 0; y < 24; y++) for (int z = 0; z < 24; z++) if (y != 9 || z != 10) window.set(7, y, z);
        Vec3 from = new Vec3(2.5, 10.5, 10.5);
        AABB large = new AABB(12, 5, 5, 13, 16, 16);
        VoxelOcclusion probe = new VoxelOcclusion(window);
        for (int i = 0; i < 9; i++) {
            AABB expanded = large.inflate(.25);
            Vec3 end = i == 8 ? expanded.getCenter() : new Vec3((i & 1) == 0 ? expanded.minX : expanded.maxX,
                    (i & 2) == 0 ? expanded.minY : expanded.maxY, (i & 4) == 0 ? expanded.minZ : expanded.maxZ);
            require(probe.firstHit(from, end) != null, "window regression really blocks all nine sample rays");
        }
        require(!hidden(window, from, large), "blocked sample rays never hide a mob visible through a gap between them");
        VoxelOcclusion.Grid one = new VoxelOcclusion.Grid(24); one.set(7, 8, 8);
        require(!hidden(one, CAMERA, MOB), "a central obstruction does not hide the surrounding body");
    }

    private static void cache() {
        VoxelOcclusion visibility = new VoxelOcclusion(wall(0, 7)); visibility.beginFrame();
        Object mob = new Object();
        require(visibility.hidden(mob, CAMERA, MOB), "first occlusion is proven");
        for (int i = 0; i < 30; i++) require(visibility.hidden(mob, CAMERA.add(.01, 0, 0), MOB.move(.1, 0, 0)),
                "same-frame cache tolerates only motion inside the proven envelope");
        require(!visibility.hidden(mob, new Vec3(14, 8.5, 8.5), MOB), "moving camera past wall immediately reveals cached mob");
        visibility = new VoxelOcclusion(wall(0, 7)); visibility.beginFrame();
        require(visibility.hidden(mob, CAMERA, MOB), "new snapshot starts a new proof");
        require(!visibility.hidden(mob, CAMERA, MOB.move(-9, 0, 0)), "moving mob in front of wall immediately invalidates hidden result");
        require(!hidden(new VoxelOcclusion.Grid(24), CAMERA, MOB), "removed wall never reuses the old grid's result");
        visibility = new VoxelOcclusion(wall(0, 7));
        List<Object> crowd = new ArrayList<>();
        for (int i = 0; i < 64; i++) crowd.add(new Object());
        for (int frame = 0; frame < 24; frame++) {
            visibility.beginFrame(); int hidden = 0;
            for (Object entity : crowd) if (visibility.hidden(entity, CAMERA, MOB)) hidden++;
            require(hidden == Math.min(64, (frame + 1) * VoxelOcclusion.TESTS_PER_FRAME),
                    "64 mobs are spread over eight frames, then remain hidden without periodic flicker");
        }
        // Exhaust this frame's budget before invalidating an existing hidden entry.
        visibility.beginFrame();
        for (int i = 0; i < 8; i++) visibility.hidden(new Object(), CAMERA, MOB);
        require(!visibility.hidden(crowd.getFirst(), new Vec3(14, 8, 8), MOB), "stale hidden result fails open even with exhausted budget");
    }

    private static void transforms() {
        Matrix4f localToPocket = new Matrix4f().translation(130, -25, 80).rotateY(.7F).rotateX(.3F).scale(32.16F);
        Vector3f expected = new Vector3f(12.4F, 11.9F, 13.2F);
        Vector3f pocketCamera = localToPocket.transformPosition(new Vector3f(expected));
        Matrix4f localToCamera = new Matrix4f().translation(-pocketCamera.x, -pocketCamera.y, -pocketCamera.z).mul(localToPocket);
        Vec3 actual = SceneryMobOcclusion.camera(localToCamera);
        require(actual.distanceTo(new Vec3(expected.x, expected.y, expected.z)) < 1e-4,
                "actual camera maps back into the scenery under giant scaling and moving Sable transforms");
    }

    private static void sampledVisibility() {
        Random random = new Random(571);
        for (int scene = 0; scene < 80; scene++) {
            VoxelOcclusion.Grid grid = new VoxelOcclusion.Grid(24);
            for (int y = 0; y < 24; y++) for (int z = 0; z < 24; z++) if (random.nextDouble() > .08) grid.set(7, y, z);
            Vec3 camera = new Vec3(2.5, 4 + random.nextDouble() * 16, 4 + random.nextDouble() * 16);
            double y = 3 + random.nextDouble() * 14, z = 3 + random.nextDouble() * 14;
            AABB box = new AABB(12, y, z, 13, y + 2, z + 2);
            if (!hidden(grid, camera, box)) continue;
            VoxelOcclusion probe = new VoxelOcclusion(grid);
            for (int i = 0; i < 200; i++) {
                Vec3 eye = camera.add((random.nextDouble() - .5) / 8, (random.nextDouble() - .5) / 8, (random.nextDouble() - .5) / 8);
                Vec3 point = new Vec3(12 + random.nextDouble(), y + random.nextDouble() * 2, z + random.nextDouble() * 2);
                require(probe.firstHit(eye, point) != null, "a hidden result blocks randomly sampled rays across the camera and body envelope");
            }
        }
    }

    public static void main(String[] args) {
        geometry(); cache(); transforms(); sampledVisibility();
        System.out.println("PASS: " + assertions + " scenery occlusion assertions (walls, openings, camera/mob movement and bounded cache)");
    }
}

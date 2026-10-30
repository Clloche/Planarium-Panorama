package com.matthieu.planariumview.client.scenery;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.BitSet;
import java.util.IdentityHashMap;
import java.util.Map;

/** Conservative visibility against an immutable snapshot of full opaque cubes, in captured-world units. */
final class VoxelOcclusion {
    static final int INTERVAL = 8, TESTS_PER_FRAME = 8;
    private static final double EPSILON = 1.0e-5, CAMERA_MARGIN = 1.0 / 16, MOVEMENT_MARGIN = .25;
    private static final int MAX_STEPS = 256, MAX_COVERAGE_CELLS = 1024;

    static final class Grid {
        final int size;
        private final BitSet opaque;

        Grid(int size) {
            if (size < 1 || size > 65) throw new IllegalArgumentException("Invalid scenery grid size");
            this.size = size;
            opaque = new BitSet(size * size * size);
        }

        void set(int x, int y, int z) {
            if (inside(x, y, z)) opaque.set((x * size + y) * size + z);
        }

        boolean solid(int x, int y, int z) {
            return inside(x, y, z) && opaque.get((x * size + y) * size + z);
        }

        private boolean inside(int x, int y, int z) {
            return x >= 0 && y >= 0 && z >= 0 && x < size && y < size && z < size;
        }
    }

    private record Result(AABB camera, AABB bounds, boolean hidden, long expires) {}
    private final Map<Object, Result> results = new IdentityHashMap<>();
    private final Grid grid;
    private long frame;
    private int tests;

    VoxelOcclusion(Grid grid) { this.grid = grid; }

    void beginFrame() {
        frame++;
        tests = 0;
        if ((frame & 63) == 0) results.values().removeIf(result -> result.expires < frame - 64);
    }

    boolean hidden(Object entity, Vec3 camera, AABB bounds) {
        if (!finite(camera) || !finite(bounds) || bounds.contains(camera)) return false;
        Result previous = results.get(entity);
        if (previous != null && frame < previous.expires
                && (!previous.hidden || contains(previous.camera, camera) && contains(previous.bounds, bounds))) {
            return previous.hidden;
        }
        // A stale hidden result is never used outside its proven camera/target envelope.
        if (tests >= TESTS_PER_FRAME) return false;
        tests++;
        AABB cameraBox = new AABB(camera, camera).inflate(CAMERA_MARGIN);
        AABB target = bounds.inflate(MOVEMENT_MARGIN);
        boolean hidden = occluded(camera, cameraBox, target);
        results.put(entity, new Result(cameraBox, target, hidden, frame + INTERVAL));
        return hidden;
    }

    private boolean occluded(Vec3 camera, AABB cameraBox, AABB target) {
        if (target.intersects(cameraBox) || grid.solid(floor(camera.x), floor(camera.y), floor(camera.z))) return false;
        int[][] hits = new int[9][];
        for (int point = 0; point < 9; point++) {
            Vec3 end = point == 8 ? target.getCenter() : corner(target, point);
            hits[point] = firstHit(camera, end);
            if (hits[point] == null) return false;
        }
        // Nine blocked rays are NOT proof of occlusion: a window can lie between them.
        // Only hide if an entire rectangular cross-section of the camera-to-box cone is solid.
        // Unions of irregular occluders that cannot be proven this way simply keep rendering.
        for (int point = 0; point < hits.length; point++) for (int axis = 0; axis < 3; axis++) {
            int slice = hits[point][axis];
            boolean duplicate = false;
            for (int prior = 0; prior < point; prior++) if (hits[prior][axis] == slice) duplicate = true;
            if (!duplicate && coveredSlice(axis, slice, cameraBox, target)) return true;
        }
        return false;
    }

    /** Amanatides-Woo traversal. Edge/corner contacts may overcount here; the coverage proof decides visibility. */
    int[] firstHit(Vec3 start, Vec3 end) {
        int x = floor(start.x), y = floor(start.y), z = floor(start.z);
        double dx = end.x - start.x, dy = end.y - start.y, dz = end.z - start.z;
        int sx = (int) Math.signum(dx), sy = (int) Math.signum(dy), sz = (int) Math.signum(dz);
        double tx = boundary(start.x, x, dx), ty = boundary(start.y, y, dy), tz = boundary(start.z, z, dz);
        double stepx = dx == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dx);
        double stepy = dy == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dy);
        double stepz = dz == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dz);
        for (int step = 0; step < MAX_STEPS; step++) {
            if (grid.solid(x, y, z)) return new int[]{x, y, z};
            double next = Math.min(tx, Math.min(ty, tz));
            if (!Double.isFinite(next) || next >= 1) return null;
            if (tx <= next) { x += sx; tx += stepx; }
            if (ty <= next) { y += sy; ty += stepy; }
            if (tz <= next) { z += sz; tz += stepz; }
        }
        return null; // Traversal budget exhausted: visible.
    }

    private boolean coveredSlice(int axis, int slice, AABB camera, AABB target) {
        double plane = slice + .5;
        if (!(max(camera, axis) < plane - EPSILON && min(target, axis) > plane + EPSILON
                || max(target, axis) < plane - EPSILON && min(camera, axis) > plane + EPSILON)) return false;
        int u = (axis + 1) % 3, v = (axis + 2) % 3;
        double minU = Double.POSITIVE_INFINITY, minV = Double.POSITIVE_INFINITY;
        double maxU = Double.NEGATIVE_INFINITY, maxV = Double.NEGATIVE_INFINITY;
        // Projection extrema over two disjoint axis-aligned boxes occur at their corners.
        for (int a = 0; a < 8; a++) for (int b = 0; b < 8; b++) {
            double ca = coordinate(camera, a, axis), ta = coordinate(target, b, axis);
            double t = (plane - ca) / (ta - ca);
            double cu = coordinate(camera, a, u), cv = coordinate(camera, a, v);
            double pu = cu + t * (coordinate(target, b, u) - cu);
            double pv = cv + t * (coordinate(target, b, v) - cv);
            minU = Math.min(minU, pu); maxU = Math.max(maxU, pu);
            minV = Math.min(minV, pv); maxV = Math.max(maxV, pv);
        }
        int fromU = floor(minU - EPSILON), toU = floor(maxU + EPSILON);
        int fromV = floor(minV - EPSILON), toV = floor(maxV + EPSILON);
        if (fromU < 0 || fromV < 0 || toU >= grid.size || toV >= grid.size
                || (long) (toU - fromU + 1) * (toV - fromV + 1) > MAX_COVERAGE_CELLS) return false;
        for (int i = fromU; i <= toU; i++) for (int j = fromV; j <= toV; j++) {
            boolean solid = switch (axis) {
                case 0 -> grid.solid(slice, i, j);
                case 1 -> grid.solid(j, slice, i);
                default -> grid.solid(i, j, slice);
            };
            if (!solid) return false;
        }
        return true;
    }

    private static double boundary(double from, int cell, double delta) {
        return delta == 0 ? Double.POSITIVE_INFINITY : ((delta > 0 ? cell + 1 : cell) - from) / delta;
    }
    private static int floor(double value) { return (int) Math.floor(value); }
    private static Vec3 corner(AABB box, int index) {
        return new Vec3(coordinate(box, index, 0), coordinate(box, index, 1), coordinate(box, index, 2));
    }
    private static double coordinate(AABB box, int index, int axis) {
        return (index & (1 << axis)) == 0 ? min(box, axis) : max(box, axis);
    }
    private static double min(AABB box, int axis) { return axis == 0 ? box.minX : axis == 1 ? box.minY : box.minZ; }
    private static double max(AABB box, int axis) { return axis == 0 ? box.maxX : axis == 1 ? box.maxY : box.maxZ; }
    private static boolean contains(AABB outer, Vec3 point) {
        return outer.minX <= point.x && outer.minY <= point.y && outer.minZ <= point.z
                && outer.maxX >= point.x && outer.maxY >= point.y && outer.maxZ >= point.z;
    }
    private static boolean contains(AABB outer, AABB inner) {
        return outer.minX <= inner.minX && outer.minY <= inner.minY && outer.minZ <= inner.minZ
                && outer.maxX >= inner.maxX && outer.maxY >= inner.maxY && outer.maxZ >= inner.maxZ;
    }
    private static boolean finite(Vec3 point) { return Double.isFinite(point.x) && Double.isFinite(point.y) && Double.isFinite(point.z); }
    private static boolean finite(AABB box) {
        return Double.isFinite(box.minX) && Double.isFinite(box.minY) && Double.isFinite(box.minZ)
                && Double.isFinite(box.maxX) && Double.isFinite(box.maxY) && Double.isFinite(box.maxZ)
                && box.getXsize() <= 32 && box.getYsize() <= 32 && box.getZsize() <= 32;
    }
}

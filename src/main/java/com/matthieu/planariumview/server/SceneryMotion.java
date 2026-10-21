package com.matthieu.planariumview.server;

import net.minecraft.core.BlockPos;

/** Refresh a moving panorama before the jar reaches its edge, with a volume-dependent rate limit. */
public final class SceneryMotion {
    private SceneryMotion() {}

    public static boolean shouldCapture(BlockPos center, BlockPos captured, int radius, long elapsed) {
        long distance = Math.max(Math.abs((long) center.getX() - captured.getX()),
                Math.max(Math.abs((long) center.getY() - captured.getY()),
                        Math.abs((long) center.getZ() - captured.getZ())));
        if (distance == 0) return false;
        // Small captures can follow the 10 Hz pose updates. At radius 32, retain the old 1 Hz limit:
        // repeatedly rebuilding a 65^3 snapshot faster would overload the server and the renderer.
        long side = Math.clamp(radius, 1, 32) * 2L + 1;
        long interval = Math.max(2, (20 * side * side * side + 65 * 65 * 65 - 1) / (65 * 65 * 65));
        return elapsed >= interval && (distance >= Math.max(1, radius / 4) || elapsed >= 20);
    }
}

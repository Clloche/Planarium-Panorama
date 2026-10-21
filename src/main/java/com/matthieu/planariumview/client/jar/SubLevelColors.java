package com.matthieu.planariumview.client.jar;

import org.joml.Matrix4dc;
import org.joml.Vector3d;

/** Projects Contraption Lights cells into a storage section without modifying its cached source array. */
public final class SubLevelColors {
    private SubLevelColors() { }

    public static short[] apply(short[] original, int sx, int sy, int sz, Matrix4dc pose, short[] interior) {
        int size = JarLight.TABLE_SIZE, height = JarLight.TABLE_HEIGHT;
        if (interior == null || interior.length != size * height * size) return original;
        short[] result = null;
        Vector3d point = new Vector3d();
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    pose.transformPosition(point.set((sx << 4) + x + 0.5, (sy << 4) + y + 0.5, (sz << 4) + z + 0.5));
                    int wx = (int) Math.floor(point.x), wy = (int) Math.floor(point.y), wz = (int) Math.floor(point.z);
                    if (wx < 0 || wx >= size || wy < 0 || wy >= height || wz < 0 || wz >= size) continue;
                    int ours = Short.toUnsignedInt(interior[(wx * height + wy) * size + wz]);
                    if (ours == 0) continue;
                    int index = y << 8 | z << 4 | x;
                    int existing = original != null && original.length >= 4096 ? Short.toUnsignedInt(original[index]) : 0;
                    if ((existing >>> 12) > (ours >>> 12)) continue;
                    if (result == null) result = original != null && original.length >= 4096 ? original.clone() : new short[4096];
                    result[index] = (short) ours;
                    // The exterior cell replaces the animated emitter contribution at this point as well.
                    if (result.length > 4096 + index) result[4096 + index] = 0;
                }
            }
        }
        return result == null ? original : result;
    }
}

package com.matthieu.planariumview.client.scenery;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntBinaryOperator;

/** World-aligned, biome-blended colors. Each tile samples its halo before blending, including modded noise. */
public final class TerrainTintCache {
    private final int radius;
    private final IntBinaryOperator sample;
    private final Map<Long, int[]> tiles = new HashMap<>();

    public TerrainTintCache(int radius, IntBinaryOperator sample) {
        this.radius = Math.clamp(radius, 0, 7);
        this.sample = sample;
    }

    public int color(int worldX, int worldZ) {
        int x = worldX >> 4, z = worldZ >> 4;
        long key = ((long) x << 32) | (z & 0xFFFFFFFFL);
        int[] colors = tiles.computeIfAbsent(key, ignored -> build(x << 4, z << 4));
        return colors[(worldX & 15) * 16 + (worldZ & 15)];
    }

    private int[] build(int x0, int z0) {
        int side = 16 + 2 * radius, stride = side + 1;
        int[] red = new int[stride * stride], green = new int[red.length], blue = new int[red.length];
        for (int x = 0; x < side; x++) {
            for (int z = 0; z < side; z++) {
                int color = sample.applyAsInt(x0 + x - radius, z0 + z - radius);
                int at = (x + 1) * stride + z + 1;
                red[at] = ((color >> 16) & 255) + red[at - stride] + red[at - 1] - red[at - stride - 1];
                green[at] = ((color >> 8) & 255) + green[at - stride] + green[at - 1] - green[at - stride - 1];
                blue[at] = (color & 255) + blue[at - stride] + blue[at - 1] - blue[at - stride - 1];
            }
        }
        int width = radius * 2 + 1, count = width * width;
        int[] result = new int[16 * 16];
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                result[x * 16 + z] = (sum(red, stride, x, z, width) / count) << 16
                        | (sum(green, stride, x, z, width) / count) << 8
                        | sum(blue, stride, x, z, width) / count;
            }
        }
        return result;
    }

    private static int sum(int[] prefix, int stride, int x, int z, int width) {
        return prefix[(x + width) * stride + z + width] - prefix[x * stride + z + width]
                - prefix[(x + width) * stride + z] + prefix[x * stride + z];
    }
}

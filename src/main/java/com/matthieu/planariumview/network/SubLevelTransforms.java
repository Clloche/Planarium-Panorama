package com.matthieu.planariumview.network;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4d;
import org.joml.Matrix4dc;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/** Double precision is essential: Sable's storage plots can be millions of blocks from the origin. */
public final class SubLevelTransforms {
    private SubLevelTransforms() {
    }

    /** Direction-only inverse, shared by the sky and the terrain's moving reference frame. */
    public static Quaterniond inverseRotation(Matrix4dc anchor) {
        return anchor.getUnnormalizedRotation(new Quaterniond()).normalize().conjugate();
    }

    public static ListTag write(Matrix4dc matrix) {
        ListTag tag = new ListTag();
        for (double value : matrix.get(new double[16])) tag.add(DoubleTag.valueOf(value));
        return tag;
    }

    public static Matrix4d read(CompoundTag tag, String key) {
        ListTag values = tag.getList(key, Tag.TAG_DOUBLE);
        if (values.size() != 16) throw new IllegalArgumentException("Invalid sub-level transform");
        double[] matrix = new double[16];
        for (int i = 0; i < 16; i++) {
            matrix[i] = values.getDouble(i);
            if (!Double.isFinite(matrix[i])) throw new IllegalArgumentException("Non-finite sub-level transform");
        }
        Matrix4d result = new Matrix4d().set(matrix);
        if (Math.abs(result.determinant()) < 1.0e-12) throw new IllegalArgumentException("Singular sub-level transform");
        return result;
    }

    public static AABB bounds(Matrix4dc matrix, AABB local) {
        Vector3d min = new Vector3d(Double.POSITIVE_INFINITY);
        Vector3d max = new Vector3d(Double.NEGATIVE_INFINITY);
        Vector3d point = new Vector3d();
        for (int i = 0; i < 8; i++) {
            matrix.transformPosition(point.set((i & 1) == 0 ? local.minX : local.maxX,
                    (i & 2) == 0 ? local.minY : local.maxY, (i & 4) == 0 ? local.minZ : local.maxZ));
            min.min(point);
            max.max(point);
        }
        return new AABB(min.x, min.y, min.z, max.x, max.y, max.z);
    }
}

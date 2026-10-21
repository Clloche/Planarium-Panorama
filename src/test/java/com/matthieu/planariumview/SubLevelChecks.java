package com.matthieu.planariumview;

import com.matthieu.planariumview.compat.SubLevelCompat;
import com.matthieu.planariumview.network.SubLevelPayload;
import com.matthieu.planariumview.network.SubLevelTransforms;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4d;
import org.joml.Vector3d;

/** Executable regression checks without a game, GPU, Sable installation, or test framework dependency. */
public final class SubLevelChecks {
    public static void main(String[] args) {
        storageCoordinatesKeepPrecision();
        rotatedBoundsContainTheWholeShip();
        invalidTransformsAreRejected();
        geometrySurvivesNetworkRoundTrip();
        check(!SubLevelCompat.available(), "Sable must remain optional on the test classpath");
        System.out.println("Sub-level checks passed: distant plots, rotation, bounds, malformed matrices, compressed protocol, optional dependency");
    }

    private static void storageCoordinatesKeepPrecision() {
        Matrix4d ship = new Matrix4d().translation(12.25, 70.5, -20.75).rotateY(Math.PI / 2)
                .translate(-1_000_000_000.0, 0, -1_000_000_000.0);
        CompoundTag tag = new CompoundTag();
        tag.put("pose", SubLevelTransforms.write(ship));
        Matrix4d decoded = SubLevelTransforms.read(tag, "pose");
        Vector3d world = decoded.transformPosition(new Vector3d(1_000_000_000.0, 3, 1_000_000_002.0));
        near(world, new Vector3d(14.25, 73.5, -20.75));
        // The jar is on the moving ship. Another block three positions along its local X must remain three away.
        Matrix4d jar = new Matrix4d(ship).translate(1_000_000_000.0, 3, 1_000_000_000.0);
        Matrix4d section = new Matrix4d(ship).translate(1_000_000_000.0, 0, 1_000_000_000.0);
        Matrix4d relative = new Matrix4d(jar).invert().mul(section);
        near(relative.transformPosition(new Vector3d(3, 3, 0)), new Vector3d(3, 0, 0));
    }

    private static void rotatedBoundsContainTheWholeShip() {
        Matrix4d rotation = new Matrix4d().translation(10, 20, 30).rotateZ(Math.PI / 2);
        AABB bounds = SubLevelTransforms.bounds(rotation, new AABB(0, 0, 0, 2, 4, 6));
        near(new Vector3d(bounds.minX, bounds.minY, bounds.minZ), new Vector3d(6, 20, 30));
        near(new Vector3d(bounds.maxX, bounds.maxY, bounds.maxZ), new Vector3d(10, 22, 36));
        check(bounds.intersects(new AABB(6, 21, 35, 7, 22, 36)), "Rotated tip must be visible to the scene query");
        check(!bounds.intersects(new AABB(11, 20, 30, 12, 21, 31)), "Unrelated structures must be excluded");
    }

    private static void invalidTransformsAreRejected() {
        CompoundTag singular = new CompoundTag();
        singular.put("pose", SubLevelTransforms.write(new Matrix4d().zero()));
        rejects(() -> SubLevelTransforms.read(singular, "pose"));
        ListTag values = SubLevelTransforms.write(new Matrix4d());
        values.set(12, DoubleTag.valueOf(Double.NaN));
        CompoundTag nan = new CompoundTag();
        nan.put("pose", values);
        rejects(() -> SubLevelTransforms.read(nan, "pose"));
        rejects(() -> SubLevelTransforms.read(new CompoundTag(), "pose"));
    }

    private static void geometrySurvivesNetworkRoundTrip() {
        CompoundTag geometry = new CompoundTag();
        byte[] light = new byte[18 * 18 * 18];
        for (int i = 0; i < light.length; i++) light[i] = (byte) (i & 255);
        geometry.putByteArray("light", light);
        geometry.putString("id", "ship/section");
        geometry.put("pose", SubLevelTransforms.write(new Matrix4d().translation(1.25, 2.5, 3.75).rotateX(0.125)));
        for (boolean miniature : new boolean[]{false, true}) {
            var original = new SubLevelPayload(ResourceLocation.fromNamespaceAndPath("ars_nouveau", "test_jar"), miniature, geometry);
            var buffer = Unpooled.buffer();
            try {
                SubLevelPayload.STREAM_CODEC.encode(buffer, original);
                check(buffer.readableBytes() < light.length, "Geometry must be compressed before transmission");
                SubLevelPayload decoded = SubLevelPayload.STREAM_CODEC.decode(buffer);
                check(original.equals(decoded), "Scene identity, context and geometry must survive the network");
                check(!buffer.isReadable(), "The codec must consume exactly one packet");
            } finally {
                buffer.release();
            }
        }
    }

    private static void near(Vector3d actual, Vector3d expected) {
        check(actual.distance(expected) < 1.0e-5, "Expected " + expected + ", got " + actual);
    }

    private static void rejects(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Invalid scene transform was accepted");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

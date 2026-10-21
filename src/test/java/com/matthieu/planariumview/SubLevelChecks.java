package com.matthieu.planariumview;

import com.matthieu.planariumview.compat.SubLevelCompat;
import com.matthieu.planariumview.client.jar.SubLevelColors;
import com.matthieu.planariumview.network.SubLevelPayload;
import com.matthieu.planariumview.network.SubLevelTransforms;
import com.matthieu.planariumview.network.SubLevelPrefsPayload;
import com.matthieu.planariumview.config.Config;
import com.matthieu.planariumview.client.scenery.FramedParticle;
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
        coloredLightFollowsRotatedPlots();
        subLevelSwitchKeepsProtocolCompatible();
        particlesKeepTheirMovingFrame();
        com.matthieu.planariumview.client.scenery.SubLevelLightingChecks.run();
        check(!SubLevelCompat.available(), "Sable must remain optional on the test classpath");
        System.out.println("Sub-level checks passed: distant plots, rotation, bounds, malformed matrices, compressed protocol, projected colors, viewer preference, particle frames, optional dependency");
    }

    private static void particlesKeepTheirMovingFrame() {
        for (double scale : new double[]{32, 1.0 / 32}) {
            Matrix4d pose = new Matrix4d().translation(20, 4, -9).rotateXYZ(.2, .9, .1).scale(scale);
            var frame = new FramedParticle.Frame(() -> pose, () -> true, scale);
            Vector3d source = new Vector3d(3.5, 7.2, 11);
            Vector3d internal = new Vector3d(source).mul(scale).add(1024, 1024, 1024);
            Vector3d world = pose.transformPosition(new Vector3d(source));
            near(frame.matrix().transformPosition(new Vector3d(internal)), world);
            Vector3d camera = new Vector3d(7, 6, 5);
            Vector3d localCamera = frame.matrix().invert().transformPosition(new Vector3d(camera));
            near(frame.matrix().transformDirection(new Vector3d(internal).sub(localCamera)), new Vector3d(world).sub(camera));
            pose.translateLocal(9, 3, -2).rotateY(.7);
            near(frame.matrix().transformPosition(new Vector3d(internal)), pose.transformPosition(new Vector3d(source)));
        }
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

    private static void coloredLightFollowsRotatedPlots() {
        int sx = 1_280_064, sy = 8, sz = 1_280_192;
        Matrix4d pose = new Matrix4d().translation(16, 5, 16).rotateY(Math.PI / 2)
                .translate(-(sx << 4), -(sy << 4), -(sz << 4));
        // The center of local block (0,0,0) rotates into pocket block (16,5,15).
        short[] interior = new short[32 * 33 * 32];
        short blue = (short) 0xD00F;
        interior[(16 * 33 + 5) * 32 + 15] = blue;
        short[] original = new short[8192];
        original[4096] = 0x77; // Animated emitter contribution replaced by the exterior cell.
        short[] result = SubLevelColors.apply(original, sx, sy, sz, pose, interior);
        check(result[0] == blue && result[4096] == 0, "A rotated Sable block must receive the physical pocket's blue light");
        check(original[0] == 0 && original[4096] == 0x77, "The shared Contraption Lights section must stay unchanged");
        original[0] = (short) 0xEFA0;
        check(SubLevelColors.apply(original, sx, sy, sz, pose, interior) == original,
                "A stronger light on the sub-level must keep its own color and animation");
        check(SubLevelColors.apply(original, sx, sy, sz, new Matrix4d(), interior) == original,
                "Storage coordinates outside the physical pocket must not acquire its color");
    }

    private static void subLevelSwitchKeepsProtocolCompatible() {
        check(Config.SUB_LEVEL_VIEWS.getDefault(), "Sub-level views must default to ON");
        check(!Config.SABLE.present(), "The integration must detect a missing optional Sable dependency");
        for (boolean enabled : new boolean[]{false, true}) {
            var buffer = Unpooled.buffer();
            try {
                var preference = new SubLevelPrefsPayload(enabled);
                SubLevelPrefsPayload.STREAM_CODEC.encode(buffer, preference);
                check(SubLevelPrefsPayload.STREAM_CODEC.decode(buffer).equals(preference),
                        "Each player's ON/OFF preference must survive the network");
                check(!buffer.isReadable(), "The preference codec must consume one message");
            } finally {
                buffer.release();
            }
        }
        CompoundTag frame = new CompoundTag();
        frame.put("frames", new ListTag());
        frame.put("anchor", SubLevelTransforms.write(new Matrix4d().translation(20, 70, 30).rotateY(0.5)));
        var buffer = Unpooled.buffer();
        try {
            var original = new SubLevelPayload(ResourceLocation.fromNamespaceAndPath("ars_nouveau", "moving_jar"), false, frame);
            SubLevelPayload.STREAM_CODEC.encode(buffer, original);
            var decoded = SubLevelPayload.STREAM_CODEC.decode(buffer);
            check(decoded.equals(original), "A moving jar must retain its anchor even with structure views OFF");
            check(!decoded.scene().contains("geometry"), "An anchor update must not contain a structure capture");
        } finally {
            buffer.release();
        }
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

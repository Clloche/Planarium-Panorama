package com.matthieu.planariumview;

import com.matthieu.planariumview.network.FlightCoverage;
import com.matthieu.planariumview.network.FlightPayload;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import com.matthieu.planariumview.client.scenery.FlightFog;

/** Coverage, movement and the compact empty-section messages used to keep fog inside completed terrain. */
final class FlightChecks {
    static void run() {
        FlightCoverage coverage = new FlightCoverage();
        coverage.view(64, -4, 19);
        BlockPos center = new BlockPos(-8, 80, -8);
        check(coverage.edge(center) == 0, "Unknown terrain cannot open the fog");
        fill(coverage, center, 88, -4, 19);
        check(coverage.edge(center) == 64, "Empty sections count as ready, up to the far view distance");

        long gap = SectionPos.asLong(2, 5, -1);
        coverage.drop(gap);
        check(coverage.edge(center) == 38, "Fog must precede an unbuilt column at distance 40");
        check(coverage.edge(center.offset(16, 0, 0)) == 22, "Moving toward a gap must reduce coverage immediately");
        coverage.ready(gap);
        check(coverage.edge(center.offset(16, 0, 0)) == 64, "New terrain opens the view without rebuilding old sections");

        coverage.drop(SectionPos.asLong(-1, -5, -1));
        check(coverage.edge(center) == 64, "Sections below world height must not block coverage");
        coverage.clear();
        coverage.view(64, 0, 1);
        center = new BlockPos(0, 8, 0);
        fill(coverage, center, 64, 0, 1);
        check(coverage.edge(center) == 62, "The coverage margin must include the camera and fractional anchor");
        check(coverage.edge(center.offset(32, 0, 0)) == 46, "World-anchored coverage must follow a translated vessel");
        fill(coverage, center.offset(32, 0, 0), 88, 0, 1);
        check(coverage.edge(center.offset(32, 0, 0)) == 64, "Receiving the leading band restores the full distance");
        coverage.view(96, 0, 1);
        fill(coverage, center, 120, 0, 1);
        check(coverage.edge(center) == 96, "Increasing view distance must reuse existing coverage and extend it");
        coverage.view(12, 0, 1);
        check(coverage.edge(center) == 12, "Reducing the configured distance must close the fog immediately");
        coverage.clear();
        coverage.view(12, 0, 1);
        check(coverage.edge(center) == 0, "A new flight cannot inherit coverage from the previous flight");

        var pocket = ResourceLocation.fromNamespaceAndPath("ars_nouveau", "flight_test");
        roundTrip(FlightPayload.view(pocket, new BlockPos(-201, 80, 77), 96, -4, 19));
        roundTrip(FlightPayload.empty(pocket, new long[]{gap, SectionPos.asLong(-33, -4, 92)}));
        roundTrip(FlightPayload.signal(pocket, FlightPayload.STOP));
        shaderFogFollowsTheMovingSections();
    }

    private static void shaderFogFollowsTheMovingSections() {
        Matrix4f rotation = new Matrix4f().rotateXYZ(.3F, 1.2F, -.12F);
        Vector3f camera = new Vector3f(23, 81, -45);
        Vector3f point = new Vector3f(2100, 60, -430);
        Vector3f previousFogPosition = null;
        for (Vector3f section : new Vector3f[]{new Vector3f(2000, 0, -512), new Vector3f(2200, 48, -384)}) {
            Matrix4f original = new Matrix4f(rotation).translate(new Vector3f(section).sub(camera));
            Vector3f local = new Vector3f(point).sub(section);
            FlightFog.Frame frame = FlightFog.frame(original);
            Vector3f fogPosition = new Vector3f(local).add(frame.chunkOffset());
            Vector3f before = original.transformPosition(new Vector3f(local));
            Vector3f after = frame.modelView().transformPosition(new Vector3f(fogPosition));
            check(before.distance(after) < .002F, "Fixing shader fog must preserve the rendered vertex position");
            check(fogPosition.distance(new Vector3f(point).sub(camera)) < .002F,
                    "Fog must measure the distant terrain, rather than a position local to its section");
            if (previousFogPosition != null) {
                check(fogPosition.distance(previousFogPosition) < .002F,
                        "The same world position must receive the same fog across section borders");
            }
            previousFogPosition = fogPosition;
        }
    }

    private static void fill(FlightCoverage coverage, BlockPos center, int reach, int lowY, int highY) {
        for (int x = (center.getX() - reach) >> 4; x <= (center.getX() + reach) >> 4; x++) {
            for (int z = (center.getZ() - reach) >> 4; z <= (center.getZ() + reach) >> 4; z++) {
                for (int y = Math.max(lowY, (center.getY() - reach) >> 4);
                     y <= Math.min(highY, (center.getY() + reach) >> 4); y++) {
                    coverage.ready(SectionPos.asLong(x, y, z));
                }
            }
        }
    }

    private static void roundTrip(FlightPayload sent) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            FlightPayload.STREAM_CODEC.encode(buffer, sent);
            FlightPayload got = FlightPayload.STREAM_CODEC.decode(buffer);
            check(got.pocketDim().equals(sent.pocketDim()) && got.kind() == sent.kind()
                    && got.section() == sent.section() && java.util.Arrays.equals(got.sections(), sent.sections()),
                    "Flight metadata must survive the existing wire format");
            check(buffer.readableBytes() == 0, "Flight metadata must consume exactly one message");
        } finally {
            buffer.release();
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

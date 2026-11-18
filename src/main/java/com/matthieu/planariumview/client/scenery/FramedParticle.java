package com.matthieu.planariumview.client.scenery;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.matthieu.planariumview.core.config.Config;
import com.matthieu.planariumview.core.network.SubLevelTransforms;
import com.matthieu.planariumview.integration.particle_core.ParticleCoreCompat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Simulate in the captured world's axes; project only the rendered particle into its moving preview. */
public final class FramedParticle extends Particle {
    static final double OFFSET = 1024;
    private static RenderPass renderPass;

    private record RenderPass(Camera camera, Map<Frame, Projection> projections) {}

    private record Projection(Matrix4d matrix, LocalCamera camera) {
        static Projection create(Frame frame, Camera camera) {
            Matrix4d matrix = frame.matrix();
            LocalCamera local = new LocalCamera();
            local.update(camera, matrix);
            return new Projection(matrix, local);
        }
    }

    public static void renderPass(Camera camera, Runnable draw) {
        RenderPass previous = renderPass;
        renderPass = new RenderPass(camera, new IdentityHashMap<>());
        try {
            draw.run();
        } finally {
            renderPass = previous;
        }
    }

    public record Frame(Supplier<Matrix4d> pose, BooleanSupplier alive, double scale) {
        public Matrix4d matrix() {
            return new Matrix4d(pose.get()).scale(1 / scale).translate(-OFFSET, -OFFSET, -OFFSET);
        }
    }

    private final Particle inner;
    private final Frame frame;
    private final boolean ember;

    FramedParticle(Particle inner, Frame frame) {
        super(Minecraft.getInstance().level, 0, 0, 0);
        this.inner = inner;
        this.frame = frame;
        ember = inner.getClass().getName().endsWith(".HotAirEmberParticle");
        hasPhysics = false;
        locate();
        xo = x; yo = y; zo = z;
    }

    private void locate() {
        var box = SubLevelTransforms.bounds(frame.matrix(), inner.getBoundingBox());
        x = (box.minX + box.maxX) * .5;
        y = box.minY;
        z = (box.minZ + box.maxZ) * .5;
        setBoundingBox(box);
    }

    @Override public void tick() {
        if (!Config.enabled() || !Config.sceneryParticles() || !frame.alive.getAsBoolean()) {
            inner.remove(); remove(); return;
        }
        xo = x; yo = y; zo = z;
        SceneryParticles.tickFramed(inner, frame);
        if (!inner.isAlive()) remove();
        else locate();
    }

    @Override public ParticleRenderType getRenderType() { return inner.getRenderType(); }

    @Override public void render(VertexConsumer buffer, Camera camera, float partialTick) {
        if (!frame.alive.getAsBoolean()) return;
        RenderPass pass = renderPass;
        Projection projection = pass != null && pass.camera() == camera
                ? pass.projections().computeIfAbsent(frame, key -> Projection.create(key, camera))
                : Projection.create(frame, camera);
        // Aeronautics' ember bypasses Particle.getLightColor and queries the real client level directly.
        Integer light = ember ? SceneryParticles.lightOf(inner) : null;
        VertexConsumer transformed = new TransformedBuffer(buffer, projection.matrix(), light == null ? null : light | 0xF0);
        boolean restore = ParticleCoreCompat.billboardCamera(projection.camera());
        try {
            inner.render(transformed, projection.camera(), partialTick);
        } finally {
            if (restore) ParticleCoreCompat.billboardCamera(camera);
        }
    }

    private static final class LocalCamera extends Camera {
        void update(Camera camera, Matrix4d matrix) {
            Matrix4d inverse = new Matrix4d(matrix).invert();
            Vec3 at = camera.getPosition();
            Vector3d position = inverse.transformPosition(new Vector3d(at.x, at.y, at.z));
            setPosition(position.x, position.y, position.z);
            Quaternionf rotation = inverse.getUnnormalizedRotation(new Quaternionf()).normalize().mul(camera.rotation());
            Vector3f look = new Vector3f(0, 0, -1).rotate(rotation);
            setRotation((float) Math.toDegrees(Math.atan2(-look.x, look.z)),
                    (float) -Math.toDegrees(Math.asin(Math.clamp(look.y, -1F, 1F))), 0);
            rotation().set(rotation);
            getLookVector().set(look);
            getUpVector().set(0, 1, 0).rotate(rotation);
            getLeftVector().set(1, 0, 0).rotate(rotation);
        }
    }

    private record TransformedBuffer(VertexConsumer inner, Matrix4d matrix, Integer light) implements VertexConsumer {
        @Override public VertexConsumer addVertex(float x, float y, float z) {
            // Particle vertices are camera-relative: translation has already cancelled out.
            inner.addVertex((float) (matrix.m00() * x + matrix.m10() * y + matrix.m20() * z),
                    (float) (matrix.m01() * x + matrix.m11() * y + matrix.m21() * z),
                    (float) (matrix.m02() * x + matrix.m12() * y + matrix.m22() * z));
            return this;
        }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { inner.setColor(r, g, b, a); return this; }
        @Override public VertexConsumer setUv(float u, float v) { inner.setUv(u, v); return this; }
        @Override public VertexConsumer setUv1(int u, int v) { inner.setUv1(u, v); return this; }
        @Override public VertexConsumer setUv2(int u, int v) {
            inner.setUv2(light == null ? u : light & 0xFFFF, light == null ? v : light >>> 16);
            return this;
        }
        @Override public VertexConsumer setNormal(float x, float y, float z) {
            Vector3d normal = matrix.transformDirection(new Vector3d(x, y, z)).normalize();
            inner.setNormal((float) normal.x, (float) normal.y, (float) normal.z);
            return this;
        }
    }
}

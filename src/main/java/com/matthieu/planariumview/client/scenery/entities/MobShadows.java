package com.matthieu.planariumview.client.scenery.entities;

import com.matthieu.planariumview.client.sky.SkySync;
import com.matthieu.planariumview.config.Config;
import com.matthieu.planariumview.mixin.entities.EntityRenderDispatcherInvoker;
import com.matthieu.planariumview.mixin.entities.EntityRendererShadowAccessor;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Vanilla shadow geometry, reading captured blocks directly: Ars snapshot worlds have no chunks. */
public final class MobShadows {
    private static final ResourceLocation TEXTURE = ResourceLocation.withDefaultNamespace("textures/misc/shadow.png");

    private MobShadows() {}

    /** {@code distanceScale}: real blocks per copy block, for the shadow fade (1/32 in a miniature). */
    public record Frame(Vector3f camera, DimensionType dimension, int skyDarken, boolean realLighting, float distanceScale) {}

    /** Read the camera and lighting once per frame, in the same coordinates as the interpolated copies. */
    public static Frame frame(Matrix4f sceneryToCamera) {
        return frame(sceneryToCamera, 1.0F, Config.realLighting());
    }

    /** Same, for copies drawn at another scale (miniatures), with their own lighting choice. */
    public static Frame frame(Matrix4f sceneryToCamera, float distanceScale, boolean realLighting) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !mc.options.entityShadows().get()
                || !((EntityRenderDispatcherInvoker) mc.getEntityRenderDispatcher()).planariumview$shouldRenderShadow()) return null;
        DimensionType type = mc.level.dimensionType();
        int darken = mc.level.getSkyDarken();
        if (SkySync.inKnownJar(mc.level)) {
            ResourceLocation source = SkySync.sourceDimensionType();
            if (source != null) {
                DimensionType outside = mc.level.registryAccess().registryOrThrow(Registries.DIMENSION_TYPE).get(source);
                if (outside != null) type = outside;
            }
            darken = skyDarken(SkySync.timeOfDay(mc.level), SkySync.rainLevel(mc.level), SkySync.thunderLevel(mc.level));
        }
        return new Frame(camera(sceneryToCamera), type, darken, realLighting, distanceScale);
    }

    private static Vector3f camera(Matrix4f sceneryToCamera) {
        return new Matrix4f(sceneryToCamera).invert().transformPosition(new Vector3f());
    }

    /** Level.updateSkyBrightness, using the source world's time and weather. */
    private static int skyDarken(float timeOfDay, float rain, float thunder) {
        double daylight = .5 + 2 * Mth.clamp((double) Mth.cos(timeOfDay * Mth.TWO_PI), -.25, .25);
        return (int) ((1 - daylight * (1 - rain * 5.0 / 16) * (1 - thunder * 5.0 / 16)) * 11);
    }

    private static float strength(double distanceSquared, float rendererStrength) {
        return (float) (Math.max(0, 1 - distanceSquared / 256) * rendererStrength);
    }

    private static int light(BlockAndTintGetter world, BlockPos pos, int skyDarken) {
        return Math.max(world.getBrightness(LightLayer.BLOCK, pos), world.getBrightness(LightLayer.SKY, pos) - skyDarken);
    }

    private static int alpha(float strength, double height, float brightness) {
        return Mth.floor(Mth.clamp((strength - (float) height * .5F) * .5F * brightness, 0, 1) * 255);
    }

    /** The pose is at the captured terrain's origin, after GiantEntities has restored its render offset. */
    public static void render(Frame frame, Entity entity, Level world, PoseStack pose, MultiBufferSource buffers) {
        if (frame == null || entity.isInvisible()) return;
        var renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity);
        if (!(renderer instanceof EntityRendererShadowAccessor shadow)) return;
        float radius = Math.min(shadow.planariumview$shadowRadius(entity), 32);
        double x = entity.getX(), y = entity.getY(), z = entity.getZ(); // OutsideMobs.prepare already interpolated these.
        double dx = (x - frame.camera().x) * frame.distanceScale(), dy = (y - frame.camera().y) * frame.distanceScale(),
                dz = (z - frame.camera().z) * frame.distanceScale();
        float strength = strength(dx * dx + dy * dy + dz * dz, shadow.planariumview$shadowStrength());
        if (!(radius > 0) || !(strength > 0)) return;
        int minY = Mth.floor(y - Math.min(strength / .5F, radius));
        BlockPos.MutableBlockPos air = new BlockPos.MutableBlockPos();
        VertexConsumer vertices = buffers.getBuffer(RenderType.entityShadow(TEXTURE));
        pose.pushPose();
        try {
            pose.translate(x, y, z);
            for (int bz = Mth.floor(z - radius); bz <= Mth.floor(z + radius); bz++) {
                for (int bx = Mth.floor(x - radius); bx <= Mth.floor(x + radius); bx++) {
                    for (int by = minY; by <= Mth.floor(y); by++) {
                        air.set(bx, by, bz);
                        BlockPos below = air.below();
                        var state = world.getBlockState(below);
                        if (state.getRenderShape() == RenderShape.INVISIBLE || !state.isCollisionShapeFullBlock(world, below)) continue;
                        int light = frame.realLighting() ? light(world, air, frame.skyDarken()) : 15;
                        if (light <= 3) continue;
                        var shape = state.getShape(world, below);
                        if (shape.isEmpty()) continue;
                        int alpha = alpha(strength, y - by, LightTexture.getBrightness(frame.dimension(), light));
                        if (alpha > 0) quad(pose.last(), vertices, air, shape.bounds(), x, y, z, radius, alpha);
                    }
                }
            }
        } finally {
            pose.popPose();
        }
    }

    private static void quad(PoseStack.Pose pose, VertexConsumer vertices, BlockPos air, AABB shape,
                             double x, double y, double z, float radius, int alpha) {
        float x0 = (float) (air.getX() + shape.minX - x), x1 = (float) (air.getX() + shape.maxX - x);
        float height = (float) (air.getY() + shape.minY - y);
        float z0 = (float) (air.getZ() + shape.minZ - z), z1 = (float) (air.getZ() + shape.maxZ - z);
        vertex(pose, vertices, x0, height, z0, radius, alpha);
        vertex(pose, vertices, x0, height, z1, radius, alpha);
        vertex(pose, vertices, x1, height, z1, radius, alpha);
        vertex(pose, vertices, x1, height, z0, radius, alpha);
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer vertices, float x, float y, float z, float radius, int alpha) {
        vertices.addVertex(pose, x, y, z).setColor(255, 255, 255, alpha)
                .setUv(-x / (2 * radius) + .5F, -z / (2 * radius) + .5F)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(pose, 0, 1, 0);
    }
}

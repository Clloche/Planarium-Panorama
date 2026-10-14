package com.matthieu.planariumview;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

/**
 * Rayons de l'ambersol d'Alex's Caves dans le decor geant et la miniature.
 *
 * Alex's Caves ne dessine pas les rayons la ou on le lui demande : il retient seulement la position du bloc,
 * puis dessine tous les rayons plus tard, a cette position dans le monde. Dans le decor geant et la miniature,
 * cette position est celle du modele (0 a 32), donc les rayons apparaissaient au mauvais endroit, a la mauvaise taille.
 * Ici, les rayons sont dessines directement a la place du bloc, agrandis (decor) ou reduits (miniature) comme lui.
 */
public final class AmbersolCompat {
    private static final Logger LOGGER = LoggerFactory.getLogger("planariumview");
    private static final String BLOCK_ENTITY = "com.github.alexmodguy.alexscaves.server.block.blockentity.AmbersolBlockEntity";
    private static final float HALF_SQRT_3 = (float) (Math.sqrt(3.0D) / 2.0D);

    private static boolean initialized;
    private static Class<?> entityClass;
    private static Method lights;
    private static Method rotSpeed;
    private static Method rotOffset;
    private static Method shine;

    private AmbersolCompat() {
    }

    private static void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        try {
            entityClass = Class.forName(BLOCK_ENTITY);
            lights = entityClass.getMethod("getLights");
            rotSpeed = entityClass.getMethod("getRotSpeed");
            rotOffset = entityClass.getMethod("getRotOffset");
            shine = Class.forName("com.github.alexmodguy.alexscaves.client.render.ACRenderTypes").getMethod("getAmbersolShine");
            LOGGER.info("[Planarium Panorama] Alex's Caves detecte : rayons de l'ambersol dessines a leur place");
        } catch (Throwable t) {
            entityClass = null;
        }
    }

    /** Vrai si c'est un ambersol et que ses rayons ont ete dessines ici (Alex's Caves ne doit alors pas le dessiner). */
    public static boolean render(BlockEntity blockEntity, float partialTick, PoseStack poseStack, MultiBufferSource buffers) {
        init();
        if (entityClass == null || !entityClass.isInstance(blockEntity)) {
            return false;
        }
        try {
            draw(blockEntity, partialTick, poseStack, buffers);
        } catch (Throwable t) {
            LOGGER.debug("[Planarium Panorama] Rayons de l'ambersol ignores", t);
        }
        return true;
    }

    private static void draw(BlockEntity blockEntity, float partialTick, PoseStack poseStack, MultiBufferSource buffers)
            throws ReflectiveOperationException {
        Minecraft mc = Minecraft.getInstance();
        Entity viewer = mc.getCameraEntity();
        if (viewer == null) {
            return;
        }
        // Centre du bloc, vu depuis la camera, et taille d'un bloc a cet endroit (32 dans le decor, 1/32 dans la miniature).
        Matrix4f matrix = poseStack.last().pose();
        Vector3f center = matrix.transformPosition(0.5F, 0.5F, 0.5F, new Vector3f());
        float size = matrix.getScale(new Vector3f()).x;
        if (size <= 0.0F) {
            return;
        }
        // Meme taille que dans Alex's Caves, selon la distance mesuree en blocs du modele.
        double maxDist = 200;
        double dist = Math.min(center.length() / size, maxDist);
        float scale = (float) Math.pow(Math.sin(dist / maxDist * Math.PI), 0.5F) * 3F;
        if (scale <= 0.0F) {
            return;
        }
        int count = (Integer) lights.invoke(blockEntity);
        float speed = (Float) rotSpeed.invoke(blockEntity);
        float offset = (Float) rotOffset.invoke(blockEntity);
        RenderType type = (RenderType) shine.invoke(null);

        float time = viewer.tickCount + partialTick;
        float time1 = time * speed * 0.3F;
        float time2 = time * 0.1F;
        Quaternionf camera = mc.getEntityRenderDispatcher().cameraOrientation();

        PoseStack rays = new PoseStack();
        rays.translate(center.x, center.y, center.z);
        rays.scale(size, size, size);
        rays.mulPose(camera);
        rays.mulPose(Axis.ZN.rotationDegrees(offset));
        VertexConsumer consumer = buffers.getBuffer(type);
        for (int i = 0; i < count; i++) {
            float length = (float) (3F + Math.sin(time2 + i * 2)) * scale;
            float width = (float) (1F - 0.2F * Math.abs(Math.cos(time2 - i * Math.PI * 0.5F))) * scale;
            rays.pushPose();
            rays.mulPose(Axis.ZN.rotationDegrees(time1 - (i / (float) count * 360)));
            Matrix4f m = rays.last().pose();
            consumer.addVertex(m, 0.0F, 0.0F, 0.0F).setColor(255, 254, 233, 230);
            consumer.addVertex(m, -HALF_SQRT_3 * width, length, 0).setColor(215, 89, 32, 0);
            consumer.addVertex(m, HALF_SQRT_3 * width, length, 0).setColor(215, 89, 32, 0);
            consumer.addVertex(m, -HALF_SQRT_3 * width, length, 0).setColor(215, 89, 32, 0);
            rays.popPose();
        }
    }
}

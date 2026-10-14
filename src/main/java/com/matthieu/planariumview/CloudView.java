package com.matthieu.planariumview;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.slf4j.Logger;

import java.io.InputStream;

/**
 * Nuages du dehors dans le bocal : quand le Planarium est pres de la couche de nuages, on la dessine agrandie
 * comme le decor geant, a la bonne hauteur, avec la forme des nuages vanilla (texture clouds.png, cases de 12 blocs,
 * 4 blocs d'epaisseur) et leur couleur (heure, pluie). Seulement ce qui tient dans la distance d'affichage du bocal.
 */
final class CloudView {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceLocation CLOUDS = ResourceLocation.withDefaultNamespace("textures/environment/clouds.png");
    private static final float CELL = 12.0F;
    private static final float THICK = 4.0F;
    private static final int MAX_CELLS = 10;

    /** Cases pleines de la texture des nuages (256 x 256), lue une fois. */
    private static boolean[] shape;
    private static boolean shapeFailed;
    private static VertexBuffer buffer;

    private CloudView() {
    }

    static void draw(Matrix4f modelView, Matrix4f projection, Vec3 camera, float partialTick,
                     OutsideViewPayload info, int radius) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (info == null || level == null || !Config.clouds() || !SkySync.skyActive(level) || SkySync.underground(level)) {
            return;
        }
        CloudStatus status = mc.options.getCloudsType();
        if (status == CloudStatus.OFF) {
            return;
        }
        float height = SkySync.sourceCloudHeight();
        if (Float.isNaN(height) || !loadShape()) {
            return;
        }
        BlockPos planarium = info.origin().offset(radius, radius, radius);
        // Distance d'affichage du bocal, en blocs du monde d'origine.
        float far = mc.gameRenderer.getDepthFar();
        float reach = far / ClientView.GIANT;
        float bottom = height + 0.33F - planarium.getY();
        boolean fancy = status == CloudStatus.FANCY;
        float top = fancy ? bottom + THICK : bottom;
        if (bottom > reach || top < -reach) {
            return;
        }

        // Meme defilement que les nuages vanilla (vers +X), sur l'heure du jeu commune a tous les mondes.
        double drift = (level.getGameTime() + partialTick) * 0.03;
        double cu = (planarium.getX() + drift) / CELL;
        double cv = planarium.getZ() / CELL + 0.33;
        int ci = Mth.floor(cu);
        int cj = Mth.floor(cv);
        int n = Math.min(MAX_CELLS, Mth.ceil(reach / CELL) + 1);

        Vec3 color;
        SkySync.enterWeatherScope();
        try {
            color = level.getCloudColor(partialTick);
        } finally {
            SkySync.exitWeatherScope();
        }
        float r = (float) color.x;
        float g = (float) color.y;
        float b = (float) color.z;

        // Les nuages s'effacent avant la limite d'affichage (sinon ils seraient coupes net).
        float fadeEnd = far * 0.95F;
        float fadeStart = far * 0.55F;

        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        Builder out = new Builder(builder, camera, fadeStart, fadeEnd);
        for (int i = ci - n; i <= ci + n; i++) {
            for (int j = cj - n; j <= cj + n; j++) {
                if (!filled(i, j)) {
                    continue;
                }
                // Coordonnees relatives au Planarium (en blocs du monde d'origine).
                float x0 = (float) ((i - cu) * CELL);
                float x1 = x0 + CELL;
                float z0 = (float) ((j - cv) * CELL);
                float z1 = z0 + CELL;
                if (!fancy) {
                    out.quad(x0, bottom, z0, x1, bottom, z0, x1, bottom, z1, x0, bottom, z1, r, g, b, 1.0F);
                    continue;
                }
                out.quad(x0, bottom, z0, x1, bottom, z0, x1, bottom, z1, x0, bottom, z1, r, g, b, 0.7F);
                out.quad(x0, top, z0, x0, top, z1, x1, top, z1, x1, top, z0, r, g, b, 1.0F);
                if (!filled(i - 1, j)) {
                    out.quad(x0, bottom, z0, x0, bottom, z1, x0, top, z1, x0, top, z0, r, g, b, 0.9F);
                }
                if (!filled(i + 1, j)) {
                    out.quad(x1, bottom, z0, x1, top, z0, x1, top, z1, x1, bottom, z1, r, g, b, 0.9F);
                }
                if (!filled(i, j - 1)) {
                    out.quad(x0, bottom, z0, x0, top, z0, x1, top, z0, x1, bottom, z0, r, g, b, 0.8F);
                }
                if (!filled(i, j + 1)) {
                    out.quad(x0, bottom, z1, x1, bottom, z1, x1, top, z1, x0, top, z1, r, g, b, 0.8F);
                }
            }
        }
        MeshData mesh = builder.build();
        if (mesh == null) {
            return;
        }
        if (buffer == null) {
            buffer = new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
        }
        buffer.bind();
        buffer.upload(mesh);

        // Meme passage dans le bocal que le decor geant.
        Matrix4f pose = new Matrix4f(modelView)
                .translate(-ClientView.ARS_OFFSET * ClientView.GIANT,
                        -ClientView.ARS_OFFSET * ClientView.GIANT - ClientView.Y_SHIFT,
                        -ClientView.ARS_OFFSET * ClientView.GIANT)
                .scale(ClientView.GIANT);

        RenderSystem.enableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        // Comme les nuages vanilla : d'abord la profondeur seule, puis la couleur de la face la plus proche
        // (sinon les faces de derriere se verraient a travers, les nuages etant transparents).
        RenderSystem.colorMask(false, false, false, false);
        buffer.drawWithShader(pose, projection, GameRenderer.getPositionColorShader());
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.depthFunc(org.lwjgl.opengl.GL11.GL_LEQUAL);
        buffer.drawWithShader(pose, projection, GameRenderer.getPositionColorShader());
        VertexBuffer.unbind();
        RenderSystem.disableBlend();
        RenderSystem.enableCull();
    }

    private static boolean filled(int i, int j) {
        return shape[(i & 255) + (j & 255) * 256];
    }

    private static boolean loadShape() {
        if (shape != null) {
            return true;
        }
        if (shapeFailed) {
            return false;
        }
        try (InputStream in = Minecraft.getInstance().getResourceManager().open(CLOUDS);
             NativeImage image = NativeImage.read(in)) {
            int w = image.getWidth();
            int h = image.getHeight();
            boolean[] cells = new boolean[256 * 256];
            for (int x = 0; x < 256; x++) {
                for (int y = 0; y < 256; y++) {
                    int pixel = image.getPixelRGBA(x * w / 256, y * h / 256);
                    cells[x + y * 256] = (pixel >>> 24) >= 26;
                }
            }
            shape = cells;
            return true;
        } catch (Exception e) {
            LOGGER.warn("[Planarium Panorama] Texture des nuages illisible, pas de nuages dans le bocal", e);
            shapeFailed = true;
            return false;
        }
    }

    /** Ecrit les faces en coordonnees du monde (relatives au Planarium), avec l'effacement au loin. */
    private static final class Builder {
        private final BufferBuilder builder;
        private final Vec3 camera;
        private final float fadeStart;
        private final float fadeEnd;

        Builder(BufferBuilder builder, Vec3 camera, float fadeStart, float fadeEnd) {
            this.builder = builder;
            this.camera = camera;
            this.fadeStart = fadeStart;
            this.fadeEnd = fadeEnd;
        }

        void quad(float ax, float ay, float az, float bx, float by, float bz, float cx, float cy, float cz,
                  float dx, float dy, float dz, float r, float g, float b, float shade) {
            vertex(ax, ay, az, r, g, b, shade);
            vertex(bx, by, bz, r, g, b, shade);
            vertex(cx, cy, cz, r, g, b, shade);
            vertex(dx, dy, dz, r, g, b, shade);
        }

        private void vertex(float x, float y, float z, float r, float g, float b, float shade) {
            // Position dans le bocal, pour l'effacement au loin.
            double px = (x - ClientView.ARS_OFFSET) * ClientView.GIANT - camera.x;
            double py = (y - ClientView.ARS_OFFSET) * ClientView.GIANT - ClientView.Y_SHIFT - camera.y;
            double pz = (z - ClientView.ARS_OFFSET) * ClientView.GIANT - camera.z;
            float distance = (float) Math.sqrt(px * px + py * py + pz * pz);
            float fade = Mth.clamp((fadeEnd - distance) / (fadeEnd - fadeStart), 0.0F, 1.0F);
            builder.addVertex(x, y, z).setColor(r * shade, g * shade, b * shade, 0.8F * fade);
        }
    }
}

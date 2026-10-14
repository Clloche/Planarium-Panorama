package com.matthieu.planariumview;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;

/**
 * Pluie et neige du monde d'origine sur le decor geant.
 * Chaque colonne capturee qui voit le ciel recoit des rideaux de pluie (ou de neige) de taille normale,
 * du haut du ciel jusqu'a son sol agrandi x32. Sous un toit, rien ne tombe.
 */
public final class WeatherView {
    private static final ResourceLocation RAIN = ResourceLocation.withDefaultNamespace("textures/environment/rain.png");
    private static final ResourceLocation SNOW = ResourceLocation.withDefaultNamespace("textures/environment/snow.png");
    /** Rideaux par cote de colonne geante (2 x 2 rideaux d'environ 16 blocs). */
    private static final int CELLS = 2;
    /**
     * Taille des gouttes et des flocons. A 1, ils ont la taille normale du jeu et paraissent minuscules
     * a cote du decor geant ; a 32, ils seraient a l'echelle du decor. 16 : nettement plus gros, sans devenir
     * des traits enormes.
     */
    private static final float DROP_SCALE = 16.0F;
    /** Hauteur de pluie dessinee au-dessus et au-dessous de la camera. */
    private static final float HEIGHT = 192.0F;
    private static final int SKY_LIGHT = LightTexture.pack(0, 15);

    private static ResourceLocation dim;
    private static BlockPos planarium;
    private static int radius;
    private static int size;
    private static int originY;
    private static int[] tops;
    private static byte[] precip;

    /** Hauteur du terrain autour du Planarium (moyenne, min, max), en y du monde d'origine, ou null. */
    public static double[] terrainHeights() {
        int[] current = tops;
        if (current == null || current.length == 0) {
            return null;
        }
        long sum = 0;
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int top : current) {
            sum += top;
            min = Math.min(min, top);
            max = Math.max(max, top);
        }
        return new double[]{(double) sum / current.length, min, max};
    }

    /** Vrai si quelque chose bloque le ciel au-dessus du Planarium (plafond de caverne, toit...). */
    public static boolean roofedAtPlanarium() {
        int[] current = tops;
        BlockPos at = planarium;
        int index = radius * size + radius;
        return current != null && at != null && index >= 0 && index < current.length && current[index] > at.getY() + 2;
    }

    /**
     * Part des colonnes autour du Planarium (dans le decor geant) qui n'ont rien au-dessus de la hauteur du Planarium :
     * du ciel ouvert a cote (entree de caverne, falaise, surplomb). 0 dans une caverne fermee, 1 en plein air.
     */
    public static float openSkyFraction() {
        int[] current = tops;
        BlockPos at = planarium;
        if (current == null || at == null || current.length == 0) {
            return 1.0F;
        }
        int open = 0;
        for (int top : current) {
            if (top <= at.getY() + 1) {
                open++;
            }
        }
        return open / (float) current.length;
    }

    /** Precipitation au Planarium : 0 rien, 1 pluie, 2 neige, -1 inconnue. */
    public static int precipitationAtPlanarium() {
        byte[] current = precip;
        int index = radius * size + radius;
        return current == null || index < 0 || index >= current.length ? -1 : current[index];
    }
    private static int rainSoundTime;

    private WeatherView() {
    }

    public static void init() {
        NeoForge.EVENT_BUS.addListener(WeatherView::onRender);
        NeoForge.EVENT_BUS.addListener(WeatherView::onClientTick);
    }

    public static void set(OutsideViewPayload payload) {
        int s = payload.radius() * 2 + 1;
        if (payload.rainTops().length != s * s || payload.precip().length != s * s) {
            clear();
            return;
        }
        dim = payload.pocketDim();
        radius = payload.radius();
        size = s;
        originY = payload.origin().getY();
        planarium = payload.origin().offset(radius, radius, radius);
        tops = payload.rainTops();
        precip = payload.precip();
    }

    public static void clear() {
        dim = null;
        tops = null;
        precip = null;
    }

    private static boolean active(ClientLevel level) {
        return dim != null && tops != null && level != null && Config.weather()
                && level.dimension().location().equals(dim);
    }

    // ------------------------------------------------------------------ rendu

    private static void onRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_WEATHER) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (!active(level)) {
            return;
        }
        float rain = SkySync.rainLevel(level);
        if (rain <= 0.0F) {
            return;
        }
        Vec3 camera = event.getCamera().getPosition();
        Vector3f look = event.getCamera().getLookVector();
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        int ticks = event.getRenderTick();

        Matrix4fStack stack = RenderSystem.getModelViewStack();
        stack.pushMatrix();
        stack.identity();
        stack.mul(event.getModelViewMatrix());
        RenderSystem.applyModelViewMatrix();
        float fogStart = RenderSystem.getShaderFogStart();
        float fogEnd = RenderSystem.getShaderFogEnd();
        com.mojang.blaze3d.shaders.FogShape fogShape = RenderSystem.getShaderFogShape();
        ClientView.applySceneryFog();
        SkySync.giantLight(true, partial);
        LightTexture lightTexture = mc.gameRenderer.lightTexture();
        lightTexture.turnOnLightLayer();
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.setShader(GameRenderer::getParticleShader);
        try {
            float maxDistance = mc.options.getEffectiveRenderDistance() * 16.0F;
            drawPass(camera, look, rain, ticks, partial, maxDistance, (byte) 1, RAIN);
            drawPass(camera, look, rain, ticks, partial, maxDistance, (byte) 2, SNOW);
        } finally {
            RenderSystem.depthMask(true);
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
            RenderSystem.defaultBlendFunc();
            lightTexture.turnOffLightLayer();
            SkySync.giantLight(false, partial);
            RenderSystem.setShaderFogStart(fogStart);
            RenderSystem.setShaderFogEnd(fogEnd);
            RenderSystem.setShaderFogShape(fogShape);
            stack.popMatrix();
            RenderSystem.applyModelViewMatrix();
        }
    }

    /** Coordonnee geante (x ou z) du bord d'une colonne du monde d'origine. */
    private static float giant(int world, int planariumCoord) {
        return (world - planariumCoord - ClientView.ARS_OFFSET) * ClientView.GIANT;
    }

    private static float giantY(int worldY) {
        return (worldY - planarium.getY() - ClientView.ARS_OFFSET) * ClientView.GIANT - ClientView.Y_SHIFT;
    }

    private static void drawPass(Vec3 camera, Vector3f look, float rain, int ticks, float partial, float maxDistance,
                                 byte type, ResourceLocation texture) {
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
        float cell = ClientView.GIANT / CELLS;
        float half = cell * 0.5F;
        float time = ticks + partial;
        float top = (float) camera.y + HEIGHT;
        float floor = (float) camera.y - HEIGHT;
        float captureBottom = giantY(originY);
        RandomSource random = RandomSource.create();

        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                int index = i * size + j;
                // La colonne du Planarium, c'est l'interieur du bocal : il n'y pleut pas.
                if (precip[index] != type || (i == radius && j == radius)) {
                    continue;
                }
                float bottom = Math.max(Math.max(giantY(tops[index]), captureBottom), floor);
                if (bottom >= top) {
                    continue;
                }
                int wx = planarium.getX() - radius + i;
                int wz = planarium.getZ() - radius + j;
                float x0 = giant(wx, planarium.getX());
                float z0 = giant(wz, planarium.getZ());
                for (int a = 0; a < CELLS; a++) {
                    for (int b = 0; b < CELLS; b++) {
                        float cx = x0 + (a + 0.5F) * cell;
                        float cz = z0 + (b + 0.5F) * cell;
                        float dx = (float) (cx - camera.x);
                        float dz = (float) (cz - camera.z);
                        float dist = Mth.sqrt(dx * dx + dz * dz);
                        if (dist < 1.0F || dist > maxDistance) {
                            continue;
                        }
                        // Derriere la camera et assez loin : invisible, on saute.
                        if (dist > 64.0F && (dx * look.x() + dz * look.z()) / dist < -0.5F) {
                            continue;
                        }
                        long seed = (long) wx * 3121L + (long) wz * 45238971L + a * 13L + b * 7L;
                        random.setSeed(seed);
                        float px = -dz / dist * half;
                        float pz = dx / dist * half;
                        float fade = Mth.clamp(1.0F - dist / maxDistance, 0.0F, 1.0F);
                        float alpha = rain * (0.35F + 0.5F * fade);
                        float u0;
                        float vOffset;
                        if (type == 1) {
                            u0 = 0.0F;
                            vOffset = -(((ticks + (int) (seed & 0x7FFFFFFF)) & 31) + partial) / 32.0F * (3.0F + random.nextFloat());
                        } else {
                            u0 = (float) (random.nextDouble() + time * 0.01 * random.nextGaussian());
                            vOffset = -((ticks & 511) + partial) / 512.0F
                                    + (float) (random.nextDouble() + time * random.nextGaussian() * 0.001);
                        }
                        float u1 = u0 + cell / DROP_SCALE;
                        // Texture accrochee au monde (et pas a la camera) : la coordonnee v ne depend que
                        // de la hauteur du sommet. Elle diminue avec le temps, donc la pluie descend.
                        float v0 = -top * 0.25F / DROP_SCALE + vOffset;
                        float v1 = -bottom * 0.25F / DROP_SCALE + vOffset;
                        float rx = (float) (cx - camera.x);
                        float rz = (float) (cz - camera.z);
                        float yb = (float) (bottom - camera.y);
                        float yt = (float) (top - camera.y);
                        builder.addVertex(rx - px, yt, rz - pz).setUv(u0, v0).setColor(1.0F, 1.0F, 1.0F, alpha).setLight(SKY_LIGHT);
                        builder.addVertex(rx + px, yt, rz + pz).setUv(u1, v0).setColor(1.0F, 1.0F, 1.0F, alpha).setLight(SKY_LIGHT);
                        builder.addVertex(rx + px, yb, rz + pz).setUv(u1, v1).setColor(1.0F, 1.0F, 1.0F, alpha).setLight(SKY_LIGHT);
                        builder.addVertex(rx - px, yb, rz - pz).setUv(u0, v1).setColor(1.0F, 1.0F, 1.0F, alpha).setLight(SKY_LIGHT);
                    }
                }
            }
        }
        MeshData mesh = builder.build();
        if (mesh != null) {
            RenderSystem.setShaderTexture(0, texture);
            BufferUploader.drawWithShader(mesh);
        }
    }

    // ------------------------------------------------------------------ son

    /**
     * Vrai si un toit construit dans le bocal couvre cette position : un bloc qui arrete la pluie entre la tete du joueur
     * et le plafond du bocal (le plafond lui-meme ne compte pas).
     */
    static boolean roofedInJar(ClientLevel level, net.minecraft.core.BlockPos feet) {
        net.minecraft.world.level.block.Block boundary = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
                ResourceLocation.fromNamespaceAndPath("ars_nouveau", "dim_boundary"));
        net.minecraft.core.BlockPos.MutableBlockPos pos = feet.mutable();
        for (int y = feet.getY() + 2; y < level.getMaxBuildHeight(); y++) {
            pos.setY(y);
            net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
            if (state.is(boundary)) {
                return false;
            }
            if (state.blocksMotion() || !state.getFluidState().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** Bruit de pluie quand il pleut juste a cote du Planarium, comme vanilla (plus sourd s'il est a l'abri). */
    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (mc.isPaused() || mc.player == null || !active(level)) {
            return;
        }
        float rain = SkySync.rainLevel(level);
        if (rain <= 0.0F) {
            return;
        }
        RandomSource random = level.getRandom();
        if (random.nextInt(3) >= rainSoundTime++) {
            return;
        }
        rainSoundTime = 0;
        boolean nearby = false;
        for (int i = Math.max(0, radius - 2); i <= Math.min(size - 1, radius + 2) && !nearby; i++) {
            for (int j = Math.max(0, radius - 2); j <= Math.min(size - 1, radius + 2); j++) {
                int index = i * size + j;
                if (precip[index] == 1 && tops[index] <= planarium.getY() + 3) {
                    nearby = true;
                    break;
                }
            }
        }
        if (!nearby) {
            return;
        }
        boolean openAbove = tops[radius * size + radius] <= planarium.getY() + 1;
        Vec3 pos = mc.player.position();
        // Sous un toit construit dans le bocal, la pluie s'entend assourdie, comme sous un toit dehors.
        if (openAbove && !roofedInJar(level, mc.player.blockPosition())) {
            level.playLocalSound(pos.x, pos.y, pos.z, SoundEvents.WEATHER_RAIN, SoundSource.WEATHER,
                    0.2F * rain, 1.0F, false);
        } else {
            level.playLocalSound(pos.x, pos.y, pos.z, SoundEvents.WEATHER_RAIN_ABOVE, SoundSource.WEATHER,
                    0.1F * rain, 0.5F, false);
        }
    }
}

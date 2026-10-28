package com.matthieu.planariumview.client.scenery;

import com.matthieu.planariumview.client.sky.SkySync;
import com.matthieu.planariumview.client.sound.OutsideAmbience;
import com.matthieu.planariumview.config.Config;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import static com.matthieu.planariumview.client.scenery.ClientView.GIANT;
import static com.matthieu.planariumview.client.scenery.ClientView.drawDistance;
import static com.matthieu.planariumview.client.scenery.ClientView.edgeRadius;

/** Brouillard du decor geant et buffers du mur d'horizon et du dome des cavernes. */
final class SceneryFog {
    private SceneryFog() {
    }

    static void markDrawn() {
        lastDrawNanos = System.nanoTime();
    }

    /** Moment du dernier dessin du decor geant (voir particleFog). */
    private static volatile long lastDrawNanos;

    /**
     * Particules : quand le decor geant est dessine, elles prennent son brouillard. Avec celui du jeu, une particule du
     * decor un peu loin (fumee d'un feu de camp) prenait la couleur du brouillard alors que le decor autour restait net.
     * Les particules du bocal sont tout pres : ce brouillard plus lointain ne les touche pas. Faux si rien n'a change.
     */
    static boolean particleFog(boolean hasRenderData) {
        if (!hasRenderData || System.nanoTime() - lastDrawNanos > 250_000_000L) {
            return false;
        }
        applySceneryFog();
        // Sous terre, la meme couleur que le decor (voir SceneryRenderer) : le noir de l'option Sombre, ou le brouillard
        // du biome. Sans elle, les particules prenaient la couleur du brouillard de vanilla (ParticleEngineTickMixin la
        // remet ensuite).
        float[] cave = caveColor(Minecraft.getInstance().level);
        if (cave != null) {
            float[] fog = RenderSystem.getShaderFogColor();
            RenderSystem.setShaderFogColor(cave[0], cave[1], cave[2], fog[3]);
        }
        return true;
    }

    /**
     * Brouillard "Rayon de vue" : au-dela du bord du decor, il n'y a que le ciel. Le jour, il a la couleur du brouillard
     * et tout se fond ; la nuit (etoiles, ciels moddes comme AstroCraft), on voyait le ciel la ou le sol devrait etre.
     * On ferme donc l'horizon : un mur et un fond de la couleur du brouillard, juste apres le bord, jusqu'a l'horizon.
     */
    private static VertexBuffer fogWall;
    private static float fogWallEdge = -1;

    static void drawFogWall(PoseStack poseStack, Matrix4f projection, Vec3 camera) {
        if (Config.FOG.get() != Config.Fog.SCENERY_EDGE) {
            return;
        }
        float edge = edgeRadius() * GIANT + GIANT / 2.0F + 2.0F;
        if (fogWall == null || edge != fogWallEdge) {
            buildFogWall(edge);
        }
        if (fogWall == null) {
            return;
        }
        float[] fog = RenderSystem.getShaderFogColor();
        RenderSystem.setShaderColor(fog[0], fog[1], fog[2], 1.0F);
        RenderSystem.enableDepthTest();
        RenderSystem.disableCull();
        // Le haut du mur s'efface en degrade dans le ciel (voir buildFogWall).
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(false);
        fogWall.bind();
        fogWall.drawWithShader(poseStack.last().pose(), projection,
                net.minecraft.client.renderer.GameRenderer.getPositionColorShader());
        VertexBuffer.unbind();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        RenderSystem.enableCull();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    /**
     * Planarium sous terre : au-dela du bord du decor geant, on voyait le ciel (le haut de la caverne n'est pas dans la
     * photo). On ferme tout le tour et le dessus par l'obscurite, comme si la caverne continuait.
     */
    private static VertexBuffer caveDome;
    private static float caveDomeEdge = -1;

    static void drawCaveDome(PoseStack poseStack, Matrix4f projection) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !SkySync.underground(mc.level)) {
            return;
        }
        // Dans la distance d'affichage, sinon le dessus serait coupe et laisserait voir le ciel.
        float edge = Math.min(edgeRadius() * GIANT + GIANT / 2.0F + 3.0F, drawDistance() * 0.6F);
        if (caveDome == null || edge != caveDomeEdge) {
            caveDome = buildDome(caveDome, edge);
            caveDomeEdge = edge;
        }
        if (caveDome == null) {
            return;
        }
        float[] cave = caveColor(mc.level);
        if (cave == null) {
            return;
        }
        RenderSystem.setShaderColor(cave[0], cave[1], cave[2], 1.0F);
        RenderSystem.enableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);
        caveDome.bind();
        caveDome.drawWithShader(poseStack.last().pose(), projection,
                net.minecraft.client.renderer.GameRenderer.getPositionColorShader());
        VertexBuffer.unbind();
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    /**
     * Couleur de la caverne quand le Planarium est sous terre, ou null s'il ne l'est pas : noir avec l'option Sombre
     * (biome au brouillard normal), sinon la couleur de brouillard du biome du Planarium, assombrie selon l'heure
     * et la meteo du dehors comme le fait vanilla.
     */
    static float[] caveColor(net.minecraft.client.multiplayer.ClientLevel level) {
        if (level == null || !SkySync.underground(level)) {
            return null;
        }
        if (SkySync.blackCave(level)) {
            return new float[]{0.015F, 0.015F, 0.02F};
        }
        net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> biome = OutsideAmbience.knownBiomeHolder();
        if (biome != null) {
            int fog = SkySync.fogColorOf(biome.value());
            float r = ((fog >> 16) & 0xFF) / 255.0F;
            float g = ((fog >> 8) & 0xFF) / 255.0F;
            float b = (fog & 0xFF) / 255.0F;
            if (SkySync.customBiomeFog()) {
                // Brouillard propre au biome (Alex's Caves...) : sa couleur telle quelle, a toute heure.
                return new float[]{r, g, b};
            }
            // Comme FogRenderer en vanilla (meme sous terre) : plus sombre la nuit, puis sous la pluie et l'orage.
            float day = net.minecraft.util.Mth.clamp(
                    net.minecraft.util.Mth.cos(SkySync.timeOfDay(level) * ((float) Math.PI * 2.0F)) * 2.0F + 0.5F, 0.0F, 1.0F);
            r *= day * 0.94F + 0.06F;
            g *= day * 0.94F + 0.06F;
            b *= day * 0.91F + 0.09F;
            float weather = (1.0F - SkySync.rainLevel(level) * 0.5F) * (1.0F - SkySync.thunderLevel(level) * 0.5F);
            return new float[]{r * weather, g * weather, b * weather};
        }
        float[] fog = RenderSystem.getShaderFogColor();
        return new float[]{fog[0], fog[1], fog[2]};
    }

    /** Cylindre ferme (paroi, fond et dessus) autour du decor geant, en blanc (la couleur est appliquee au dessin). */
    private static VertexBuffer buildDome(VertexBuffer target, float edge) {
        float centerX = 16.0F;
        float centerZ = 16.0F;
        float bottom = 32.0F - edge * 0.8F;
        float top = 32.0F + edge * 0.8F;
        int segments = 48;
        BufferBuilder buffer = com.mojang.blaze3d.vertex.Tesselator.getInstance().begin(
                com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS, com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i < segments; i++) {
            double a0 = Math.PI * 2.0 * i / segments;
            double a1 = Math.PI * 2.0 * (i + 1) / segments;
            float x0 = centerX + (float) Math.cos(a0) * edge;
            float z0 = centerZ + (float) Math.sin(a0) * edge;
            float x1 = centerX + (float) Math.cos(a1) * edge;
            float z1 = centerZ + (float) Math.sin(a1) * edge;
            buffer.addVertex(x0, bottom, z0).setColor(1.0F, 1.0F, 1.0F, 1.0F);
            buffer.addVertex(x1, bottom, z1).setColor(1.0F, 1.0F, 1.0F, 1.0F);
            buffer.addVertex(x1, top, z1).setColor(1.0F, 1.0F, 1.0F, 1.0F);
            buffer.addVertex(x0, top, z0).setColor(1.0F, 1.0F, 1.0F, 1.0F);
            for (float y : new float[]{bottom, top}) {
                buffer.addVertex(centerX, y, centerZ).setColor(1.0F, 1.0F, 1.0F, 1.0F);
                buffer.addVertex(centerX, y, centerZ).setColor(1.0F, 1.0F, 1.0F, 1.0F);
                buffer.addVertex(x1, y, z1).setColor(1.0F, 1.0F, 1.0F, 1.0F);
                buffer.addVertex(x0, y, z0).setColor(1.0F, 1.0F, 1.0F, 1.0F);
            }
        }
        MeshData mesh = buffer.build();
        if (mesh == null) {
            return target;
        }
        VertexBuffer result = target != null ? target : new VertexBuffer(VertexBuffer.Usage.STATIC);
        result.bind();
        result.upload(mesh);
        VertexBuffer.unbind();
        return result;
    }

    /**
     * Mur (jusqu'au haut du bocal, a peu pres l'horizon) et fond, en blanc : la couleur du brouillard est appliquee au dessin.
     * Le haut du mur devient de plus en plus transparent jusqu'a l'horizon, sans ligne nette.
     */
    private static void buildFogWall(float edge) {
        float centerX = 16.0F;
        float centerZ = 16.0F;
        float top = 32.0F;
        float bottom = top - edge * 2.0F;
        // Degrade sur environ 8 degres sous l'horizon, vu depuis le bocal : il finit a l'horizon.
        float solid = top - edge * 0.14F;
        int segments = 48;
        BufferBuilder buffer = com.mojang.blaze3d.vertex.Tesselator.getInstance().begin(
                com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS, com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i < segments; i++) {
            double a0 = Math.PI * 2.0 * i / segments;
            double a1 = Math.PI * 2.0 * (i + 1) / segments;
            float x0 = centerX + (float) Math.cos(a0) * edge;
            float z0 = centerZ + (float) Math.sin(a0) * edge;
            float x1 = centerX + (float) Math.cos(a1) * edge;
            float z1 = centerZ + (float) Math.sin(a1) * edge;
            buffer.addVertex(x0, bottom, z0).setColor(1.0F, 1.0F, 1.0F, 1.0F);
            buffer.addVertex(x1, bottom, z1).setColor(1.0F, 1.0F, 1.0F, 1.0F);
            buffer.addVertex(x1, solid, z1).setColor(1.0F, 1.0F, 1.0F, 1.0F);
            buffer.addVertex(x0, solid, z0).setColor(1.0F, 1.0F, 1.0F, 1.0F);
            // Degrade : opaque en bas, transparent a l'horizon.
            buffer.addVertex(x0, solid, z0).setColor(1.0F, 1.0F, 1.0F, 1.0F);
            buffer.addVertex(x1, solid, z1).setColor(1.0F, 1.0F, 1.0F, 1.0F);
            buffer.addVertex(x1, top, z1).setColor(1.0F, 1.0F, 1.0F, 0.0F);
            buffer.addVertex(x0, top, z0).setColor(1.0F, 1.0F, 1.0F, 0.0F);
            buffer.addVertex(centerX, bottom, centerZ).setColor(1.0F, 1.0F, 1.0F, 1.0F);
            buffer.addVertex(centerX, bottom, centerZ).setColor(1.0F, 1.0F, 1.0F, 1.0F);
            buffer.addVertex(x1, bottom, z1).setColor(1.0F, 1.0F, 1.0F, 1.0F);
            buffer.addVertex(x0, bottom, z0).setColor(1.0F, 1.0F, 1.0F, 1.0F);
        }
        MeshData mesh = buffer.build();
        if (mesh == null) {
            return;
        }
        if (fogWall == null) {
            fogWall = new VertexBuffer(VertexBuffer.Usage.STATIC);
        }
        fogWall.bind();
        fogWall.upload(mesh);
        VertexBuffer.unbind();
        fogWallEdge = edge;
    }

    /**
     * Brouillard du decor geant, applique de la meme facon aux blocs normaux et aux blocs speciaux.
     * Sans brouillard : repousse a l'infini. Avec brouillard : brouillard de terrain vanilla calcule
     * sur la vraie distance d'affichage (sans l'extension de Voxy, qui le repousserait tres loin).
     */
    static void applySceneryFog() {
        if (OutsideFluidView.applyFog(Minecraft.getInstance().gameRenderer.getMainCamera())) return;
        Config.Fog mode = Config.FOG.get();
        if (mode == Config.Fog.OFF) {
            RenderSystem.setShaderFogStart(Float.MAX_VALUE / 2);
            RenderSystem.setShaderFogEnd(Float.MAX_VALUE);
            return;
        }
        if (mode == Config.Fog.SCENERY_EDGE) {
            // Bord du decor geant : le rayon de vue (en blocs du monde) fois 32, plus la moitie de la case du Planarium.
            float edge = edgeRadius() * GIANT + GIANT / 2.0F;
            // Scale the fade with the giant scenery too: 160 render units are only about five outside blocks.
            RenderSystem.setShaderFogStart(edge - net.minecraft.util.Mth.clamp(edge / 4.0F, GIANT, 16.0F * GIANT));
            RenderSystem.setShaderFogEnd(edge);
            RenderSystem.setShaderFogShape(com.mojang.blaze3d.shaders.FogShape.CYLINDER);
            return;
        }
        float renderDistance = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0F;
        float fogStart = renderDistance - net.minecraft.util.Mth.clamp(renderDistance / 10.0F, 4.0F, 64.0F);
        RenderSystem.setShaderFogStart(fogStart);
        RenderSystem.setShaderFogEnd(renderDistance);
        RenderSystem.setShaderFogShape(com.mojang.blaze3d.shaders.FogShape.CYLINDER);
    }

}

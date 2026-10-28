package com.matthieu.planariumview.client.scenery;

import com.hollingsworth.arsnouveau.client.renderer.tile.PlanariumRenderer.StructureRenderData;
import com.hollingsworth.arsnouveau.common.block.tile.PlanariumTile;
import com.matthieu.planariumview.client.scenery.SceneryMeshes.SectionMesh;
import com.matthieu.planariumview.client.scenery.SceneryMeshes.SectionPos;
import com.matthieu.planariumview.client.scenery.SceneryPatcher.BlockChange;
import com.matthieu.planariumview.client.scenery.entities.GiantEntities;
import com.matthieu.planariumview.client.scenery.entities.MobShadows;
import com.matthieu.planariumview.client.scenery.entities.OutsideMobs;
import com.matthieu.planariumview.client.scenery.entities.OutsideVehicles;
import com.matthieu.planariumview.client.sky.SkySync;
import com.matthieu.planariumview.compat.alexs_caves.AmbersolCompat;
import com.matthieu.planariumview.compat.contraption_lights.ColorCompat;
import com.matthieu.planariumview.config.Config;
import com.matthieu.planariumview.network.OutsideViewPayload;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static com.matthieu.planariumview.client.scenery.ClientView.ARS_OFFSET;
import static com.matthieu.planariumview.client.scenery.ClientView.GIANT;
import static com.matthieu.planariumview.client.scenery.ClientView.Y_SHIFT;
import static com.matthieu.planariumview.client.scenery.ClientView.applySceneryFog;
import static com.matthieu.planariumview.client.scenery.ClientView.liveArea;
import static com.matthieu.planariumview.client.scenery.ClientView.rebuild;
import static com.matthieu.planariumview.client.scenery.ClientView.viewRadius;
import static com.matthieu.planariumview.client.scenery.SceneryMeshes.buildPendingSections;
import static com.matthieu.planariumview.client.scenery.SceneryMeshes.builtViewRadius;
import static com.matthieu.planariumview.client.scenery.SceneryMeshes.sectionMeshes;
import static com.matthieu.planariumview.client.scenery.SceneryPatcher.applyPatch;
import static com.matthieu.planariumview.client.scenery.SceneryPatcher.blockEntityLight;
import static com.matthieu.planariumview.client.scenery.SceneryPatcher.build;
import static com.matthieu.planariumview.client.scenery.SceneryPatcher.sceneryEntityLight;
import static com.matthieu.planariumview.client.scenery.SceneryReceiver.dispose;
import static com.matthieu.planariumview.client.scenery.SceneryReceiver.pendingDiffNanos;
import static com.matthieu.planariumview.client.scenery.SceneryReceiver.pendingInfo;
import static com.matthieu.planariumview.client.scenery.SceneryReceiver.pendingLight;
import static com.matthieu.planariumview.client.scenery.SceneryReceiver.pendingPatch;
import static com.matthieu.planariumview.client.scenery.SceneryReceiver.pendingPatchInfo;
import static com.matthieu.planariumview.client.scenery.SceneryReceiver.pendingPatchLight;
import static com.matthieu.planariumview.client.scenery.SceneryReceiver.pendingTemplate;
import static com.matthieu.planariumview.client.scenery.SceneryReceiver.releaseRenderData;
import static com.matthieu.planariumview.client.scenery.SceneryState.currentDim;
import static com.matthieu.planariumview.client.scenery.SceneryState.currentRadius;
import static com.matthieu.planariumview.client.scenery.SceneryState.lastInfo;
import static com.matthieu.planariumview.client.scenery.SceneryState.renderData;

/** Rendu du decor geant, de ses blocs speciaux et de ses entites. */
final class SceneryRenderer {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClientView.class);

    private static final List<RenderType> DRAW_ORDER = List.of(
            RenderType.solid(), RenderType.cutout(), RenderType.cutoutMipped(),
            RenderType.translucent(), RenderType.tripwire());

    private static int sortCounter;

    private SceneryRenderer() {
    }

    static void onRenderStage(RenderLevelStageEvent event) {
        boolean sky = event.getStage() == RenderLevelStageEvent.Stage.AFTER_SKY;
        boolean blockEntities = event.getStage() == RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES;
        if (!sky && !blockEntities) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (sky) {
            SubLevelView.beginFrame();
            SkySync.resetWeatherScope();
        }
        if (sky && mc.level != null) {
            // Deuxieme point d'entree pour la lightmap du bocal, apres son calcul par le jeu (ou un autre mod)
            // et avant le dessin des blocs.
            SkySync.afterLightmapUpdate(mc.gameRenderer.lightTexture(), event.getPartialTick().getGameTimeDeltaPartialTick(false), "rendu");
        }
        if (mc.level == null || currentDim == null || !mc.level.dimension().location().equals(currentDim)
                || !Config.enabled()) {
            return;
        }
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        if (blockEntities) {
            if (renderData != null) {
                SkySync.giantLight(true, partialTick);
                try {
                    if (!FlightView.active()) drawBlockEntities(renderData, event);
                    if (lastInfo != null) {
                        // Blocs speciaux des structures Sable (roue, machines de Create) : le brouillard du decor geant,
                        // comme les blocs speciaux du decor. Avec celui du bocal, ils disparaissaient a quelques blocs.
                        float fogStart = RenderSystem.getShaderFogStart();
                        float fogEnd = RenderSystem.getShaderFogEnd();
                        com.mojang.blaze3d.shaders.FogShape fogShape = RenderSystem.getShaderFogShape();
                        applySceneryFog();
                        try {
                            SubLevelView.drawOutside(event,
                                    lastInfo.origin().offset(currentRadius, currentRadius, currentRadius), true);
                        } finally {
                            RenderSystem.setShaderFogStart(fogStart);
                            RenderSystem.setShaderFogEnd(fogEnd);
                            RenderSystem.setShaderFogShape(fogShape);
                        }
                    }
                } catch (Exception e) {
                    LOGGER.debug("[Planarium View] Erreur de rendu des blocs speciaux", e);
                } finally {
                    SkySync.giantLight(false, partialTick);
                }
            }
            return;
        }
        SkySync.giantLight(true, partialTick);
        try {
            // La distance d'affichage a change : on reconstruit le decor au nouveau rayon de vue.
            if (renderData != null && builtViewRadius != viewRadius()) {
                rebuild();
            }
            if (pendingTemplate != null) {
                StructureTemplate template = pendingTemplate;
                byte[] light = pendingLight;
                OutsideViewPayload info = pendingInfo;
                int radius = currentRadius;
                pendingTemplate = null;
                StructureRenderData oldRender = renderData;
                try {
                    StructureRenderData newRender = build(template, light, info, radius, event.getCamera().getPosition());
                    renderData = newRender;
                    lastInfo = info;
                    if (oldRender != newRender) {
                        releaseRenderData(oldRender);
                    }
                } catch (Exception e) {
                    LOGGER.error("[Planarium View] Echec de reconstruction; conservation de la vue precedente", e);
                    if (pendingTemplate == null) {
                        pendingTemplate = template;
                        pendingLight = light;
                        pendingInfo = info;
                    }
                }
            } else if (pendingPatch != null && renderData != null) {
                Map<BlockPos, BlockChange> patch = pendingPatch;
                byte[] light = pendingPatchLight;
                OutsideViewPayload info = pendingPatchInfo;
                long diffNanos = pendingDiffNanos;
                pendingPatch = null;
                pendingPatchLight = null;
                pendingPatchInfo = null;
                pendingDiffNanos = 0L;
                try {
                    applyPatch(renderData, patch, light, info, currentRadius, diffNanos);
                    lastInfo = info;
                } catch (Exception e) {
                    LOGGER.warn("[Planarium View] Correction partielle du decor impossible; reconstruction complete", e);
                    rebuild();
                }
            }
            buildPendingSections(event.getCamera().getPosition());
            SceneryParticles.prepareHues();
            if (renderData != null) {
                GiantSprites.markActive();
                SceneryClip.begin(event.getCamera(), event.getModelViewMatrix());
                try { draw(renderData, event); }
                finally { SceneryClip.end(); }
            }
        } catch (Exception e) {
            LOGGER.error("[Planarium View] Erreur de rendu, vue desactivee", e);
            dispose();
        } finally {
            SkySync.giantLight(false, partialTick);
        }
    }

    private static void draw(StructureRenderData data, RenderLevelStageEvent event) {
        SceneryFog.markDrawn();
        if (++sortCounter > 20) {
            sortCounter = 0;
            Vec3 camera = event.getCamera().getPosition();
            for (SectionMesh section : sectionMeshes.values()) section.sort(camera);
        }

        Vec3 camera = event.getCamera().getPosition();
        FarRing.buildPending(camera);
        NeighborPockets.buildPending(camera);
        FlightView.update(camera);
        PoseStack poseStack = new PoseStack();
        poseStack.mulPose(event.getModelViewMatrix());
        poseStack.translate(-camera.x, -camera.y, -camera.z);

        float fogStart = RenderSystem.getShaderFogStart();
        float fogEnd = RenderSystem.getShaderFogEnd();
        com.mojang.blaze3d.shaders.FogShape fogShape = RenderSystem.getShaderFogShape();
        float[] fogColor = RenderSystem.getShaderFogColor().clone();
        applySceneryFog();
        // Sous terre, le decor se fond dans la couleur de la caverne : le brouillard du biome, ou le noir (option Sombre).
        Minecraft client = Minecraft.getInstance();
        float[] cave = client.level != null ? SceneryFog.caveColor(client.level) : null;
        if (cave != null) {
            RenderSystem.setShaderFogColor(cave[0], cave[1], cave[2], fogColor[3]);
        }
        try {
            Matrix4f projectionMatrix = new Matrix4f(event.getProjectionMatrix());
            // Fond du decor avant les blocs : l'eau et le verre melangent leur couleur avec ce qui est deja dessine,
            // puis ecrivent la profondeur. Apres eux, le fond ne pouvait plus remplacer le ciel vu par transparence.
            // Meme repere du bocal que precedemment, avant le placement du terrain Sable.
            SceneryFog.drawFogWall(poseStack, projectionMatrix, camera);
            SceneryFog.drawCaveDome(poseStack, projectionMatrix);
            boolean flight = FlightView.active();
            FlightView.prepare(poseStack, projectionMatrix);
            // Repere du bocal, sans le placement du terrain : les Planariums voisins y sont fixes (voir NeighborPockets).
            Matrix4f jarPose = new Matrix4f(poseStack.last().pose());
            poseStack.pushPose();
            PoseStack placement = new PoseStack();
            if (lastInfo != null) SubLevelView.terrain(placement,
                    lastInfo.origin().offset(currentRadius, currentRadius, currentRadius));
            Matrix4f terrain = new Matrix4f(placement.last().pose());
            poseStack.mulPose(terrain);
            // Sections du decor detaille dans le champ de la camera, une fois pour toutes les couches. Meme matrice que
            // le dessin (placement Sable compris) : meme repere que les sommets. Le tri du verre et de l'eau est garde.
            NEAR_FRUSTUM.update(poseStack.last().pose(), projectionMatrix);
            SPECIAL_FRUSTUM.update(poseStack.last().pose(), projectionMatrix);
            List<Map.Entry<SectionPos, SectionMesh>> visibleSections = new ArrayList<>();
            for (Map.Entry<SectionPos, SectionMesh> entry : sectionMeshes.entrySet()) {
                if (NEAR_FRUSTUM.visible(entry.getValue().box)) visibleSections.add(entry);
            }
            // Verre et eau : seulement les sections visibles, triees du plus loin au plus proche.
            List<Map.Entry<SectionPos, SectionMesh>> translucentSections = new ArrayList<>(visibleSections);
            for (Map.Entry<SectionPos, SectionMesh> entry : translucentSections) {
                entry.getValue().cameraDistanceSqr = entry.getKey().distanceToSqr(currentRadius, camera);
            }
            translucentSections.sort(Comparator.comparingDouble(
                    (Map.Entry<SectionPos, SectionMesh> entry) -> entry.getValue().cameraDistanceSqr).reversed());
            for (RenderType renderType : DRAW_ORDER) {
                if (!flight && renderType == RenderType.translucent()) {
                    // Les mobs passent apres le terrain opaque, mais avant l'eau et le verre : leur profondeur
                    // masquerait sinon les animaux sous l'eau. Le placement est celui des maillages de cette image.
                    drawMobsBeforeWater(data, event, camera, terrain);
                }
                Iterable<Map.Entry<SectionPos, SectionMesh>> sections = renderType == RenderType.translucent()
                        ? translucentSections : visibleSections;
                renderType.setupRenderState();
                try {
                    // Planariums voisins : dans le repere du bocal, aussi en vol (ils bougent avec la structure).
                    NeighborPockets.drawLayer(renderType, jarPose, projectionMatrix, camera);
                    if (flight) {
                        // En vol : les sections du vol a la place du decor normal (et sans anneau lointain).
                        FlightView.drawLayer(renderType, projectionMatrix);
                        continue;
                    }
                    // L'anneau lointain d'abord : derriere le decor proche (ordre des couches translucides).
                    FarRing.drawLayer(renderType, poseStack.last().pose(), projectionMatrix);
                    for (Map.Entry<SectionPos, SectionMesh> entry : sections) {
                        VertexBuffer vertexBuffer = entry.getValue().buffers.get(renderType);
                        if (vertexBuffer == null || vertexBuffer.getFormat() == null) continue;
                        vertexBuffer.bind();
                        vertexBuffer.drawWithShader(poseStack.last().pose(),
                                projectionMatrix, RenderSystem.getShader());
                        VertexBuffer.unbind();
                    }
                } finally {
                    VertexBuffer.unbind();
                    renderType.clearRenderState();
                }
            }
            poseStack.popPose();
            if (lastInfo != null) SubLevelView.drawOutside(event,
                    lastInfo.origin().offset(currentRadius, currentRadius, currentRadius), false);
            try {
                CloudView.draw(poseStack.last().pose(), projectionMatrix, camera,
                        event.getPartialTick().getGameTimeDeltaPartialTick(false), lastInfo, currentRadius);
            } catch (Exception e) {
                LOGGER.debug("[Planarium View] Nuages ignores", e);
            }
        } finally {
            RenderSystem.setShaderFogStart(fogStart);
            RenderSystem.setShaderFogEnd(fogEnd);
            RenderSystem.setShaderFogShape(fogShape);
            RenderSystem.setShaderFogColor(fogColor[0], fogColor[1], fogColor[2], fogColor[3]);
        }
    }

    /** Tableaux, cadres et porte-armures du decor (des entites, pas des blocs : voir GiantEntities). */
    private static void drawEntities(StructureRenderData data, float partialTick, Vec3 camera, PoseStack poseStack, int radius,
                                     Matrix4f terrain) {
        OutsideViewPayload info = lastInfo;
        if (info == null || !(data.fakeRenderingWorld instanceof net.minecraft.world.level.Level fake)) {
            return;
        }
        int view = viewRadius();
        for (net.minecraft.world.entity.Entity entity : GiantEntities.entities(data, fake, info.template(), info.origin())) {
            if (!liveArea(entity.getX() - radius - 0.5, entity.getZ() - radius - 0.5, view + 1.0)
                    || !specialVisible(entity.getBoundingBoxForCulling().inflate(1.0), radius)) {
                continue;
            }
            poseStack.pushPose();
            poseStack.translate(-camera.x, -camera.y, -camera.z);
            poseStack.mulPose(terrain);
            poseStack.translate(-ARS_OFFSET * GIANT, -ARS_OFFSET * GIANT - Y_SHIFT, -ARS_OFFSET * GIANT);
            poseStack.scale(GIANT, GIANT, GIANT);
            poseStack.translate(-radius, -radius, -radius);
            try {
                GiantEntities.render(entity, fake, partialTick, poseStack, blockEntityBuffers,
                        sceneryEntityLight.get(entity));
            } catch (Exception e) {
                LOGGER.debug("[Planarium Panorama] Entite du decor ignoree", e);
            }
            poseStack.popPose();
        }
    }

    private static void drawMobsBeforeWater(StructureRenderData data, RenderLevelStageEvent event, Vec3 camera,
                                          Matrix4f terrain) {
        Minecraft client = Minecraft.getInstance();
        // AFTER_SKY precede l'initialisation vanilla de l'eclairage des entites. La meme configuration sera
        // reappliquee par LevelRenderer avant ses propres entites ; le terrain utilise sa lumiere precalculee.
        if (client.level != null && client.level.effects().constantAmbientLight()) {
            com.mojang.blaze3d.platform.Lighting.setupNetherLevel();
        } else {
            com.mojang.blaze3d.platform.Lighting.setupLevel();
        }
        if (blockEntityBuffers == null) {
            blockEntityBuffers = MultiBufferSource.immediate(new ByteBufferBuilder(262144));
        }
        GiantEntities.withCamera(event.getModelViewMatrix(), () -> {
            try {
                drawMobs(data, event.getPartialTick().getGameTimeDeltaPartialTick(false), camera,
                        new PoseStack(), currentRadius, terrain);
            } finally {
                blockEntityBuffers.endBatch();
            }
            SceneryWaterParticles.render(event);
        });
    }

    private static void drawMobs(StructureRenderData data, float partialTick, Vec3 camera, PoseStack poseStack, int radius,
                                 Matrix4f terrain) {
        OutsideViewPayload info = lastInfo;
        if (info == null || !(data.fakeRenderingWorld instanceof net.minecraft.world.level.Level fake)) return;
        int view = viewRadius();
        // Mobs du dehors : places pour cette image, une pile de matrices neuve par mob (un rendu modde qui plante en
        // plein milieu ne laisse pas la pile decalee pour les suivants).
        var flameRotation = com.matthieu.planariumview.client.scenery.entities.MobEffects.flameRotation(terrain,
                Minecraft.getInstance().gameRenderer.getMainCamera().rotation());
        PoseStack mobFrame = GiantEntities.copyPose(poseStack.last());
        mobFrame.translate(-camera.x, -camera.y, -camera.z);
        mobFrame.mulPose(terrain);
        mobFrame.translate(-ARS_OFFSET * GIANT, -ARS_OFFSET * GIANT - Y_SHIFT, -ARS_OFFSET * GIANT);
        mobFrame.scale(GIANT, GIANT, GIANT);
        mobFrame.translate(-radius, -radius, -radius);
        MobShadows.Frame shadows = MobShadows.frame(mobFrame.last().pose());
        for (net.minecraft.world.entity.Entity mob : OutsideMobs.prepare(partialTick, info.origin())) {
            if (!liveArea(mob.getX() - radius - 0.5, mob.getZ() - radius - 0.5, view + 1.0)
                    || !specialVisible(mob.getBoundingBoxForCulling().inflate(1.0), radius)) {
                continue;
            }
            PoseStack mobStack = GiantEntities.copyPose(mobFrame.last());
            try {
                int light;
                ColorCompat.begin(fake);
                try {
                    light = GiantEntities.packedLight(fake, mob, partialTick);
                } finally {
                    ColorCompat.end();
                }
                OutsideVehicles.render(mob, fake, partialTick, mobStack, blockEntityBuffers, light, flameRotation);
            } catch (Exception | LinkageError e) {
                OutsideMobs.broken(mob);
                LOGGER.debug("[Planarium Panorama] Mob du dehors ignore", e);
                continue;
            }
            try {
                MobShadows.render(shadows, mob, fake, mobStack, blockEntityBuffers);
            } catch (Exception | LinkageError e) {
                LOGGER.debug("[Planarium Panorama] Ombre d'un mob du dehors ignoree", e);
            }
        }
    }

    /** Tampon a part pour les blocs a rendu special (coffres, panneaux, etc.). */
    private static MultiBufferSource.BufferSource blockEntityBuffers;

    /**
     * Les coffres, panneaux, bannieres, tetes, etc. ne sont pas des modeles de blocs :
     * ils sont dessines a chaque image par leur "BlockEntityRenderer", ici agrandis 32 fois.
     */
    /** Champ de la camera pour le decor detaille, et pour ses blocs speciaux et entites (voir SceneryFrustum). */
    private static final SceneryFrustum NEAR_FRUSTUM = new SceneryFrustum();
    private static final SceneryFrustum SPECIAL_FRUSTUM = new SceneryFrustum();

    /**
     * Vrai si la boite (coordonnees du faux monde, autour du Planarium a {@code radius}) peut etre vue : elle est passee
     * dans le repere du decor geant comme les sommets (x 32,16, decalage d'Ars). Une boite infinie est toujours gardee.
     */
    private static boolean specialVisible(net.minecraft.world.phys.AABB box, int radius) {
        if (box == null || box.isInfinite()) return true;
        float offset = -ARS_OFFSET * GIANT;
        return SPECIAL_FRUSTUM.visible(new float[]{
                (float) (box.minX - radius) * GIANT + offset, (float) (box.minY - radius) * GIANT + offset - Y_SHIFT,
                (float) (box.minZ - radius) * GIANT + offset, (float) (box.maxX - radius) * GIANT + offset,
                (float) (box.maxY - radius) * GIANT + offset - Y_SHIFT, (float) (box.maxZ - radius) * GIANT + offset});
    }

    private static void drawBlockEntities(StructureRenderData data, RenderLevelStageEvent event) {
        if (data.fakeRenderingWorld == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        BlockEntityRenderDispatcher dispatcher = mc.getBlockEntityRenderDispatcher();
        if (blockEntityBuffers == null) {
            blockEntityBuffers = MultiBufferSource.immediate(new ByteBufferBuilder(262144));
        }
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 camera = event.getCamera().getPosition();
        int radius = currentRadius;
        int view = viewRadius();
        BlockPos planarium = new BlockPos(radius, radius, radius);
        BlockPos above = planarium.above();

        float fogStart = RenderSystem.getShaderFogStart();
        float fogEnd = RenderSystem.getShaderFogEnd();
        com.mojang.blaze3d.shaders.FogShape fogShape = RenderSystem.getShaderFogShape();
        applySceneryFog();
        try {
            // Champ de la camera dans le repere du decor geant : vue, camera et placement Sable, comme au dessin.
            // Placement Sable lu une seule fois : le test et le dessin utilisent exactement la meme position (il bouge
            // avec le temps sur un navire en mouvement).
            PoseStack placement = new PoseStack();
            if (lastInfo != null) SubLevelView.terrain(placement, lastInfo.origin().offset(radius, radius, radius));
            Matrix4f terrain = new Matrix4f(placement.last().pose());
            PoseStack frame = new PoseStack();
            frame.mulPose(event.getModelViewMatrix());
            frame.translate(-camera.x, -camera.y, -camera.z);
            frame.mulPose(terrain);
            SPECIAL_FRUSTUM.update(frame.last().pose(), new Matrix4f(event.getProjectionMatrix()));
            PoseStack poseStack = new PoseStack();
            for (BlockEntity blockEntity : data.fakeRenderingWorld.blockEntityMap.values()) {
                BlockPos pos = blockEntity.getBlockPos();
                if (pos.equals(planarium) || pos.equals(above)) {
                    continue;
                }
                if (!liveArea(pos.getX() - radius, pos.getZ() - radius, view)) {
                    continue;
                }
                if (blockEntity instanceof PlanariumTile planariumTile) {
                    // Evite de dessiner une miniature dans la miniature.
                    planariumTile.key = null;
                }
                BlockEntityRenderer<BlockEntity> renderer = dispatcher.getRenderer(blockEntity);
                // Lanterne de Swinging Lanterns : immobile dans le decor (voir SwingingLanternsCompat).
                if (renderer == null || com.matthieu.planariumview.compat.swinging_lanterns.SwingingLanternsCompat.skip(renderer)) {
                    continue;
                }
                // Hors du champ de la camera : pas dessine. Boite de rendu du renderer (NeoForge), avec un bloc de
                // marge pour les rendus qui depassent un peu (bannieres, panneaux, machines de Create).
                net.minecraft.world.phys.AABB renderBox;
                try {
                    renderBox = renderer.getRenderBoundingBox(blockEntity);
                } catch (Exception e) {
                    renderBox = null;
                }
                if (renderBox != null && !renderBox.isInfinite() && !specialVisible(renderBox.inflate(1.0), radius)) {
                    continue;
                }
                poseStack.pushPose();
                poseStack.translate(-camera.x, -camera.y, -camera.z);
                poseStack.mulPose(terrain);
                poseStack.translate(-ARS_OFFSET * GIANT, -ARS_OFFSET * GIANT - Y_SHIFT, -ARS_OFFSET * GIANT);
                poseStack.scale(GIANT, GIANT, GIANT);
                poseStack.translate(pos.getX() - radius, pos.getY() - radius, pos.getZ() - radius);
                Integer stored = blockEntityLight.get(pos);
                int light = stored != null ? stored : LevelRenderer.getLightColor(data.fakeRenderingWorld, pos);
                try {
                    if (!AmbersolCompat.render(blockEntity, partialTick, poseStack, blockEntityBuffers)) {
                        // Couleur de Contraption Lights gardee meme si le rendu recalcule la lumiere (voir HueKeepingBuffers).
                        com.matthieu.planariumview.compat.create.CreateCopies.render(renderer, blockEntity, data.fakeRenderingWorld,
                                partialTick, poseStack, HueKeepingBuffers.of(blockEntityBuffers, light), light, OverlayTexture.NO_OVERLAY);
                    }
                } catch (Exception e) {
                    LOGGER.debug("[Planarium View] Bloc special ignore", e);
                }
                poseStack.popPose();
            }
            drawEntities(data, partialTick, camera, poseStack, radius, terrain);
            blockEntityBuffers.endBatch();
        } finally {
            RenderSystem.setShaderFogStart(fogStart);
            RenderSystem.setShaderFogEnd(fogEnd);
            RenderSystem.setShaderFogShape(fogShape);
        }
    }
}

package com.matthieu.planariumview.client.scenery;

import com.hollingsworth.arsnouveau.client.renderer.LiquidBlockVertexConsumer;
import com.hollingsworth.arsnouveau.client.renderer.tile.PlanariumRenderer;
import com.hollingsworth.arsnouveau.client.renderer.tile.PlanariumRenderer.StructureRenderData;
import com.hollingsworth.arsnouveau.client.renderer.world.CulledStatePos;
import com.matthieu.planariumview.compat.contraption_lights.ColorCompat;
import com.matthieu.planariumview.compat.puddle_flood.PuddlesCompat;
import com.matthieu.planariumview.compat.snow_real_magic.SnowRealMagicCompat;
import com.matthieu.planariumview.network.OutsideViewPayload;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.matthieu.planariumview.client.scenery.ClientView.ARS_OFFSET;
import static com.matthieu.planariumview.client.scenery.ClientView.GIANT;
import static com.matthieu.planariumview.client.scenery.ClientView.Y_SHIFT;
import static com.matthieu.planariumview.client.scenery.SceneryState.currentRadius;

/** Construction, invalidation et buffers des sections du decor detaille. */
final class SceneryMeshes {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClientView.class);
    private static final int SECTION_SIZE = 16;

    /** Blocs du decor par section, gardes pour corriger le decor sans le reconstruire. */
    static Map<SectionPos, List<CulledStatePos>> currentSectionStates = new HashMap<>();
    static final Map<SectionPos, SectionMesh> sectionMeshes = new HashMap<>();
    static Map<SectionPos, List<CulledStatePos>> pendingSectionStates = Map.of();
    static final ArrayDeque<SectionPos> pendingSections = new ArrayDeque<>();
    static final Set<SectionPos> pendingSectionSet = new HashSet<>();
    /** Failed rebuilds still display old geometry; it must not be used to prove occlusion of new captures. */
    static final Set<SectionPos> failedSections = new HashSet<>();
    static StructureRenderData pendingSectionData;
    static boolean forceAllSections;
    static byte[] renderedLight;
    static byte[] renderedBiomes;
    /** Coin du gabarit dans le monde au dernier rendu : s'il bouge, les biomes et leurs couleurs changent partout. */
    static BlockPos renderedOrigin;
    static List<ResourceLocation> renderedBiomePalette;

    /** Rayon de vue avec lequel le decor actuel a ete construit. */
    static int builtViewRadius = -1;

    record SectionPos(int x, int y, int z) {
        public static SectionPos of(BlockPos pos) {
            return new SectionPos(Math.floorDiv(pos.getX(), SECTION_SIZE),
                    Math.floorDiv(pos.getY(), SECTION_SIZE), Math.floorDiv(pos.getZ(), SECTION_SIZE));
        }

        double distanceToSqr(int radius, Vec3 camera) {
            double dx = (x * SECTION_SIZE + SECTION_SIZE / 2.0 - radius - ARS_OFFSET) * GIANT - camera.x;
            double dy = (y * SECTION_SIZE + SECTION_SIZE / 2.0 - radius - ARS_OFFSET) * GIANT - Y_SHIFT - camera.y;
            double dz = (z * SECTION_SIZE + SECTION_SIZE / 2.0 - radius - ARS_OFFSET) * GIANT - camera.z;
            return dx * dx + dy * dy + dz * dz;
        }

        double distanceFromPlanariumSqr(int radius) {
            double xCenter = x * SECTION_SIZE + SECTION_SIZE / 2.0 - radius;
            double yCenter = y * SECTION_SIZE + SECTION_SIZE / 2.0 - radius;
            double zCenter = z * SECTION_SIZE + SECTION_SIZE / 2.0 - radius;
            return xCenter * xCenter + yCenter * yCenter + zCenter * zCenter;
        }
    }

    static final class SectionMesh {
        final Map<RenderType, VertexBuffer> buffers = new HashMap<>();
        /** Boite des sommets de la section, pour le frustum culling (voir SceneryFrustum). */
        final float[] box = SceneryFrustum.emptyBox();
        final Map<RenderType, MeshData.SortState> sortStates = new HashMap<>();
        final Map<RenderType, com.mojang.blaze3d.vertex.ByteBufferBuilder> sortBuffers = new HashMap<>();
        double cameraDistanceSqr;

        public void sort(Vec3 camera) {
            for (Map.Entry<RenderType, MeshData.SortState> entry : sortStates.entrySet()) {
                RenderType type = entry.getKey();
                com.mojang.blaze3d.vertex.ByteBufferBuilder builder = sortBuffers.computeIfAbsent(type,
                        ignored -> new com.mojang.blaze3d.vertex.ByteBufferBuilder(4096));
                VertexBuffer buffer = buffers.get(type);
                if (buffer == null) continue;
                builder.clear();
                var sorted = entry.getValue().buildSortedIndexBuffer(builder,
                        VertexSorting.byDistance((float) camera.x, (float) camera.y, (float) camera.z));
                buffer.bind();
                buffer.uploadIndexBuffer(sorted);
                VertexBuffer.unbind();
            }
        }

        public void close() {
            for (VertexBuffer buffer : buffers.values()) buffer.close();
            for (var builder : sortBuffers.values()) builder.close();
            buffers.clear();
            sortBuffers.clear();
            sortStates.clear();
        }
    }

    private SceneryMeshes() {
    }

    static Map<SectionPos, List<CulledStatePos>> groupSections(List<CulledStatePos> states) {
        Map<SectionPos, List<CulledStatePos>> sections = new HashMap<>();
        for (CulledStatePos state : states) {
            sections.computeIfAbsent(SectionPos.of(state.pos), ignored -> new ArrayList<>()).add(state);
        }
        return sections;
    }

    static Set<SectionPos> changedSections(StructureRenderData previous, StructureRenderData current,
                                                    Map<SectionPos, List<CulledStatePos>> sections,
                                                    byte[] light, OutsideViewPayload info, int radius) {
        Set<SectionPos> changed = new HashSet<>();
        BlockPos origin = info == null ? null : info.origin();
        if (forceAllSections || previous == null
                || !java.util.Objects.equals(origin, renderedOrigin)
                || !markLightChanges(changed, light, renderedLight, radius * 2 + 3)
                || !markBiomeChanges(changed, info, renderedBiomePalette, renderedBiomes, radius)) {
            changed.clear();
            changed.addAll(sections.keySet());
            return changed;
        }

        Map<BlockPos, BlockState> oldStates = new HashMap<>();
        for (CulledStatePos state : previous.statePosCache) oldStates.put(state.pos, state.state);
        for (CulledStatePos state : current.statePosCache) {
            BlockState old = oldStates.remove(state.pos);
            if (old == null || !old.equals(state.state)) markAffectedSections(changed, state.pos);
        }
        for (BlockPos removed : oldStates.keySet()) markAffectedSections(changed, removed);
        changed.retainAll(sections.keySet());
        return changed;
    }

    /** Les faces cachees et l'AO lisent les voisins; aux aretes, on invalide aussi les sections adjacentes. */
    static void markAffectedSections(Set<SectionPos> changed, BlockPos pos) {
        int sx = Math.floorDiv(pos.getX(), SECTION_SIZE);
        int sy = Math.floorDiv(pos.getY(), SECTION_SIZE);
        int sz = Math.floorDiv(pos.getZ(), SECTION_SIZE);
        int[] dx = sectionOffsets(pos.getX());
        int[] dy = sectionOffsets(pos.getY());
        int[] dz = sectionOffsets(pos.getZ());
        for (int x : dx) for (int y : dy) for (int z : dz) changed.add(new SectionPos(sx + x, sy + y, sz + z));
    }

    /**
     * Lumiere : seules les sections proches d'une case dont la lumiere a change sont refaites.
     * Le lissage (AO) et notre getBrightness lisent les cases voisines, d'ou la marge de 3 blocs.
     * Faux si les tableaux ne sont pas comparables (taille differente, lumiere activee ou coupee) : tout refaire.
     */
    static boolean markLightChanges(Set<SectionPos> changed, byte[] light, byte[] previous, int size) {
        if (java.util.Arrays.equals(light, previous)) return true;
        if (light == null || previous == null || light.length != previous.length
                || light.length != size * size * size) return false;
        final int margin = 3;
        for (int i = 0; i < light.length; i++) {
            if (light[i] == previous[i]) continue;
            int z = i % size;
            int y = (i / size) % size;
            int x = i / (size * size);
            // Le tableau a une marge d'un bloc : la case 0 correspond a la position -1 du gabarit.
            markBox(changed, x - 1 - margin, y - 1 - margin, z - 1 - margin,
                    x - 1 + margin, y - 1 + margin, z - 1 + margin);
        }
        return true;
    }

    /**
     * Biomes : seules les sections proches d'un cube de biome (4 x 4 x 4) qui a change sont refaites.
     * Le melange des couleurs lit 2 blocs autour, d'ou la marge. Les biomes sont compares par nom,
     * pour qu'une palette simplement reordonnee ne fasse pas tout refaire.
     * Faux si les grilles ne sont pas comparables : tout refaire.
     */
    private static boolean markBiomeChanges(Set<SectionPos> changed, OutsideViewPayload info,
                                            List<ResourceLocation> previousPalette, byte[] previous, int radius) {
        List<ResourceLocation> palette = info == null ? null : info.biomePalette();
        byte[] biomes = info == null ? null : info.biomes();
        if (java.util.Objects.equals(palette, previousPalette) && java.util.Arrays.equals(biomes, previous)) return true;
        if (palette == null || biomes == null || previousPalette == null || previous == null
                || biomes.length != previous.length) return false;
        BiomeGrid grid = BiomeGrid.of(info.origin(), radius * 2 + 1);
        if (biomes.length != grid.volume()) return false;
        BlockPos origin = info.origin();
        final int margin = BiomeGrid.MARGIN + 1;
        for (int i = 0; i < biomes.length; i++) {
            ResourceLocation now = paletteEntry(palette, biomes[i]);
            ResourceLocation before = paletteEntry(previousPalette, previous[i]);
            if (java.util.Objects.equals(now, before)) continue;
            int qz = i % grid.sizeZ();
            int qy = (i / grid.sizeZ()) % grid.sizeY();
            int qx = i / (grid.sizeZ() * grid.sizeY());
            // Coin du cube de biome dans le monde, ramene aux positions du gabarit.
            int minX = (grid.minQx() + qx) * 4 - origin.getX();
            int minY = (grid.minQy() + qy) * 4 - origin.getY();
            int minZ = (grid.minQz() + qz) * 4 - origin.getZ();
            markBox(changed, minX - margin, minY - margin, minZ - margin,
                    minX + 3 + margin, minY + 3 + margin, minZ + 3 + margin);
        }
        return true;
    }

    private static ResourceLocation paletteEntry(List<ResourceLocation> palette, byte index) {
        int i = index & 0xFF;
        return i < palette.size() ? palette.get(i) : null;
    }

    /** Marque toutes les sections qui touchent la boite (positions du gabarit, bornes incluses). */
    static void markBox(Set<SectionPos> changed, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        int sx0 = Math.floorDiv(minX, SECTION_SIZE), sx1 = Math.floorDiv(maxX, SECTION_SIZE);
        int sy0 = Math.floorDiv(minY, SECTION_SIZE), sy1 = Math.floorDiv(maxY, SECTION_SIZE);
        int sz0 = Math.floorDiv(minZ, SECTION_SIZE), sz1 = Math.floorDiv(maxZ, SECTION_SIZE);
        for (int x = sx0; x <= sx1; x++) {
            for (int y = sy0; y <= sy1; y++) {
                for (int z = sz0; z <= sz1; z++) {
                    changed.add(new SectionPos(x, y, z));
                }
            }
        }
    }

    private static int[] sectionOffsets(int coordinate) {
        int edge = Math.floorMod(coordinate, SECTION_SIZE);
        if (edge == 0) return new int[]{-1, 0};
        if (edge == SECTION_SIZE - 1) return new int[]{0, 1};
        return new int[]{0};
    }

    private static SectionMesh buildSection(StructureRenderData data, int radius,
                                            List<CulledStatePos> states, Vec3 camera) {
        data.bufferBuilders.clear();
        SectionMesh section = new SectionMesh();
        try {
            ColorCompat.begin(data.fakeRenderingWorld);
            try {
                tesselate(data, radius, states);
            } finally {
                ColorCompat.end();
            }
            Vector3f sortPos = new Vector3f((float) camera.x, (float) camera.y, (float) camera.z);
            for (Map.Entry<RenderType, BufferBuilder> entry : data.bufferBuilders.entrySet()) {
                RenderType type = entry.getKey();
                MeshData mesh = entry.getValue().build();
                if (mesh == null) continue;
                // Sommets sur une grille fixe : pas de fentes entre les blocs voisins (voir GridSnap).
                GridSnap.snapMesh(mesh, type != RenderType.translucent());
                // Boite des vrais sommets avant l'envoi a la carte graphique (upload libere le maillage).
                SceneryFrustum.include(section.box, mesh);
                if (type == RenderType.translucent()) {
                    section.sortStates.put(type, mesh.sortQuads(data.getByteBuffer(type),
                            VertexSorting.byDistance(sortPos)));
                }
                VertexBuffer buffer = new ClippedVertexBuffer();
                buffer.bind();
                buffer.upload(mesh);
                VertexBuffer.unbind();
                section.buffers.put(type, buffer);
            }
            return section;
        } catch (RuntimeException e) {
            section.close();
            throw e;
        } finally {
            VertexBuffer.unbind();
            data.bufferBuilders.clear();
            for (var builder : data.builders.values()) builder.clear();
        }
    }

    /**
     * Temps maximal par image pour retesseler des sections (3 ms). Une section commencee va toujours jusqu'au bout,
     * donc une section tres chargee peut depasser ce temps a elle seule ; on en construit toujours au moins une.
     */
    private static final long SECTION_BUDGET_NANOS = 3_000_000L;

    /** Construit des sections tant que le budget de l'image le permet; les meshes precedents restent visibles jusque-la. */
    static void buildPendingSections(Vec3 camera) {
        long start = System.nanoTime();
        do {
            buildNextSection(camera);
        } while (pendingSectionData != null && !pendingSections.isEmpty()
                && System.nanoTime() - start < SECTION_BUDGET_NANOS);
    }

    private static void buildNextSection(Vec3 camera) {
        if (pendingSectionData == null || pendingSections.isEmpty()) return;
        SectionPos key = pendingSections.removeFirst();
        pendingSectionSet.remove(key);
        List<CulledStatePos> states = pendingSectionStates.get(key);
        if (pendingSectionData.fakeRenderingWorld instanceof LitRenderingWorld lit) {
            states = PuddlesCompat.sectionStates(lit, key.x(), key.y(), key.z(), states, currentRadius, builtViewRadius);
        }
        SectionMesh old = sectionMeshes.remove(key);
        if (states == null || states.isEmpty()) {
            if (old != null) old.close();
            failedSections.remove(key);
        } else {
            try {
                SectionMesh replacement = buildSection(pendingSectionData, currentRadius, states, camera);
                sectionMeshes.put(key, replacement);
                if (old != null) old.close();
                failedSections.remove(key);
            } catch (Exception e) {
                failedSections.add(key);
                if (old != null) sectionMeshes.put(key, old);
                LOGGER.warn("[Planarium View] Echec du retesselage de la section {}; ancienne vue conservee", key, e);
            }
        }
        if (pendingSections.isEmpty()) {
            pendingSectionData = null;
            pendingSectionStates = Map.of();
        }
    }

    private static void tesselate(StructureRenderData data, int radius, List<CulledStatePos> statePositions) {
        PoseStack matrix = new PoseStack();
        BlockRenderDispatcher dispatcher = Minecraft.getInstance().getBlockRenderer();
        ModelBlockRenderer modelRenderer = dispatcher.getModelRenderer();
        RandomSource random = RandomSource.create();
        LitRenderingWorld hiding = data.fakeRenderingWorld instanceof LitRenderingWorld lit ? lit : null;
        if (hiding != null) {
            hiding.hideJarCells(new BlockPos(radius, radius, radius));
        }
        try {
            tesselateStates(data, radius, statePositions, dispatcher, modelRenderer, random, matrix);
        } finally {
            if (hiding != null) {
                hiding.showJarCells();
            }
        }
    }

    private static void tesselateStates(StructureRenderData data, int radius, List<CulledStatePos> statePositions,
                                        BlockRenderDispatcher dispatcher, ModelBlockRenderer modelRenderer,
                                        RandomSource random, PoseStack matrix) {
        for (CulledStatePos pos : statePositions) {
            BlockState renderState = data.fakeRenderingWorld.getBlockState(pos.pos);
            FluidState fluidState = pos.state.getFluidState();
            boolean isModel = PlanariumRenderer.isModelRender(pos.state);
            if (renderState.isAir() || !(isModel || !fluidState.isEmpty())) {
                continue;
            }
            if (pos.needsUpdate()) {
                PlanariumRenderer.updateCulling(pos, data.fakeRenderingWorld);
                pos.setNeedsUpdate(false);
            }
            if (pos.shouldSkipRender()) {
                continue;
            }

            BakedModel model = dispatcher.getBlockModel(renderState);
            model = NeighborPockets.model(model, renderState, pos.pos.offset(-radius, -radius, -radius));
            boolean neighbor = model instanceof NeighborPlanariumModel;
            // Les morceaux de modele qui depassent dans la case du Planarium entreraient dans le bocal.
            BlockPos planariumCell = new BlockPos(radius, radius, radius);
            if (JarClippedModel.touches(pos.pos, planariumCell)) {
                model = new JarClippedModel(model, planariumCell.subtract(pos.pos));
            }
            matrix.pushPose();
            // Coordonnees de poche = (position relative au Planarium - decalage d'Ars) * 32
            matrix.translate(-ARS_OFFSET * GIANT, -ARS_OFFSET * GIANT - Y_SHIFT, -ARS_OFFSET * GIANT);
            matrix.scale(GIANT, GIANT, GIANT);
            matrix.translate(pos.pos.getX() - radius, pos.pos.getY() - radius, pos.pos.getZ() - radius);

            if (!fluidState.isEmpty()) {
                RenderType fluidType = net.minecraft.client.renderer.ItemBlockRenderTypes.getRenderLayer(fluidState);
                BufferBuilder fluidBuilder = data.bufferBuilders.computeIfAbsent(fluidType,
                        rt -> new BufferBuilder(data.getByteBuffer(rt), rt.mode(), rt.format()));
                try {
                    dispatcher.renderLiquid(pos.pos, data.fakeRenderingWorld,
                            new LiquidBlockVertexConsumer(fluidBuilder, matrix, pos.pos), pos.state, fluidState);
                } catch (Exception e) {
                    LOGGER.debug("[Planarium View] Liquide ignore", e);
                }
            }

            for (RenderType renderType : model.getRenderTypes(renderState, random, ModelData.EMPTY)) {
                RenderType target = renderType;
                // Ars passe les blocs pleins "cutout" en translucide pour la miniature. Dans le decor geant, on garde le
                // cutout : en translucide, les pixels transparents (racines de Spelunkery...) laissaient voir le ciel.
                final RenderType bufferType = target;
                BufferBuilder builder = data.bufferBuilders.computeIfAbsent(bufferType,
                        rt -> new BufferBuilder(data.getByteBuffer(rt), rt.mode(), rt.format()));
                if (renderState.getRenderShape() != RenderShape.MODEL) {
                    continue;
                }
                // Sommets mis sur une grille fixe a la fin de la section (voir GridSnap.snapMesh).
                VertexConsumer snapped = builder;
                if (renderState.getFluidState().isEmpty() && isModel && !(model instanceof JarClippedModel)) {
                    // Bloc couvert de neige de Snow! Real Magic : le vrai bloc, puis la neige (voir SnowRealMagicCompat).
                    final CulledStatePos culled = pos;
                    final VertexConsumer snowTarget = snapped;
                    final RenderType layer = renderType;
                    boolean snowy = SnowRealMagicCompat.render(data.fakeRenderingWorld, renderState, pos.pos, renderType, matrix,
                            (partState, partModel, pose) -> PlanariumRenderer.tesselateBlock(culled, modelRenderer,
                                    data.fakeRenderingWorld, partModel, partState, culled.pos, pose, snowTarget, random,
                                    partState.getSeed(culled.pos), OverlayTexture.NO_OVERLAY, ModelData.EMPTY, layer));
                    if (snowy) {
                        continue;
                    }
                }
                // Un bloc aquatique ou waterlogged a aussi un modele, en plus de son liquide.
                if (isModel) {
                    try {
                        // Couleur de Contraption Lights prise devant chaque face (voir FaceHueConsumer).
                        com.mojang.blaze3d.vertex.VertexConsumer faces = com.matthieu.planariumview.compat.contraption_lights.ColorCompat.building()
                                ? new FaceHueConsumer(snapped, data.fakeRenderingWorld, pos.pos) : snapped;
                        if (neighbor) faces = NeighborPlanariumModel.light(faces, snapped);
                        PlanariumRenderer.tesselateBlock(pos, modelRenderer, data.fakeRenderingWorld, model, renderState,
                                pos.pos, matrix, faces, random, renderState.getSeed(pos.pos),
                                OverlayTexture.NO_OVERLAY, ModelData.EMPTY, renderType);
                    } catch (Exception e) {
                        LOGGER.debug("[Planarium View] Bloc ignore", e);
                    }
                }
            }
            matrix.popPose();
        }
    }
}

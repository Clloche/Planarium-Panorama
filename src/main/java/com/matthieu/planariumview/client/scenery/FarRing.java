package com.matthieu.planariumview.client.scenery;

import com.hollingsworth.arsnouveau.client.renderer.LiquidBlockVertexConsumer;
import com.matthieu.planariumview.compat.ColorCompat;
import com.matthieu.planariumview.config.Config;
import com.matthieu.planariumview.network.FarRingPayload;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Anneau lointain du decor geant (voir FarRingServer) : une photo figee des blocs visibles au-dela du rayon de vue.
 * Seulement des blocs : pas de blocs speciaux animes, de particules, de sons ni d'entites. Construit section par
 * section avec un petit budget par image, et dessine avec le decor proche. Cache quand il ne correspond plus au decor
 * proche (Planarium deplace sur un sous-niveau qui bouge).
 */
public final class FarRing {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long BUDGET_NANOS = 2_000_000L;

    private static ResourceLocation pocket;
    private static BlockPos center;
    private static int near;
    private static int far;
    private static int height;
    private static FarWorld world;
    /** Blocs de chaque section a construire (cle : section dans la boite de l'anneau). */
    private static Map<Long, List<BlockPos>> pendingBlocks = Map.of();
    private static final ArrayDeque<Long> pending = new ArrayDeque<>();
    /** Section construite : ses tampons par couche et la boite de ses sommets (voir SceneryFrustum). */
    private record Built(Map<RenderType, VertexBuffer> buffers, float[] box) {
        void close() {
            buffers.values().forEach(VertexBuffer::close);
        }
    }

    private static Map<Long, Built> sections = new HashMap<>();
    /** Sections de la nouvelle photo, en construction ; l'ancienne reste dessinee jusqu'a la fin (rafraichissement). */
    private static Map<Long, Built> building = new HashMap<>();
    /** Champ de la camera, et sections dedans pour cette image (communes a toutes les couches). */
    private static final SceneryFrustum frustum = new SceneryFrustum();
    private static final List<Built> visibleSections = new ArrayList<>();
    /** Vrai pendant un rafraichissement : les nouvelles sections vont dans {@code building}, sinon directement. */
    private static boolean replacing;
    /**
     * Rayon du dernier anneau recu pour ce bocal, garde quand l'anneau est cache (vaisseau qui bouge) : la distance de
     * dessin du jeu reste la meme (voir ClientView.sceneryDepthFar). La changer a chaque apparition de l'anneau
     * provoquait une saccade.
     */
    static int knownRadius;
    private static final Map<RenderType, ByteBufferBuilder> builders = new IdentityHashMap<>();

    /**
     * Preparation des anneaux recus, hors du fil de rendu : remplir les tables du monde de l'anneau (blocs, voisins,
     * lumiere) prenait jusqu'a 60 ms d'un coup. Un seul fil, en arriere-plan.
     */
    private static final java.util.concurrent.ExecutorService LOADER = java.util.concurrent.Executors.newSingleThreadExecutor(
            runnable -> {
                Thread thread = new Thread(runnable, "Planarium Panorama - anneau lointain");
                thread.setDaemon(true);
                // Travail de fond : laisse passer le rendu quand le processeur est charge (comme ModernFix).
                thread.setPriority(Thread.NORM_PRIORITY - 1);
                return thread;
            });
    /** Numero de l'anneau attendu : un resultat d'un autre numero (anneau remplace, sortie du bocal) est jete. */
    private static volatile int loadVersion;

    /** Diagnostic detaille des sections lentes (temps par type de bloc) : -Dplanariumview.diagSections=true. */
    private static final boolean DIAG_SECTIONS = Boolean.getBoolean("planariumview.diagSections");
    /** Temps des etapes de la derniere construction (voir build), pour le message des sections lentes. */
    private static long lastTessNanos, lastMeshNanos, lastSortNanos, lastUploadNanos;
    private static Map<net.minecraft.world.level.block.Block, long[]> lastBlockNanos = Map.of();

    private FarRing() {
    }

    public static void receive(FarRingPayload payload) {
        knownRadius = payload.isClear() ? 0 : payload.far();
        boolean sameArea = world != null && !payload.isClear() && payload.pocketDim().equals(pocket)
                && payload.center().equals(center) && payload.near() == near && payload.far() == far
                && payload.height() == height;
        replacing = sameArea;
        if (!sameArea) {
            clear();
        } else {
            // Meme zone (rafraichissement) : l'ancien anneau reste visible pendant la construction du nouveau.
            closeAll(building);
            pending.clear();
        }
        if (payload.isClear()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        // Sur le fil de rendu : seulement les biomes (registres du monde du client). Le reste en arriere-plan.
        List<Holder<Biome>> biomes = biomeHolders(payload.data());
        BlockState[] palette = blockPalette(payload.data());
        int version = ++loadVersion;
        boolean replacingArea = replacing;
        long start = System.nanoTime();
        LOADER.execute(() -> {
            try {
                Map<Long, List<BlockPos>> bySection = new HashMap<>();
                FarWorld next = fillTables(FarWorld.ring(payload.far(), payload.height(), payload.center()), payload.data(),
                        palette, () -> version != loadVersion, pos -> bySection.computeIfAbsent(BlockPos.asLong(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4),
                                ignored -> new ArrayList<>()).add(pos));
                next.setBiomes(biomes, payload.data().biomes(), payload.data().quarts());
                long prepared = System.nanoTime() - start;
                mc.execute(() -> {
                    if (version != loadVersion || Minecraft.getInstance().level == null) return;
                    install(payload, next, bySection, replacingArea);
                    LOGGER.info("[Planarium Panorama] Anneau lointain recu : {} blocs, preparation {} ms (hors rendu)",
                            payload.data().blocks().length, prepared / 1_000_000L);
                });
            } catch (java.util.concurrent.CancellationException ignored) {
                // Anneau remplace ou sortie du bocal pendant la preparation : le fil passe au suivant.
            } catch (RuntimeException e) {
                LOGGER.warn("[Planarium Panorama] Anneau lointain illisible", e);
                mc.execute(() -> {
                    if (version == loadVersion) clear();
                });
            }
        });
    }

    /** Sur le fil de rendu : installe un anneau prepare (voir receive). */
    private static void install(FarRingPayload payload, FarWorld next, Map<Long, List<BlockPos>> bySection,
                                boolean replacingArea) {
        replacing = replacingArea;
        pocket = payload.pocketDim();
        center = payload.center();
        near = payload.near();
        far = payload.far();
        height = payload.height();
        world = next;
        pendingBlocks = bySection;
        // Les sections les plus proches du Planarium d'abord.
        List<Long> order = new ArrayList<>(bySection.keySet());
        int middle = far >> 4;
        int middleY = height >> 4;
        order.sort(java.util.Comparator.comparingInt(key -> Math.abs(BlockPos.getX(key) - middle)
                + Math.abs(BlockPos.getY(key) - middleY) + Math.abs(BlockPos.getZ(key) - middle)));
        pending.addAll(order);
        if (!replacing) {
            ClientView.farRingArrived();
        }
    }

    /** Remplit {@code next} avec des blocs visibles recus ; {@code sink} recoit la position locale de chaque bloc. */
    static FarWorld fill(FarWorld next, FarRingPayload.Data data, java.util.function.Consumer<BlockPos> sink) {
        fillTables(next, data, blockPalette(data), () -> false, sink);
        if (Minecraft.getInstance().level != null) {
            next.setBiomes(biomeHolders(data), data.biomes(), data.quarts());
        }
        return next;
    }

    /** Etats de blocs de la palette recue, resolus sur le fil de rendu (le travailleur ne lit aucun registre). */
    private static BlockState[] blockPalette(FarRingPayload.Data data) {
        int[] ids = data.palette();
        BlockState[] palette = new BlockState[ids.length];
        for (int i = 0; i < palette.length; i++) {
            palette[i] = net.minecraft.world.level.block.Block.stateById(ids[i]);
        }
        return palette;
    }

    /** Biomes de la palette recue, lus dans les registres du monde du client (fil de rendu). */
    private static List<Holder<Biome>> biomeHolders(FarRingPayload.Data data) {
        List<Holder<Biome>> biomes = new ArrayList<>();
        var level = Minecraft.getInstance().level;
        if (level == null) return biomes;
        var biomeRegistry = level.registryAccess().registryOrThrow(Registries.BIOME);
        for (ResourceLocation id : data.biomePalette()) {
            biomes.add(biomeRegistry.getHolder(ResourceKey.create(Registries.BIOME, id)).orElse(null));
        }
        return biomes;
    }

    /**
     * Tables des blocs, voisins pleins, eau et lumiere, a partir d'une palette deja resolue : ne lit aucun registre, peut
     * tourner hors du fil de rendu. {@code cancelled} : arret anticipe d'une preparation perimee.
     */
    private static FarWorld fillTables(FarWorld next, FarRingPayload.Data data, BlockState[] palette,
                                       java.util.function.BooleanSupplier cancelled,
                                       java.util.function.Consumer<BlockPos> sink) {
        int[] blocks = data.blocks();
        int[] info = data.info();
        int[] lightPos = data.lightPos();
        byte[] light = data.light();
        if (blocks.length != info.length || lightPos.length != light.length) {
            throw new IllegalArgumentException("Tailles incoherentes");
        }
        Direction[] directions = Direction.values();
        for (int i = 0; i < blocks.length; i++) {
            // Anneau perime (remplace, sortie du bocal) : on arrete tout de suite au lieu de finir pour rien.
            if ((i & 4095) == 0 && cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
            int packed = blocks[i];
            int paletteId = info[i] & 0xFFFF;
            if (paletteId >= palette.length) continue;
            BlockPos pos = new BlockPos(FarRingPayload.unpackX(packed), FarRingPayload.unpackY(packed), FarRingPayload.unpackZ(packed));
            next.states.put(pos.asLong(), palette[paletteId]);
            next.noteTop(pos.getX(), pos.getY(), pos.getZ());
            int solid = info[i] >>> 16 & 0x3F;
            int water = info[i] >>> 22 & 0x3F;
            for (Direction direction : directions) {
                int bit = 1 << direction.get3DDataValue();
                if ((solid & bit) != 0) {
                    next.solid.add(pos.relative(direction).asLong());
                } else if ((water & bit) != 0) {
                    next.water.add(pos.relative(direction).asLong());
                } else {
                    continue;
                }
                // Solide ou eau deduits : connus aussi, parfois dans la colonne voisine.
                next.noteTop(pos.getX() + direction.getStepX(), pos.getY() + direction.getStepY(),
                        pos.getZ() + direction.getStepZ());
            }
            sink.accept(pos);
        }
        for (int i = 0; i < lightPos.length; i++) {
            if ((i & 4095) == 0 && cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
            int packed = lightPos[i];
            next.light.put(BlockPos.asLong(FarRingPayload.unpackX(packed), FarRingPayload.unpackY(packed),
                    FarRingPayload.unpackZ(packed)), light[i]);
        }
        return next;
    }

    /**
     * Bloc de l'anneau a cette position relative au Planarium (pour le bord du decor proche), ou null s'il n'est pas
     * connu (pas d'anneau, hors de l'anneau, ou case qui n'a pas ete envoyee).
     */
    static BlockState stateAround(int rx, int ry, int rz) {
        FarWorld current = world;
        if (current == null || !visible() || Math.abs(rx) > far || Math.abs(rz) > far || Math.abs(ry) > height) {
            return null;
        }
        BlockState state = current.known(rx + far, ry + height, rz + far);
        if (state != null) {
            return state;
        }
        // Case pas envoyee : sous de l'eau (le fond d'un ocean), c'est de l'eau. Sinon l'eau du bord du decor proche
        // dessinait une paroi d'eau vers l'anneau. On s'arrete a la plus haute case connue de la colonne : au-dessus,
        // il n'y a rien (les particules d'ambiance tirent surtout de l'air et remontaient toute la colonne).
        int top = Math.min(2 * height, current.top(rx + far, rz + far));
        for (int y = ry + height + 1; y <= top; y++) {
            BlockState above = current.known(rx + far, y, rz + far);
            if (above != null) {
                return above.getFluidState().is(net.minecraft.tags.FluidTags.WATER) && above.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock
                        ? Blocks.WATER.defaultBlockState() : null;
            }
        }
        return null;
    }

    /** Oublie l'anneau (sortie du bocal, decor efface, nouvelle photo). */
    public static void clear() {
        // Un anneau encore en preparation ne sera pas installe.
        loadVersion++;
        closeAll(sections);
        closeAll(building);
        replacing = false;
        pending.clear();
        pendingBlocks = Map.of();
        world = null;
        pocket = null;
        center = null;
        far = 0;
    }

    private static void closeAll(Map<Long, Built> meshes) {
        meshes.values().forEach(Built::close);
        meshes.clear();
        visibleSections.clear();
        frustum.invalidate();
    }

    /** L'anneau va avec le decor proche actuel (meme bocal, meme centre, meme rayon proche). */
    public static boolean visible() {
        if (world == null || !Config.enabled() || Config.FOG.get() == Config.Fog.RENDER_DISTANCE) {
            return false;
        }
        var level = Minecraft.getInstance().level;
        return level != null && pocket.equals(level.dimension().location())
                && pocket.equals(ClientView.currentDimension()) && center.equals(ClientView.nearCenter())
                && near == ClientView.nearRadius();
    }

    public static int radius() {
        return far;
    }

    /** Construit des sections dans le budget de l'image (au moins une). */
    static void buildPending(Vec3 camera) {
        if (pending.isEmpty() || !visible()) {
            return;
        }
        long start = System.nanoTime();
        do {
            Long key = pending.poll();
            List<BlockPos> blocks = pendingBlocks.get(key);
            if (blocks == null) continue;
            try {
                long sectionStart = System.nanoTime();
                float[] box = SceneryFrustum.emptyBox();
                Built built = new Built(build(world, blocks, far, height, far, camera, true, false, box), box);
                long sectionMs = (System.nanoTime() - sectionStart) / 1_000_000L;
                if (sectionMs > 20) {
                    logSlowSection(blocks.size(), sectionMs);
                }
                Built old = (replacing ? building : sections).put(key, built);
                if (old != null) old.close();
                if (!replacing) frustum.invalidate();
            } catch (RuntimeException e) {
                LOGGER.debug("[Planarium Panorama] Section de l'anneau lointain ignoree", e);
            }
        } while (!pending.isEmpty() && System.nanoTime() - start < BUDGET_NANOS);
        if (pending.isEmpty() && replacing) {
            replacing = false;
            closeAll(sections);
            Map<Long, Built> done = building;
            building = sections;
            sections = done;
            frustum.invalidate();
        }
        if (pending.isEmpty()) {
            pendingBlocks = Map.of();
        }
    }

    /**
     * Section lente (plus de 20 ms) : temps de chaque etape. Avec -Dplanariumview.diagSections=true, aussi les types de
     * blocs qui ont pris le plus de temps (le chronometre par bloc n'est actif qu'avec cette option).
     */
    private static void logSlowSection(int blocks, long totalMs) {
        String detail = "";
        if (DIAG_SECTIONS && !lastBlockNanos.isEmpty()) {
            StringBuilder top = new StringBuilder();
            lastBlockNanos.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                    .limit(5)
                    .forEach(entry -> top.append(String.format(java.util.Locale.ROOT, " %s x%d %.1f ms;",
                            net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(entry.getKey()),
                            entry.getValue()[1], entry.getValue()[0] / 1_000_000.0)));
            detail = ", blocs les plus lents :" + top;
        }
        LOGGER.info("[Planarium Panorama] Section lente de l'anneau lointain : {} blocs, {} ms (tessellation {} ms, "
                        + "assemblage {} ms, tri {} ms, envoi {} ms){}", blocks, totalMs,
                lastTessNanos / 1_000_000L, lastMeshNanos / 1_000_000L, lastSortNanos / 1_000_000L,
                lastUploadNanos / 1_000_000L, detail);
    }

    /** Dessine une couche (l'etat de rendu de la couche est deja en place, voir ClientView.draw). */
    static void drawLayer(RenderType type, Matrix4f pose, Matrix4f projection) {
        if (sections.isEmpty() || !visible()) {
            return;
        }
        // Sections dans le champ de la camera : recalcule seulement quand la vue ou les sections changent (une fois
        // par image, pas par couche). Meme matrice que le dessin, donc meme repere que les sommets.
        if (frustum.update(pose, projection)) {
            visibleSections.clear();
            for (Built section : sections.values()) {
                if (frustum.visible(section.box())) visibleSections.add(section);
            }
        }
        // Toutes les sections ont la meme matrice : le shader est prepare une seule fois pour la couche (uniformes,
        // textures), puis chaque section est seulement dessinee. drawWithShader refaisait tout a chaque section.
        net.minecraft.client.renderer.ShaderInstance shader = RenderSystem.getShader();
        boolean applied = false;
        for (Built section : visibleSections) {
            VertexBuffer buffer = section.buffers().get(type);
            if (buffer == null || buffer.getFormat() == null) continue;
            buffer.bind();
            if (shader == null || buffer instanceof ClippedVertexBuffer) {
                // Decoupage contre le bocal : son propre dessin (qui libere le shader a la fin).
                buffer.drawWithShader(pose, projection, shader);
                applied = false;
                continue;
            }
            if (!applied) {
                shader.setDefaultUniforms(com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS, pose, projection,
                        net.minecraft.client.Minecraft.getInstance().getWindow());
                shader.apply();
                applied = true;
            }
            buffer.draw();
        }
        if (applied) shader.clear();
        VertexBuffer.unbind();
    }

    private static ByteBufferBuilder bytes(RenderType type) {
        return builders.computeIfAbsent(type, ignored -> new ByteBufferBuilder(type.bufferSize()));
    }

    /**
     * Construit les blocs {@code blocks} de {@code world}, places a (position locale - decalage) du Planarium, a
     * l'echelle du decor geant (comme le decor proche). Aussi pour les sections du decor en vol (FlightView).
     */
    static Map<RenderType, VertexBuffer> build(FarWorld world, List<BlockPos> blocks, int offX, int offY, int offZ, Vec3 camera) {
        return build(world, blocks, offX, offY, offZ, camera, true, false, null);
    }

    /** Keep flight vertices in source block units; scale and place all sections together at draw time. */
    static Map<RenderType, VertexBuffer> buildFlight(FarWorld world, List<BlockPos> blocks, Vec3 localCamera, float[] box) {
        return build(world, blocks, 1, 1, 1, localCamera, false, false, box);
    }

    /**
     * Poche d'un Planarium voisin (voir NeighborPockets) : sommets en coordonnees de sa poche, a l'echelle 1:1, places au
     * dessin. Decoupes contre l'interieur du bocal : le mur de la poche voisine depasse un peu dans le notre.
     */
    static Map<RenderType, VertexBuffer> buildPocket(FarWorld world, List<BlockPos> blocks, int offX, int offY, int offZ,
                                                      Vec3 localCamera) {
        return build(world, blocks, offX, offY, offZ, localCamera, false, true, null);
    }

    private static Map<RenderType, VertexBuffer> build(FarWorld world, List<BlockPos> blocks,
            int offX, int offY, int offZ, Vec3 camera, boolean giantCoordinates, boolean clip, float[] box) {
        Map<RenderType, BufferBuilder> buffers = new IdentityHashMap<>();
        BlockRenderDispatcher dispatcher = Minecraft.getInstance().getBlockRenderer();
        ModelBlockRenderer modelRenderer = dispatcher.getModelRenderer();
        RandomSource random = RandomSource.create();
        PoseStack matrix = new PoseStack();
        float giant = ClientView.GIANT;
        long tessStart = System.nanoTime();
        Map<net.minecraft.world.level.block.Block, long[]> perBlock = DIAG_SECTIONS ? new IdentityHashMap<>() : null;
        // Sections du vol (ni a l'echelle geante, ni decoupees) : sans lumiere coloree, voir ColorCompat.beginWithoutColor.
        if (!giantCoordinates && !clip) {
            ColorCompat.beginWithoutColor();
        } else {
            ColorCompat.begin(world);
        }
        try {
            // Cache de Minecraft pour la lumiere et l'ombrage des positions voisines, partage entre les faces.
            // Indexe par position seulement : vide a la fin de chaque construction pour ne pas melanger les mondes.
            ModelBlockRenderer.enableCaching();
            for (BlockPos pos : blocks) {
                BlockState state = world.getBlockState(pos);
                long blockStart = perBlock != null ? System.nanoTime() : 0L;
                FluidState fluid = state.getFluidState();
                matrix.pushPose();
                try {
                    // Comme le decor proche : coordonnees de poche = (position relative au Planarium - decalage d'Ars) x 32.
                    if (giantCoordinates) {
                        matrix.translate(-ClientView.ARS_OFFSET * giant, -ClientView.ARS_OFFSET * giant - ClientView.Y_SHIFT,
                                -ClientView.ARS_OFFSET * giant);
                        matrix.scale(giant, giant, giant);
                    }
                    matrix.translate(pos.getX() - offX, pos.getY() - offY, pos.getZ() - offZ);
                    if (!fluid.isEmpty()) {
                        RenderType fluidType = ItemBlockRenderTypes.getRenderLayer(fluid);
                        BufferBuilder builder = buffers.computeIfAbsent(fluidType,
                                type -> new BufferBuilder(bytes(type), type.mode(), type.format()));
                        dispatcher.renderLiquid(pos, world, new LiquidBlockVertexConsumer(builder, matrix, pos), state, fluid);
                    }
                    if (state.getRenderShape() != RenderShape.MODEL) continue;
                    BakedModel model = dispatcher.getBlockModel(state);
                    long seed = state.getSeed(world.worldPos(pos));
                    random.setSeed(seed);
                    for (RenderType type : model.getRenderTypes(state, random, ModelData.EMPTY)) {
                        BufferBuilder builder = buffers.computeIfAbsent(type,
                                layer -> new BufferBuilder(bytes(layer), layer.mode(), layer.format()));
                        VertexConsumer target = builder;
                        VertexConsumer faces = ColorCompat.building() ? new FaceHueConsumer(target, world, pos) : target;
                        modelRenderer.tesselateBlock(world, model, state, pos, matrix, faces, true, random, seed,
                                OverlayTexture.NO_OVERLAY, ModelData.EMPTY, type);
                    }
                } catch (RuntimeException e) {
                    LOGGER.debug("[Planarium Panorama] Bloc de l'anneau lointain ignore", e);
                } finally {
                    matrix.popPose();
                    if (perBlock != null) {
                        long[] stat = perBlock.computeIfAbsent(state.getBlock(), ignored -> new long[2]);
                        stat[0] += System.nanoTime() - blockStart;
                        stat[1]++;
                    }
                }
            }
        } finally {
            ModelBlockRenderer.clearCache();
            ColorCompat.end();
        }
        lastTessNanos = System.nanoTime() - tessStart;
        lastMeshNanos = lastSortNanos = lastUploadNanos = 0L;
        lastBlockNanos = perBlock != null ? perBlock : Map.of();
        Map<RenderType, VertexBuffer> result = new IdentityHashMap<>();
        Vector3f sortPos = new Vector3f((float) camera.x, (float) camera.y, (float) camera.z);
        try {
            for (Map.Entry<RenderType, BufferBuilder> entry : buffers.entrySet()) {
                long stageStart = System.nanoTime();
                MeshData mesh = entry.getValue().build();
                if (mesh == null) continue;
                // Sommets sur une grille fixe : pas de fentes entre les blocs voisins (voir GridSnap).
                if (giantCoordinates) GridSnap.snapMesh(mesh, entry.getKey() != RenderType.translucent());
                // Boite des sommets avant l'envoi a la carte graphique (upload libere le maillage).
                SceneryFrustum.include(box, mesh);
                lastMeshNanos += System.nanoTime() - stageStart;
                if (entry.getKey() == RenderType.translucent()) {
                    stageStart = System.nanoTime();
                    mesh.sortQuads(bytes(entry.getKey()), VertexSorting.byDistance(sortPos));
                    lastSortNanos += System.nanoTime() - stageStart;
                }
                // Pas de decoupe pour l'anneau lointain et le vol : ils commencent bien au-dela du bocal. Seules les
                // poches voisines touchent le bocal.
                // Le constructeur cree deja les objets OpenGL : compte dans l'envoi.
                stageStart = System.nanoTime();
                VertexBuffer buffer = clip ? new ClippedVertexBuffer() : new VertexBuffer(VertexBuffer.Usage.STATIC);
                result.put(entry.getKey(), buffer);
                buffer.bind();
                buffer.upload(mesh);
                lastUploadNanos += System.nanoTime() - stageStart;
            }
        } catch (RuntimeException e) {
            result.values().forEach(VertexBuffer::close);
            throw e;
        } finally {
            VertexBuffer.unbind();
            builders.values().forEach(ByteBufferBuilder::clear);
        }
        return result;
    }

    /**
     * Monde lu pour dessiner l'anneau : les blocs visibles, des blocs pleins la ou ils les cachent (faces internes
     * retirees), et de l'air ailleurs. Positions dans la boite de l'anneau (0..2*far, 0..2*height, 0..2*far).
     */
    static final class FarWorld implements BlockAndTintGetter {
        private static final BlockState SOLID = Blocks.STONE.defaultBlockState();
        final Long2ObjectOpenHashMap<BlockState> states = new Long2ObjectOpenHashMap<>();
        final LongOpenHashSet solid = new LongOpenHashSet();
        /** Eau qui n'est pas envoyee (sous la surface, autour des algues et des blocs waterlogged). */
        final LongOpenHashSet water = new LongOpenHashSet();
        final Long2ByteOpenHashMap light = new Long2ByteOpenHashMap();
        /** Position dans le monde de la case locale 0, 0, 0. */
        private final BlockPos origin;
        /** Cases inconnues au-dessus de cette hauteur locale : le ciel. */
        private final int skyAbove;
        /** Decalage entre les positions locales et la grille des biomes. */
        private final int biomeShift;
        /**
         * Ciel a l'endroit d'un Planarium voisin (0 a 15), ou -1. Sa poche a son propre ciel, plein : vu depuis le decor,
         * son interieur doit etre aussi sombre que l'endroit ou il se trouve (une grotte), comme une miniature.
         */
        int skyCap = -1;
        /**
         * Lumiere des lampes autour d'un Planarium voisin qui entre dans sa poche (JarLight.computeTable, en coordonnees
         * de poche), ou null. Comme pour une miniature : sans elle, un voisin eclaire de dehors restait tout noir.
         */
        byte[] outsideBlock;
        private List<Holder<Biome>> biomes = List.of();
        private byte[] biomeGrid = new byte[0];
        private int quarts;
        private final Map<ColorResolver, TerrainTintCache> tints = new IdentityHashMap<>();
        private int tintRadius = -1;

        /** Monde de l'anneau : la boite autour du Planarium. */
        static FarWorld ring(int far, int height, BlockPos center) {
            FarWorld world = new FarWorld(center.offset(-far, -height, -far), 2 * height, 0);
            world.ringFar = far;
            world.ringHeight = height;
            world.ringCenter = center.immutable();
            // Seul l'anneau utilise stateAround : pas de plafonds pour le vol ni les poches voisines.
            world.columnTop = new Long2IntOpenHashMap();
            world.columnTop.defaultReturnValue(Integer.MIN_VALUE);
            return world;
        }

        /** Anneau seulement : rayon, demi-hauteur et centre, pour lire le decor detaille au milieu (voir nearLocal). */
        private int ringFar, ringHeight;
        private BlockPos ringCenter;

        /**
         * Position dans le faux monde du decor detaille si cette case de l'anneau en fait partie (meme centre et meme
         * forme que le trou de l'anneau), sinon null. L'anneau n'a pas ces cases (le serveur les saute) : sans elles,
         * Contraption Lights ne voyait pas les lampes et feux du decor detaille, et la couleur s'arretait au rayon de
         * detail. Les faces et l'ombrage du bord de l'anneau lisent aussi les vrais blocs au lieu d'une approximation.
         */
        @Nullable
        private BlockPos nearLocal(BlockPos pos) {
            if (ringCenter == null) return null;
            int radius = ClientView.nearRadius();
            int rx = pos.getX() - ringFar, ry = pos.getY() - ringHeight, rz = pos.getZ() - ringFar;
            if (radius <= 0 || Math.abs(ry) > radius || !ClientView.inDetail(rx, rz, radius)) return null;
            if (!ringCenter.equals(ClientView.nearCenter())) return null;
            return new BlockPos(rx + radius, ry + radius, rz + radius);
        }

        FarWorld(BlockPos origin, int skyAbove, int biomeShift) {
            this.origin = origin;
            this.skyAbove = skyAbove;
            this.biomeShift = biomeShift;
            light.defaultReturnValue((byte) -1);
        }

        /**
         * Plus haute case connue de chaque colonne (blocs envoyes, et solides ou eau deduits autour d'eux), pour arreter
         * les recherches vers le haut (voir stateAround). Allouee seulement pour l'anneau lointain.
         */
        @Nullable
        private Long2IntOpenHashMap columnTop;

        void noteTop(int x, int y, int z) {
            if (columnTop == null) return;
            long column = (long) x << 32 | (z & 0xFFFFFFFFL);
            if (y > columnTop.get(column)) columnTop.put(column, y);
        }

        /** Plus haute case connue de la colonne, ou Integer.MIN_VALUE. */
        int top(int x, int z) {
            return columnTop == null ? Integer.MIN_VALUE : columnTop.get((long) x << 32 | (z & 0xFFFFFFFFL));
        }

        void setBiomes(List<Holder<Biome>> biomes, byte[] grid, int quarts) {
            this.biomes = biomes;
            this.biomeGrid = grid;
            this.quarts = quarts;
            tints.clear();
        }

        /** Etat envoye ou deduit (bloc plein cache, eau sous la surface) a cette case, null si inconnu. */
        @Nullable
        BlockState known(int x, int y, int z) {
            long key = BlockPos.asLong(x, y, z);
            BlockState state = states.get(key);
            if (state != null) return state;
            if (solid.contains(key)) return SOLID;
            return water.contains(key) ? Blocks.WATER.defaultBlockState() : null;
        }

        BlockPos worldPos(BlockPos pos) {
            return origin.offset(pos.getX(), pos.getY(), pos.getZ());
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            long key = pos.asLong();
            BlockState state = states.get(key);
            if (state != null) return state;
            BlockPos near = nearLocal(pos);
            LitRenderingWorld nearWorld = near != null ? ClientView.sceneryWorld() : null;
            if (nearWorld != null) return nearWorld.getBlockState(near);
            if (solid.contains(key)) return SOLID;
            return water.contains(key) ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState();
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Nullable
        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public int getBrightness(LightLayer layer, BlockPos pos) {
            if (!Config.realLighting()) {
                return 15;
            }
            byte value = light.get(pos.asLong());
            // 0xFF also means full sky and block light; it is unknown only when the entry is absent.
            if (value == -1 && !light.containsKey(pos.asLong())) {
                // Case du decor detaille : sa vraie lumiere (lampes et feux du centre).
                BlockPos near = nearLocal(pos);
                LitRenderingWorld nearWorld = near != null ? ClientView.sceneryWorld() : null;
                if (nearWorld != null) return nearWorld.getBrightness(layer, near);
                // Case inconnue : le ciel au-dessus de la boite, l'obscurite dedans (sous le sol).
                return layer == LightLayer.SKY ? sky(pos.getY() > skyAbove ? 15 : 0) : outsideBlock(pos);
            }
            return layer == LightLayer.SKY ? sky((value >> 4) & 15) : Math.max(value & 15, outsideBlock(pos));
        }

        /**
         * Lumiere des lampes de la poche seulement, sans celle qui entre de dehors (outsideBlock) : le plancher du
         * Planarium voisin recoit deja la lumiere du dehors, avec sa couleur (voir NeighborPlanariumModel).
         */
        int ownBlockLight(BlockPos pos) {
            if (!Config.realLighting()) return 15;
            long key = pos.asLong();
            return light.containsKey(key) ? light.get(key) & 15 : 0;
        }

        private int outsideBlock(BlockPos pos) {
            byte[] table = outsideBlock;
            if (table == null) return 0;
            int index = com.matthieu.planariumview.client.jar.JarLight.tableIndex(pos.getX() + origin.getX(),
                    pos.getY() + origin.getY(), pos.getZ() + origin.getZ());
            return index < 0 ? 0 : table[index];
        }

        private int sky(int value) {
            return skyCap < 0 || skyCap >= 15 ? value : LitRenderingWorld.combineSky(value, skyCap);
        }

        @Override
        public int getRawBrightness(BlockPos pos, int amount) {
            return Math.max(getBrightness(LightLayer.SKY, pos) - amount, getBrightness(LightLayer.BLOCK, pos));
        }

        @Override
        public float getShade(Direction direction, boolean shade) {
            var level = Minecraft.getInstance().level;
            return level != null ? level.getShade(direction, shade) : 1.0F;
        }

        @Override
        public LevelLightEngine getLightEngine() {
            return Minecraft.getInstance().level.getLightEngine();
        }

        @Override
        public int getBlockTint(BlockPos pos, ColorResolver resolver) {
            var level = Minecraft.getInstance().level;
            if (!Config.biomeColors() || quarts == 0) {
                return level != null ? level.getBlockTint(new BlockPos(16, 33, 16), resolver) : 0xFFFFFF;
            }
            int radius = Minecraft.getInstance().options.biomeBlendRadius().get();
            if (radius != tintRadius) {
                tintRadius = radius;
                tints.clear();
            }
            TerrainTintCache colors = tints.computeIfAbsent(resolver,
                    key -> new TerrainTintCache(radius, (x, z) -> rawTint(key, x, z)));
            // Noise-based resolvers must see the actual block coordinates, with the same halo on either side of a section.
            return colors.color(origin.getX() + pos.getX(), origin.getZ() + pos.getZ());
        }

        private int rawTint(ColorResolver resolver, int worldX, int worldZ) {
            int qx = Math.clamp(Math.floorDiv(worldX - origin.getX() - biomeShift, 4), 0, quarts - 1);
            int qz = Math.clamp(Math.floorDiv(worldZ - origin.getZ() - biomeShift, 4), 0, quarts - 1);
            int index = qx * quarts + qz;
            int id = index < biomeGrid.length ? biomeGrid[index] & 255 : 0;
            Holder<Biome> biome = id < biomes.size() ? biomes.get(id) : null;
            return biome == null ? 0xFFFFFF : resolver.getColor(biome.value(), worldX, worldZ);
        }

        @Override
        public int getHeight() {
            return skyAbove + 64;
        }

        @Override
        public int getMinBuildHeight() {
            return -32;
        }
    }
}

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
    private static Map<Long, Map<RenderType, VertexBuffer>> sections = new HashMap<>();
    /** Sections de la nouvelle photo, en construction ; l'ancienne reste dessinee jusqu'a la fin (rafraichissement). */
    private static Map<Long, Map<RenderType, VertexBuffer>> building = new HashMap<>();
    /** Vrai pendant un rafraichissement : les nouvelles sections vont dans {@code building}, sinon directement. */
    private static boolean replacing;
    /**
     * Rayon du dernier anneau recu pour ce bocal, garde quand l'anneau est cache (vaisseau qui bouge) : la distance de
     * dessin du jeu reste la meme (voir ClientView.sceneryDepthFar). La changer a chaque apparition de l'anneau
     * provoquait une saccade.
     */
    static int knownRadius;
    private static final Map<RenderType, ByteBufferBuilder> builders = new IdentityHashMap<>();

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
        try {
            long start = System.nanoTime();
            load(payload);
            LOGGER.info("[Planarium Panorama] Anneau lointain recu : {} blocs, lecture {} ms", payload.data().blocks().length,
                    (System.nanoTime() - start) / 1_000_000L);
        } catch (RuntimeException e) {
            LOGGER.warn("[Planarium Panorama] Anneau lointain illisible", e);
            clear();
        }
    }

    private static void load(FarRingPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        Map<Long, List<BlockPos>> bySection = new HashMap<>();
        FarWorld next = fill(FarWorld.ring(payload.far(), payload.height(), payload.center()), payload.data(),
                pos -> bySection.computeIfAbsent(BlockPos.asLong(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4),
                        ignored -> new ArrayList<>()).add(pos));

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
        Minecraft mc = Minecraft.getInstance();
        int[] paletteIds = data.palette();
        BlockState[] palette = new BlockState[paletteIds.length];
        for (int i = 0; i < palette.length; i++) {
            palette[i] = net.minecraft.world.level.block.Block.stateById(paletteIds[i]);
        }
        int[] blocks = data.blocks();
        int[] info = data.info();
        int[] lightPos = data.lightPos();
        byte[] light = data.light();
        if (blocks.length != info.length || lightPos.length != light.length) {
            throw new IllegalArgumentException("Tailles incoherentes");
        }
        Direction[] directions = Direction.values();
        for (int i = 0; i < blocks.length; i++) {
            int packed = blocks[i];
            int paletteId = info[i] & 0xFFFF;
            if (paletteId >= palette.length) continue;
            BlockPos pos = new BlockPos(FarRingPayload.unpackX(packed), FarRingPayload.unpackY(packed), FarRingPayload.unpackZ(packed));
            next.states.put(pos.asLong(), palette[paletteId]);
            int solid = info[i] >>> 16 & 0x3F;
            int water = info[i] >>> 22 & 0x3F;
            for (Direction direction : directions) {
                int bit = 1 << direction.get3DDataValue();
                if ((solid & bit) != 0) {
                    next.solid.add(pos.relative(direction).asLong());
                } else if ((water & bit) != 0) {
                    next.water.add(pos.relative(direction).asLong());
                }
            }
            sink.accept(pos);
        }
        for (int i = 0; i < lightPos.length; i++) {
            int packed = lightPos[i];
            next.light.put(BlockPos.asLong(FarRingPayload.unpackX(packed), FarRingPayload.unpackY(packed),
                    FarRingPayload.unpackZ(packed)), light[i]);
        }
        if (mc.level != null) {
            var biomeRegistry = mc.level.registryAccess().registryOrThrow(Registries.BIOME);
            List<Holder<Biome>> biomes = new ArrayList<>();
            for (ResourceLocation id : data.biomePalette()) {
                biomes.add(biomeRegistry.getHolder(ResourceKey.create(Registries.BIOME, id)).orElse(null));
            }
            next.setBiomes(biomes, data.biomes(), data.quarts());
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
        // dessinait une paroi d'eau vers l'anneau.
        for (int y = ry + height + 1; y <= 2 * height; y++) {
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

    private static void closeAll(Map<Long, Map<RenderType, VertexBuffer>> meshes) {
        meshes.values().forEach(buffers -> buffers.values().forEach(VertexBuffer::close));
        meshes.clear();
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
                Map<RenderType, VertexBuffer> built = build(blocks, camera);
                long sectionMs = (System.nanoTime() - sectionStart) / 1_000_000L;
                if (sectionMs > 50) {
                    LOGGER.info("[Planarium Panorama] Section lente de l'anneau lointain : {} blocs, {} ms", blocks.size(), sectionMs);
                }
                Map<RenderType, VertexBuffer> old = (replacing ? building : sections).put(key, built);
                if (old != null) old.values().forEach(VertexBuffer::close);
            } catch (RuntimeException e) {
                LOGGER.debug("[Planarium Panorama] Section de l'anneau lointain ignoree", e);
            }
        } while (!pending.isEmpty() && System.nanoTime() - start < BUDGET_NANOS);
        if (pending.isEmpty() && replacing) {
            replacing = false;
            closeAll(sections);
            Map<Long, Map<RenderType, VertexBuffer>> done = building;
            building = sections;
            sections = done;
        }
        if (pending.isEmpty()) {
            pendingBlocks = Map.of();
        }
    }

    /** Dessine une couche (l'etat de rendu de la couche est deja en place, voir ClientView.draw). */
    static void drawLayer(RenderType type, Matrix4f pose, Matrix4f projection) {
        if (sections.isEmpty() || !visible()) {
            return;
        }
        for (Map<RenderType, VertexBuffer> section : sections.values()) {
            VertexBuffer buffer = section.get(type);
            if (buffer == null || buffer.getFormat() == null) continue;
            buffer.bind();
            buffer.drawWithShader(pose, projection, RenderSystem.getShader());
        }
        VertexBuffer.unbind();
    }

    private static ByteBufferBuilder bytes(RenderType type) {
        return builders.computeIfAbsent(type, ignored -> new ByteBufferBuilder(type.bufferSize()));
    }

    private static Map<RenderType, VertexBuffer> build(List<BlockPos> blocks, Vec3 camera) {
        return build(world, blocks, far, height, far, camera);
    }

    /**
     * Construit les blocs {@code blocks} de {@code world}, places a (position locale - decalage) du Planarium, a
     * l'echelle du decor geant (comme le decor proche). Aussi pour les sections du decor en vol (FlightView).
     */
    static Map<RenderType, VertexBuffer> build(FarWorld world, List<BlockPos> blocks, int offX, int offY, int offZ, Vec3 camera) {
        Map<RenderType, BufferBuilder> buffers = new IdentityHashMap<>();
        BlockRenderDispatcher dispatcher = Minecraft.getInstance().getBlockRenderer();
        ModelBlockRenderer modelRenderer = dispatcher.getModelRenderer();
        RandomSource random = RandomSource.create();
        PoseStack matrix = new PoseStack();
        float giant = ClientView.GIANT;
        ColorCompat.begin(world);
        try {
            for (BlockPos pos : blocks) {
                BlockState state = world.getBlockState(pos);
                FluidState fluid = state.getFluidState();
                matrix.pushPose();
                try {
                    // Comme le decor proche : coordonnees de poche = (position relative au Planarium - decalage d'Ars) x 32.
                    matrix.translate(-ClientView.ARS_OFFSET * giant, -ClientView.ARS_OFFSET * giant - ClientView.Y_SHIFT,
                            -ClientView.ARS_OFFSET * giant);
                    matrix.scale(giant, giant, giant);
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
                        VertexConsumer faces = ColorCompat.building() ? new FaceHueConsumer(builder, world, pos) : builder;
                        modelRenderer.tesselateBlock(world, model, state, pos, matrix, faces, true, random, seed,
                                OverlayTexture.NO_OVERLAY, ModelData.EMPTY, type);
                    }
                } catch (RuntimeException e) {
                    LOGGER.debug("[Planarium Panorama] Bloc de l'anneau lointain ignore", e);
                } finally {
                    matrix.popPose();
                }
            }
        } finally {
            ColorCompat.end();
        }
        Map<RenderType, VertexBuffer> result = new IdentityHashMap<>();
        Vector3f sortPos = new Vector3f((float) camera.x, (float) camera.y, (float) camera.z);
        try {
            for (Map.Entry<RenderType, BufferBuilder> entry : buffers.entrySet()) {
                MeshData mesh = entry.getValue().build();
                if (mesh == null) continue;
                if (entry.getKey() == RenderType.translucent()) {
                    mesh.sortQuads(bytes(entry.getKey()), VertexSorting.byDistance(sortPos));
                }
                VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
                result.put(entry.getKey(), buffer);
                buffer.bind();
                buffer.upload(mesh);
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
        private List<Holder<Biome>> biomes = List.of();
        private byte[] biomeGrid = new byte[0];
        private int quarts;
        private final Map<ColorResolver, int[]> tints = new IdentityHashMap<>();

        /** Monde de l'anneau : la boite autour du Planarium. */
        static FarWorld ring(int far, int height, BlockPos center) {
            return new FarWorld(center.offset(-far, -height, -far), 2 * height, 0);
        }

        FarWorld(BlockPos origin, int skyAbove, int biomeShift) {
            this.origin = origin;
            this.skyAbove = skyAbove;
            this.biomeShift = biomeShift;
            light.defaultReturnValue((byte) -1);
        }

        void setBiomes(List<Holder<Biome>> biomes, byte[] grid, int quarts) {
            this.biomes = biomes;
            this.biomeGrid = grid;
            this.quarts = quarts;
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
            if (value == -1) {
                // Case inconnue : le ciel au-dessus de la boite, l'obscurite dedans (sous le sol).
                return layer == LightLayer.SKY && pos.getY() > skyAbove ? 15 : 0;
            }
            return layer == LightLayer.SKY ? (value >> 4) & 15 : value & 15;
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
            int[] colors = tints.computeIfAbsent(resolver, this::quartColors);
            // Melange entre les centres des colonnes de 4 x 4 blocs (sans lui, l'herbe changerait de couleur par carres).
            float fx = Math.max(0, Math.min(quarts - 1, (pos.getX() - biomeShift - 2) / 4.0F));
            float fz = Math.max(0, Math.min(quarts - 1, (pos.getZ() - biomeShift - 2) / 4.0F));
            int x0 = (int) fx, z0 = (int) fz;
            int x1 = Math.min(quarts - 1, x0 + 1), z1 = Math.min(quarts - 1, z0 + 1);
            float tx = fx - x0, tz = fz - z0;
            return mix(mix(colors[x0 * quarts + z0], colors[x1 * quarts + z0], tx),
                    mix(colors[x0 * quarts + z1], colors[x1 * quarts + z1], tx), tz);
        }

        private int[] quartColors(ColorResolver resolver) {
            int[] colors = new int[quarts * quarts];
            for (int qx = 0; qx < quarts; qx++) {
                for (int qz = 0; qz < quarts; qz++) {
                    int index = qx * quarts + qz;
                    int id = index < biomeGrid.length ? biomeGrid[index] & 0xFF : 0;
                    Holder<Biome> biome = id < biomes.size() ? biomes.get(id) : null;
                    double worldX = origin.getX() + biomeShift + qx * 4 + 2;
                    double worldZ = origin.getZ() + biomeShift + qz * 4 + 2;
                    colors[index] = biome == null ? 0xFFFFFF : resolver.getColor(biome.value(), worldX, worldZ);
                }
            }
            return colors;
        }

        private static int mix(int a, int b, float t) {
            int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
            int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
            int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
            return r << 16 | g << 8 | bl;
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

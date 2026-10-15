package com.matthieu.planariumview;

import com.hollingsworth.arsnouveau.client.renderer.LiquidBlockVertexConsumer;
import com.hollingsworth.arsnouveau.client.renderer.tile.PlanariumRenderer;
import com.hollingsworth.arsnouveau.client.renderer.tile.PlanariumRenderer.StructureRenderData;
import com.hollingsworth.arsnouveau.client.renderer.world.CulledStatePos;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.logging.LogUtils;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.LevelRenderer;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.hollingsworth.arsnouveau.common.block.tile.PlanariumTile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

/**
 * Cote client : dessine la "photo" des alentours du Planarium, agrandie 32 fois,
 * autour de la dimension de poche.
 */
public final class ClientView {
    private static final Logger LOGGER = LogUtils.getLogger();

    // Memes constantes que PlanariumRenderer d'Ars Nouveau (la dimension de 32 blocs tient dans 1 bloc).
    private static final float PAD = 0.0025f;
    private static final float ARS_SCALE = (1.0f - 2.0f * PAD) / 32.0f;
    static final float ARS_OFFSET = PAD + ((1.0f - 2.0f * PAD) - 32 * ARS_SCALE) * 0.5f;
    /** Facteur d'agrandissement : 1 bloc du monde = ~32 blocs dans la poche. */
    static final float GIANT = 1.0f / ARS_SCALE;
    /**
     * Dans le rendu d'Ars, le bloc de poche a la hauteur y apparait a (y + 25) / 32 dans le Planarium.
     * On applique la transformation inverse.
     */
    static final float Y_SHIFT = 25.0f;

    private static final List<RenderType> DRAW_ORDER = List.of(
            RenderType.solid(), RenderType.cutout(), RenderType.cutoutMipped(),
            RenderType.translucent(), RenderType.tripwire());
    private static final int SECTION_SIZE = 16;

    private static ResourceLocation currentDim;
    private static int currentRadius;
    private static StructureTemplate pendingTemplate;
    private static byte[] pendingLight;
    private static OutsideViewPayload pendingInfo;
    private static OutsideViewPayload lastInfo;
    private static OutsideViewPayload latestInfo;
    /** Derniere photo recue, gardee pour reconstruire le rendu quand une option change. */
    private static StructureTemplate lastTemplate;
    private static byte[] lastLight;
    private static StructureRenderData renderData;
    private static final Map<SectionPos, SectionMesh> sectionMeshes = new HashMap<>();
    private static Map<SectionPos, List<CulledStatePos>> pendingSectionStates = Map.of();
    private static final ArrayDeque<SectionPos> pendingSections = new ArrayDeque<>();
    private static final Set<SectionPos> pendingSectionSet = new HashSet<>();
    private static StructureRenderData pendingSectionData;
    private static boolean forceAllSections;
    private static byte[] renderedLight;
    private static byte[] renderedBiomes;
    /** Coin du gabarit dans le monde au dernier rendu : s'il bouge, les biomes et leurs couleurs changent partout. */
    private static BlockPos renderedOrigin;
    private static List<ResourceLocation> renderedBiomePalette;
    private static boolean forcePendingBuild;
    private static int sortCounter;
    private static final Map<BlockPos, Integer> blockEntityLight = new java.util.HashMap<>();

    private record SectionPos(int x, int y, int z) {
        static SectionPos of(BlockPos pos) {
            return new SectionPos(Math.floorDiv(pos.getX(), SECTION_SIZE),
                    Math.floorDiv(pos.getY(), SECTION_SIZE), Math.floorDiv(pos.getZ(), SECTION_SIZE));
        }

        Vec3 center(int radius) {
            return new Vec3((x * SECTION_SIZE + SECTION_SIZE / 2.0 - radius - ARS_OFFSET) * GIANT,
                    (y * SECTION_SIZE + SECTION_SIZE / 2.0 - radius - ARS_OFFSET) * GIANT - Y_SHIFT,
                    (z * SECTION_SIZE + SECTION_SIZE / 2.0 - radius - ARS_OFFSET) * GIANT);
        }

        double distanceFromPlanariumSqr(int radius) {
            double xCenter = x * SECTION_SIZE + SECTION_SIZE / 2.0 - radius;
            double yCenter = y * SECTION_SIZE + SECTION_SIZE / 2.0 - radius;
            double zCenter = z * SECTION_SIZE + SECTION_SIZE / 2.0 - radius;
            return xCenter * xCenter + yCenter * yCenter + zCenter * zCenter;
        }
    }

    private static final class SectionMesh {
        final Map<RenderType, VertexBuffer> buffers = new HashMap<>();
        final Map<RenderType, MeshData.SortState> sortStates = new HashMap<>();
        final Map<RenderType, com.mojang.blaze3d.vertex.ByteBufferBuilder> sortBuffers = new HashMap<>();

        void sort(Vec3 camera) {
            for (Map.Entry<RenderType, MeshData.SortState> entry : sortStates.entrySet()) {
                RenderType type = entry.getKey();
                com.mojang.blaze3d.vertex.ByteBufferBuilder builder = sortBuffers.computeIfAbsent(type,
                        ignored -> new com.mojang.blaze3d.vertex.ByteBufferBuilder(4096));
                VertexBuffer buffer = buffers.get(type);
                if (buffer == null) continue;
                builder.clear();
                var sorted = entry.getValue().buildSortedIndexBuffer(builder,
                        VertexSorting.byDistance(v -> -v.distanceSquared((float) camera.x, (float) camera.y, (float) camera.z)));
                buffer.bind();
                buffer.uploadIndexBuffer(sorted);
                VertexBuffer.unbind();
            }
        }

        void close() {
            for (VertexBuffer buffer : buffers.values()) buffer.close();
            for (var builder : sortBuffers.values()) builder.close();
            buffers.clear();
            sortBuffers.clear();
            sortStates.clear();
        }
    }

    private ClientView() {
    }

    public static void init(net.neoforged.fml.ModContainer container) {
        container.registerExtensionPoint(net.neoforged.neoforge.client.gui.IConfigScreenFactory.class,
                (modContainer, parent) -> ConfigScreen.create(parent));
        NeoForge.EVENT_BUS.addListener(ClientView::onRenderStage);
        NeoForge.EVENT_BUS.addListener(ClientView::onLoggingOut);
        NeoForge.EVENT_BUS.addListener(ClientView::sendToggle);
        JarInteriorBiome.init();
        WeatherView.init();
        LightningView.init();
        LunarClient.init();
        SeasonColors.init();
        container.getEventBus().addListener(net.neoforged.fml.event.lifecycle.FMLClientSetupEvent.class,
                event -> event.enqueueWork(() -> {
                    WallColors.register();
                    LogFilter.install();
                }));
    }

    public static void receive(OutsideViewPayload payload) {
        if (!payload.active()) {
            dispose();
            return;
        }
        StructureTemplate template = new StructureTemplate();
        template.load(BuiltInRegistries.BLOCK.asLookup(), payload.template());
        boolean changedDimension = currentDim != null && !currentDim.equals(payload.pocketDim());
        boolean changedRadius = currentDim != null && currentRadius != payload.radius();
        if (changedDimension || changedRadius || (currentDim == null && renderData != null)) {
            disposeRender();
        }
        currentDim = payload.pocketDim();
        currentRadius = payload.radius();
        pendingTemplate = template;
        pendingLight = payload.light();
        pendingInfo = payload;
        latestInfo = payload;
        WeatherView.set(payload);
        lastTemplate = template;
        lastLight = payload.light();
    }

    /**
     * Part de l'air relie au Planarium (dans la photo) qui est a ciel ouvert (lumiere du ciel complete).
     * Une entree de caverne donne sur beaucoup d'air a ciel ouvert ; une caverne fermee, ou percee d'un petit trou, tres peu.
     */
    private static volatile float openSkyShare = 1.0F;

    public static float openSkyShare() {
        return openSkyShare;
    }

    /**
     * Part des directions, vues du Planarium, qui menent dehors : un rayon compte s'il atteint de l'air
     * dehors (lumiere du ciel 12 ou plus, par une fenetre aussi) ou sort de la zone captee par de l'air bien eclaire par le ciel, sans toucher de bloc opaque.
     * A l'entree d'une caverne, l'ouverture occupe une bonne part de la vue ; au fond, aucune.
     */
    private static float computeOpenSkyShare(LitRenderingWorld lit, int radius) {
        try {
            int size = radius * 2 + 1;
            int rays = 1500;
            int open = 0;
            double center = radius + 0.5;
            double golden = Math.PI * (3.0 - Math.sqrt(5.0));
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int i = 0; i < rays; i++) {
                double dy = 1.0 - 2.0 * (i + 0.5) / rays;
                double ring = Math.sqrt(1.0 - dy * dy);
                double angle = golden * i;
                double dx = Math.cos(angle) * ring;
                double dz = Math.sin(angle) * ring;
                boolean escaped = true;
                int lastSky = 0;
                for (double t = 0.75; t <= radius * 1.8; t += 0.3) {
                    int x = (int) Math.floor(center + dx * t);
                    int y = (int) Math.floor(center + dy * t);
                    int z = (int) Math.floor(center + dz * t);
                    if (x < 0 || y < 0 || z < 0 || x >= size || y >= size || z >= size) {
                        // Sortie de la zone : dehors seulement si l'air au bord recoit le ciel (pas une grande grotte).
                        escaped = lastSky >= 8;
                        break;
                    }
                    if (x == radius && y == radius && z == radius) {
                        continue;
                    }
                    pos.set(x, y, z);
                    if (lit.getBlockState(pos).getLightBlock(lit, pos) >= 15) {
                        escaped = false;
                        break;
                    }
                    lastSky = lit.getBrightness(net.minecraft.world.level.LightLayer.SKY, pos);
                    if (lastSky >= 12) {
                        break;
                    }
                }
                if (escaped) {
                    open++;
                }
            }
            float share = open / (float) rays;
            LOGGER.info("[Planarium Panorama] Vue vers dehors depuis le Planarium : {} rayons sur {}", open, rays);
            return share;
        } catch (Exception e) {
            return 1.0F;
        }
    }

    /** Faux monde du decor geant (positions du gabarit, vraie lumiere), ou null s'il n'est pas construit. */
    public static net.minecraft.world.level.Level outsideWorld() {
        StructureRenderData data = renderData;
        return data != null ? data.fakeRenderingWorld : null;
    }

    /** Position, dans le faux monde du decor geant, du bloc juste au-dessus du Planarium. */
    public static BlockPos outsideAbovePlanarium() {
        return new BlockPos(currentRadius, currentRadius + 1, currentRadius);
    }

    /** Reconstruit le decor geant et les miniatures avec les options actuelles (appele par le menu de config). */
    /**
     * Rayon de vue affiche : celui du serveur, mais jamais plus grand que la distance d'affichage du joueur (en chunks),
     * sauf avec Voxy (qui fait dessiner le jeu bien plus loin). Ensuite :
     * - brouillard "Distance d'affichage" : seulement ce que le brouillard laisse voir. Il finit a la distance d'affichage
     *   en blocs normaux, et un bloc du decor en fait 32 : environ la moitie de la distance d'affichage, plus un bloc de marge ;
     * - sinon : le decor, son mur de brouillard et le dome des cavernes doivent rester dans la distance de dessin du jeu
     *   (avec une marge pour les coins et le haut), sinon ils seraient coupes net.
     */
    public static int viewRadius() {
        int renderDistance = Minecraft.getInstance().options.getEffectiveRenderDistance();
        int radius = voxy() ? currentRadius : Math.min(currentRadius, renderDistance);
        if (Config.FOG.get() == Config.Fog.RENDER_DISTANCE) {
            int visible = (int) Math.ceil(renderDistance * 16.0 / GIANT) + 1;
            radius = Math.min(radius, visible);
        } else {
            radius = Math.min(radius, drawableRadius());
        }
        return Math.max(1, radius);
    }

    /** Plus grand rayon de vue dont le bord (avec le mur de brouillard ou le dome) reste dans la distance de dessin. */
    private static int drawableRadius() {
        float far = drawDistance();
        if (far <= 0.0F) {
            return Integer.MAX_VALUE;
        }
        return Math.max(1, (int) Math.floor((far * 0.6F - GIANT / 2.0F - 3.0F) / GIANT));
    }

    private static Boolean voxy;
    private static java.lang.reflect.Field voxyConfig;
    private static java.lang.reflect.Method voxyEnabled;
    private static java.lang.reflect.Field voxyDistance;

    /** Vrai si Voxy est installe. */
    private static boolean voxy() {
        if (voxy == null) {
            net.neoforged.fml.ModList mods = net.neoforged.fml.ModList.get();
            voxy = mods != null && mods.isLoaded("voxy");
        }
        return voxy;
    }

    /**
     * Distance (en blocs) jusqu'ou le jeu dessine : 4 fois la distance d'affichage, ou bien plus avec Voxy
     * (il l'allonge pendant le dessin du monde, comme dans son GameRenderer : sa distance en sections x 32 x 4).
     */
    static float drawDistance() {
        float far = Minecraft.getInstance().gameRenderer.getDepthFar();
        if (!voxy()) {
            return far;
        }
        try {
            if (voxyConfig == null) {
                Class<?> config = Class.forName("me.cortex.voxy.client.config.VoxyConfig");
                voxyConfig = config.getField("CONFIG");
                voxyEnabled = config.getMethod("isRenderingEnabled");
                voxyDistance = config.getField("sectionRenderDistance");
            }
            Object config = voxyConfig.get(null);
            if (config != null && (Boolean) voxyEnabled.invoke(config)) {
                return Math.max(far, ((Number) voxyDistance.get(config)).floatValue() * 32.0F * 4.0F);
            }
        } catch (Throwable e) {
            // Autre version de Voxy : pas de limite.
            return Float.MAX_VALUE;
        }
        return far;
    }

    /** Rayon de vue avec lequel le decor actuel a ete construit. */
    private static int builtViewRadius = -1;

    public static void rebuild() {
        if (lastTemplate != null && currentDim != null) {
            pendingTemplate = lastTemplate;
            pendingLight = lastLight;
            pendingInfo = latestInfo;
            forcePendingBuild = true;
            forceAllSections = true;
        }
        MiniLight.rebuildAll();
    }

    /** Dernier etat de l'interrupteur general envoye au serveur (null : rien d'envoye sur cette connexion). */
    private static Boolean sentEnabled;

    /** Previent le serveur quand l'interrupteur general change (et a la connexion) : OFF, il ne capture plus rien pour nous. */
    private static void sendToggle(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        net.minecraft.client.multiplayer.ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null || Minecraft.getInstance().player == null) {
            return;
        }
        boolean enabled = Config.enabled();
        if (sentEnabled != null && sentEnabled == enabled) {
            return;
        }
        sentEnabled = enabled;
        try {
            if (connection.hasChannel(ViewTogglePayload.TYPE)) {
                net.neoforged.neoforge.network.PacketDistributor.sendToServer(new ViewTogglePayload(enabled));
            }
        } catch (Exception e) {
            LOGGER.debug("[Planarium Panorama] Interrupteur general non envoye au serveur", e);
        }
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        sentEnabled = null;
        dispose();
        MiniLight.clear();
        LunarClient.clear();
    }

    private static void dispose() {
        disposeRender();
        lastTemplate = null;
        lastLight = null;
        lastInfo = null;
        latestInfo = null;
        OutsideAmbience.clear();
        SkySync.clear();
        WeatherView.clear();
        WallColors.clear();
        GiantSprites.clear();
    }

    private static void disposeRender() {
        GiantEntities.clear();
        releaseRenderData(renderData);
        for (SectionMesh mesh : sectionMeshes.values()) mesh.close();
        sectionMeshes.clear();
        pendingSections.clear();
        pendingSectionSet.clear();
        pendingSectionStates = Map.of();
        pendingSectionData = null;
        renderedLight = null;
        renderedBiomes = null;
        renderedOrigin = null;
        renderedBiomePalette = null;
        renderData = null;
        builtViewRadius = -1;
        pendingTemplate = null;
        forcePendingBuild = false;
        forceAllSections = false;
        currentDim = null;
    }

    private static void releaseRenderData(StructureRenderData data) {
        if (data == null) {
            return;
        }
        try {
            PlanariumRenderer.clearByteBuffers(data);
            for (VertexBuffer buffer : data.vertexBuffers.values()) {
                buffer.close();
            }
        } catch (Exception e) {
            LOGGER.warn("[Planarium View] Erreur en liberant le rendu", e);
        }
    }

    private static void onRenderStage(RenderLevelStageEvent event) {
        boolean sky = event.getStage() == RenderLevelStageEvent.Stage.AFTER_SKY;
        boolean blockEntities = event.getStage() == RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES;
        if (!sky && !blockEntities) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (sky) {
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
                    drawBlockEntities(renderData, event);
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
                forcePendingBuild = false;
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
            }
            buildPendingSections(event.getCamera().getPosition());
            if (renderData != null) {
                GiantSprites.markActive();
                draw(renderData, event);
            }
        } catch (Exception e) {
            LOGGER.error("[Planarium View] Erreur de rendu, vue desactivee", e);
            dispose();
        } finally {
            SkySync.giantLight(false, partialTick);
        }
    }

    private static StructureRenderData build(StructureTemplate template, byte[] light, OutsideViewPayload info,
                                             int radius, Vec3 camera) {
        long buildStart = System.nanoTime();
        StructureRenderData data = LitRenderingWorld.asGiant(() -> new StructureRenderData(template, 0L));
        long templateNanos = System.nanoTime() - buildStart;
        if (data.statePosCache == null) {
            data.statePosCache = new ArrayList<>();
        }
        long stageStart = System.nanoTime();
        // Remplace le faux monde d'Ars (lumiere 15 partout) par le notre, avec la vraie lumiere.
        // (et avec les couleurs du vrai biome pour l'herbe, les feuilles et l'eau).
        LitRenderingWorld lit = LitRenderingWorld.giant(Minecraft.getInstance().level, data.statePosCache);
        if (Config.realLighting() && light != null && light.length > 0) {
            lit.setLight(light, radius * 2 + 3);
        }
        if (info != null && Config.biomeColors()) {
            var registry = Minecraft.getInstance().level.registryAccess()
                    .registryOrThrow(net.minecraft.core.registries.Registries.BIOME);
            java.util.List<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> palette = new java.util.ArrayList<>();
            for (ResourceLocation id : info.biomePalette()) {
                var holder = registry.getHolder(net.minecraft.resources.ResourceKey.create(
                        net.minecraft.core.registries.Registries.BIOME, id));
                palette.add(holder.isPresent() ? holder.get()
                        : registry.getHolderOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS));
            }
            lit.setBiomes(palette, info.biomes(), radius * 2 + 1, info.origin());
            LOGGER.info("[Planarium Panorama] Couleurs de biome : {} ({})",
                    lit.hasBiomes() ? "OK" : "donnees invalides", info.biomePalette());
        }
        data.fakeRenderingWorld = lit;
        openSkyShare = computeOpenSkyShare(lit, radius);
        long sceneryDataNanos = System.nanoTime() - stageStart;
        // On n'affiche pas le Planarium lui-meme ni le bloc juste au-dessus : ils occuperaient la poche.
        BlockPos planarium = new BlockPos(radius, radius, radius);
        BlockPos above = planarium.above();
        data.statePosCache.removeIf(p -> p.pos.equals(planarium) || p.pos.equals(above));
        // Rayon de vue limite par la distance d'affichage : on ne garde que les blocs dans ce rayon (a l'horizontale).
        int view = viewRadius();
        builtViewRadius = view;
        if (view < radius) {
            data.statePosCache.removeIf(p -> Math.abs(p.pos.getX() - radius) > view || Math.abs(p.pos.getZ() - radius) > view);
        }

        long specialLightNanos;
        ColorCompat.begin(data.fakeRenderingWorld);
        try {
            stageStart = System.nanoTime();
            // Lumiere des blocs speciaux, calculee ici pendant que Contraption Lights lit notre faux monde.
            blockEntityLight.clear();
            for (BlockPos pos : data.fakeRenderingWorld.blockEntityMap.keySet()) {
                try {
                    blockEntityLight.put(pos, LevelRenderer.getLightColor(data.fakeRenderingWorld, pos));
                } catch (Exception e) {
                    LOGGER.debug("[Planarium View] Lumiere de bloc special ignoree", e);
                }
            }
            specialLightNanos = System.nanoTime() - stageStart;
        } finally {
            ColorCompat.end();
        }

        Map<SectionPos, List<CulledStatePos>> sectionStates = groupSections(data.statePosCache);
        Set<SectionPos> carriedSections = new HashSet<>(pendingSectionSet);
        Set<SectionPos> dirtySections = changedSections(renderData, data, sectionStates, light, info, radius);
        dirtySections.addAll(carriedSections);
        for (SectionPos existing : new HashSet<>(sectionMeshes.keySet())) {
            if (!sectionStates.containsKey(existing)) {
                SectionMesh removed = sectionMeshes.remove(existing);
                if (removed != null) removed.close();
            }
        }
        pendingSections.clear();
        pendingSectionSet.clear();
        pendingSectionData = data;
        pendingSectionStates = sectionStates;
        dirtySections.removeIf(key -> !sectionStates.containsKey(key));
        List<SectionPos> orderedSections = new ArrayList<>(dirtySections);
        orderedSections.sort(Comparator.comparingDouble(section -> section.distanceFromPlanariumSqr(radius)));
        for (SectionPos section : orderedSections) {
            pendingSections.addLast(section);
            pendingSectionSet.add(section);
        }
        if (pendingSections.isEmpty()) pendingSectionData = null;
        renderedLight = light == null ? null : light.clone();
        renderedBiomePalette = info == null ? null : info.biomePalette();
        renderedBiomes = info == null || info.biomes() == null ? null : info.biomes().clone();
        renderedOrigin = info == null ? null : info.origin();
        forceAllSections = false;
        stageStart = System.nanoTime();
        GiantSprites.collect(data);
        long spritesNanos = System.nanoTime() - stageStart;
        LOGGER.info("[Planarium Panorama] Preparation de {} blocs : total={} ms, gabarit={} ms, donnees={} ms, " +
                        "lumiere speciale={} ms, sections a retesseler={}, sprites={} ms",
                data.statePosCache.size(), (System.nanoTime() - buildStart) / 1_000_000L,
                templateNanos / 1_000_000L, sceneryDataNanos / 1_000_000L,
                specialLightNanos / 1_000_000L, pendingSections.size(), spritesNanos / 1_000_000L);
        return data;
    }

    private static Map<SectionPos, List<CulledStatePos>> groupSections(List<CulledStatePos> states) {
        Map<SectionPos, List<CulledStatePos>> sections = new HashMap<>();
        for (CulledStatePos state : states) {
            sections.computeIfAbsent(SectionPos.of(state.pos), ignored -> new ArrayList<>()).add(state);
        }
        return sections;
    }

    private static Set<SectionPos> changedSections(StructureRenderData previous, StructureRenderData current,
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
    private static void markAffectedSections(Set<SectionPos> changed, BlockPos pos) {
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
    private static boolean markLightChanges(Set<SectionPos> changed, byte[] light, byte[] previous, int size) {
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
    private static void markBox(Set<SectionPos> changed, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
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
                if (type == RenderType.translucent()) {
                    section.sortStates.put(type, mesh.sortQuads(data.getByteBuffer(type),
                            VertexSorting.byDistance(v -> -sortPos.distanceSquared(v))));
                }
                VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
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
    private static void buildPendingSections(Vec3 camera) {
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
        SectionMesh old = sectionMeshes.remove(key);
        if (states == null || states.isEmpty()) {
            if (old != null) old.close();
        } else {
            try {
                SectionMesh replacement = buildSection(pendingSectionData, currentRadius, states, camera);
                sectionMeshes.put(key, replacement);
                if (old != null) old.close();
            } catch (Exception e) {
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
                RenderType fluidType = RenderType.translucent();
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
                if (renderState.getFluidState().isEmpty() && isModel && !(model instanceof JarClippedModel)) {
                    // Bloc couvert de neige de Snow! Real Magic : le vrai bloc, puis la neige (voir SnowRealMagicCompat).
                    final CulledStatePos culled = pos;
                    final BufferBuilder snowTarget = builder;
                    final RenderType layer = renderType;
                    boolean snowy = SnowRealMagicCompat.render(data.fakeRenderingWorld, renderState, pos.pos, renderType, matrix,
                            (partState, partModel, pose) -> PlanariumRenderer.tesselateBlock(culled, modelRenderer,
                                    data.fakeRenderingWorld, partModel, partState, culled.pos, pose, snowTarget, random,
                                    partState.getSeed(culled.pos), OverlayTexture.NO_OVERLAY, ModelData.EMPTY, layer));
                    if (snowy) {
                        continue;
                    }
                }
                if (renderState.getFluidState().isEmpty() && isModel) {
                    try {
                        PlanariumRenderer.tesselateBlock(pos, modelRenderer, data.fakeRenderingWorld, model, renderState,
                                pos.pos, matrix, builder, random, renderState.getSeed(pos.pos),
                                OverlayTexture.NO_OVERLAY, ModelData.EMPTY, renderType);
                    } catch (Exception e) {
                        LOGGER.debug("[Planarium View] Bloc ignore", e);
                    }
                }
            }
            matrix.popPose();
        }
    }

    private static void draw(StructureRenderData data, RenderLevelStageEvent event) {
        if (++sortCounter > 20) {
            sortCounter = 0;
            Vec3 camera = event.getCamera().getPosition();
            for (SectionMesh section : sectionMeshes.values()) section.sort(camera);
        }

        Vec3 camera = event.getCamera().getPosition();
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
        float[] cave = client.level != null ? caveColor(client.level) : null;
        if (cave != null) {
            RenderSystem.setShaderFogColor(cave[0], cave[1], cave[2], fogColor[3]);
        }
        try {
            for (RenderType renderType : DRAW_ORDER) {
                List<Map.Entry<SectionPos, SectionMesh>> sections = new ArrayList<>(sectionMeshes.entrySet());
                if (renderType == RenderType.translucent()) {
                    sections.sort(Comparator.comparingDouble(entry ->
                            entry.getKey().center(currentRadius).distanceToSqr(event.getCamera().getPosition())));
                    java.util.Collections.reverse(sections);
                }
                renderType.setupRenderState();
                try {
                    for (Map.Entry<SectionPos, SectionMesh> entry : sections) {
                        VertexBuffer vertexBuffer = entry.getValue().buffers.get(renderType);
                        if (vertexBuffer == null || vertexBuffer.getFormat() == null) continue;
                        vertexBuffer.bind();
                        vertexBuffer.drawWithShader(poseStack.last().pose(),
                                new Matrix4f(event.getProjectionMatrix()), RenderSystem.getShader());
                        VertexBuffer.unbind();
                    }
                } finally {
                    VertexBuffer.unbind();
                    renderType.clearRenderState();
                }
            }
            drawFogWall(poseStack, new Matrix4f(event.getProjectionMatrix()), camera);
            drawCaveDome(poseStack, new Matrix4f(event.getProjectionMatrix()));
            try {
                CloudView.draw(poseStack.last().pose(), new Matrix4f(event.getProjectionMatrix()), camera,
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

    /**
     * Brouillard "Rayon de vue" : au-dela du bord du decor, il n'y a que le ciel. Le jour, il a la couleur du brouillard
     * et tout se fond ; la nuit (etoiles, ciels moddes comme AstroCraft), on voyait le ciel la ou le sol devrait etre.
     * On ferme donc l'horizon : un mur et un fond de la couleur du brouillard, juste apres le bord, jusqu'a l'horizon.
     */
    private static VertexBuffer fogWall;
    private static float fogWallEdge = -1;

    private static void drawFogWall(PoseStack poseStack, Matrix4f projection, Vec3 camera) {
        if (Config.FOG.get() != Config.Fog.VIEW_RADIUS) {
            return;
        }
        float edge = viewRadius() * GIANT + GIANT / 2.0F + 2.0F;
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

    private static void drawCaveDome(PoseStack poseStack, Matrix4f projection) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !SkySync.underground(mc.level)) {
            return;
        }
        // Dans la distance d'affichage, sinon le dessus serait coupe et laisserait voir le ciel.
        float edge = Math.min(viewRadius() * GIANT + GIANT / 2.0F + 3.0F, drawDistance() * 0.6F);
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
        Config.Fog mode = Config.FOG.get();
        if (mode == Config.Fog.OFF) {
            RenderSystem.setShaderFogStart(Float.MAX_VALUE / 2);
            RenderSystem.setShaderFogEnd(Float.MAX_VALUE);
            return;
        }
        if (mode == Config.Fog.VIEW_RADIUS) {
            // Bord du decor geant : le rayon de vue (en blocs du monde) fois 32, plus la moitie de la case du Planarium.
            float edge = viewRadius() * GIANT + GIANT / 2.0F;
            RenderSystem.setShaderFogStart(edge - net.minecraft.util.Mth.clamp(edge / 4.0F, 16.0F, 160.0F));
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

    /** Tableaux, cadres et porte-armures du decor (des entites, pas des blocs : voir GiantEntities). */
    private static void drawEntities(StructureRenderData data, float partialTick, Vec3 camera, PoseStack poseStack, int radius) {
        OutsideViewPayload info = lastInfo;
        if (info == null || !(data.fakeRenderingWorld instanceof net.minecraft.world.level.Level fake)) {
            return;
        }
        int view = viewRadius();
        for (net.minecraft.world.entity.Entity entity : GiantEntities.entities(data, fake, info.template(), info.origin())) {
            if (Math.abs(entity.getX() - radius - 0.5) > view + 1 || Math.abs(entity.getZ() - radius - 0.5) > view + 1) {
                continue;
            }
            poseStack.pushPose();
            poseStack.translate(-camera.x, -camera.y, -camera.z);
            poseStack.translate(-ARS_OFFSET * GIANT, -ARS_OFFSET * GIANT - Y_SHIFT, -ARS_OFFSET * GIANT);
            poseStack.scale(GIANT, GIANT, GIANT);
            poseStack.translate(-radius, -radius, -radius);
            try {
                GiantEntities.render(entity, fake, partialTick, poseStack, blockEntityBuffers);
            } catch (Exception e) {
                LOGGER.debug("[Planarium Panorama] Entite du decor ignoree", e);
            }
            poseStack.popPose();
        }
    }

    /** Tampon a part pour les blocs a rendu special (coffres, panneaux, etc.). */
    private static MultiBufferSource.BufferSource blockEntityBuffers;

    /**
     * Les coffres, panneaux, bannieres, tetes, etc. ne sont pas des modeles de blocs :
     * ils sont dessines a chaque image par leur "BlockEntityRenderer", ici agrandis 32 fois.
     */
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
        BlockPos planarium = new BlockPos(radius, radius, radius);
        BlockPos above = planarium.above();

        float fogStart = RenderSystem.getShaderFogStart();
        float fogEnd = RenderSystem.getShaderFogEnd();
        com.mojang.blaze3d.shaders.FogShape fogShape = RenderSystem.getShaderFogShape();
        applySceneryFog();
        try {
            PoseStack poseStack = new PoseStack();
            for (BlockEntity blockEntity : data.fakeRenderingWorld.blockEntityMap.values()) {
                BlockPos pos = blockEntity.getBlockPos();
                if (pos.equals(planarium) || pos.equals(above)) {
                    continue;
                }
                int view = viewRadius();
                if (Math.abs(pos.getX() - radius) > view || Math.abs(pos.getZ() - radius) > view) {
                    continue;
                }
                if (blockEntity instanceof PlanariumTile planariumTile) {
                    // Evite de dessiner une miniature dans la miniature.
                    planariumTile.key = null;
                }
                BlockEntityRenderer<BlockEntity> renderer = dispatcher.getRenderer(blockEntity);
                if (renderer == null) {
                    continue;
                }
                poseStack.pushPose();
                poseStack.translate(-camera.x, -camera.y, -camera.z);
                poseStack.translate(-ARS_OFFSET * GIANT, -ARS_OFFSET * GIANT - Y_SHIFT, -ARS_OFFSET * GIANT);
                poseStack.scale(GIANT, GIANT, GIANT);
                poseStack.translate(pos.getX() - radius, pos.getY() - radius, pos.getZ() - radius);
                Integer stored = blockEntityLight.get(pos);
                int light = stored != null ? stored : LevelRenderer.getLightColor(data.fakeRenderingWorld, pos);
                try {
                    if (!AmbersolCompat.render(blockEntity, partialTick, poseStack, blockEntityBuffers)) {
                        renderer.render(blockEntity, partialTick, poseStack, blockEntityBuffers, light, OverlayTexture.NO_OVERLAY);
                    }
                } catch (Exception e) {
                    LOGGER.debug("[Planarium View] Bloc special ignore", e);
                }
                poseStack.popPose();
            }
            drawEntities(data, partialTick, camera, poseStack, radius);
            blockEntityBuffers.endBatch();
        } finally {
            RenderSystem.setShaderFogStart(fogStart);
            RenderSystem.setShaderFogEnd(fogEnd);
            RenderSystem.setShaderFogShape(fogShape);
        }
    }
}

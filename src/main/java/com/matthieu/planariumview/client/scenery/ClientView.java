package com.matthieu.planariumview.client.scenery;

import com.matthieu.planariumview.LogFilter;
import com.matthieu.planariumview.client.jar.JarInteriorBiome;
import com.matthieu.planariumview.client.jar.WallColors;
import com.matthieu.planariumview.client.mini.MiniLight;
import com.matthieu.planariumview.client.mini.MiniParticles;
import com.matthieu.planariumview.client.sky.LightningView;
import com.matthieu.planariumview.client.sky.LunarClient;
import com.matthieu.planariumview.client.sky.SkySync;
import com.matthieu.planariumview.client.sky.WeatherView;
import com.matthieu.planariumview.client.sound.OutsideAmbience;
import com.matthieu.planariumview.client.sound.OutsideRecordClient;
import com.matthieu.planariumview.client.sound.OutsideSoundClient;
import com.matthieu.planariumview.compat.AmbersolCompat;
import com.matthieu.planariumview.compat.ColorCompat;
import com.matthieu.planariumview.compat.PuddlesCompat;
import com.matthieu.planariumview.compat.SeasonColors;
import com.matthieu.planariumview.compat.SnowRealMagicCompat;
import com.matthieu.planariumview.config.Config;
import com.matthieu.planariumview.config.ConfigScreen;
import com.matthieu.planariumview.network.OutsideViewDeltaPayload;
import com.matthieu.planariumview.network.OutsideViewPayload;
import com.matthieu.planariumview.network.SoundPrefsPayload;
import com.matthieu.planariumview.network.SubLevelPrefsPayload;
import com.matthieu.planariumview.network.ViewTogglePayload;

import com.hollingsworth.arsnouveau.client.renderer.LiquidBlockVertexConsumer;
import com.hollingsworth.arsnouveau.client.renderer.tile.PlanariumRenderer;
import com.hollingsworth.arsnouveau.client.renderer.tile.PlanariumRenderer.StructureRenderData;
import com.hollingsworth.arsnouveau.client.renderer.world.CulledStatePos;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
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
import java.util.Collection;
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
    public static final float ARS_SCALE = (1.0f - 2.0f * PAD) / 32.0f;
    public static final float ARS_OFFSET = PAD + ((1.0f - 2.0f * PAD) - 32 * ARS_SCALE) * 0.5f;
    /** Facteur d'agrandissement : 1 bloc du monde = ~32 blocs dans la poche. */
    public static final float GIANT = 1.0f / ARS_SCALE;
    /**
     * Dans le rendu d'Ars, le bloc de poche a la hauteur y apparait a (y + 25) / 32 dans le Planarium.
     * On applique la transformation inverse.
     */
    public static final float Y_SHIFT = 25.0f;

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
    /** Photo brute (NBT) de la derniere reception : sert a trouver les blocs qui ont change, sans tout relire. */
    private static net.minecraft.nbt.CompoundTag lastTemplateTag;
    private static byte[] lastLight;
    /** Blocs changes recus mais pas encore appliques au decor (null : rien en attente). */
    private static Map<BlockPos, BlockChange> pendingPatch;
    private static byte[] pendingPatchLight;
    private static OutsideViewPayload pendingPatchInfo;
    /** Temps passe a comparer les photos recues depuis la derniere correction (pour le log). */
    private static long pendingDiffNanos;
    /**
     * Les photos sont comparees sur un fil a part (10 a 20 ms pour 130 000 blocs), une a la fois et dans l'ordre.
     * Le numero de generation change a chaque reconstruction complete : les comparaisons encore en cours
     * deviennent alors inutiles et leur resultat est ignore.
     */
    private static final java.util.concurrent.ExecutorService DIFF_THREAD =
            java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "Planarium Panorama - comparaison du decor");
                thread.setDaemon(true);
                return thread;
            });
    private static int diffGeneration;
    /**
     * Blocs recus par envoi partiel depuis la derniere photo complete : la photo gardee ne les contient pas.
     * Ils sont reappliques apres une reconstruction complete, et reverifies contre la photo complete suivante.
     */
    private static final Map<BlockPos, BlockChange> deltaOverlay = new HashMap<>();
    /** Au-dela de ce nombre de blocs changes, on reconstruit tout le decor plutot que de le corriger. */
    private static final int MAX_PATCH_BLOCKS = 4096;
    /** Blocs du decor par position et par section, gardes pour corriger le decor sans le reconstruire. */
    private static Map<BlockPos, CulledStatePos> statePosByPos = new HashMap<>();
    private static Map<SectionPos, List<CulledStatePos>> currentSectionStates = new HashMap<>();

    /** Nouvel etat d'un bloc ({@code state} null : le bloc a disparu de la photo). */
    private record BlockChange(BlockState state, net.minecraft.nbt.CompoundTag nbt) {
    }
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
    private static int sortCounter;
    private static final Map<BlockPos, Integer> blockEntityLight = new java.util.HashMap<>();
    /** Lumiere des entites du decor, calculee avec la source CL du faux monde et reutilisee pendant le rendu. */
    private static final Map<net.minecraft.world.entity.Entity, Integer> sceneryEntityLight = new java.util.IdentityHashMap<>();

    private record SectionPos(int x, int y, int z) {
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

    private static final class SectionMesh {
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

    private ClientView() {
    }

    public static void init(net.neoforged.fml.ModContainer container) {
        container.registerExtensionPoint(net.neoforged.neoforge.client.gui.IConfigScreenFactory.class,
                (modContainer, parent) -> ConfigScreen.create(parent));
        NeoForge.EVENT_BUS.addListener(ClientView::onRenderStage);
        NeoForge.EVENT_BUS.addListener(ClientView::onLoggingOut);
        NeoForge.EVENT_BUS.addListener(ClientView::sendToggle);
        NeoForge.EVENT_BUS.addListener(ClientView::tickSceneryAmbience);
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
        boolean changedDimension = currentDim != null && !currentDim.equals(payload.pocketDim());
        boolean changedRadius = currentDim != null && currentRadius != payload.radius();
        if (changedDimension || changedRadius || (currentDim == null && renderData != null)) {
            disposeRender();
        }
        // Meme decor qu'avant (meme endroit, memes biomes) : on ne corrigera que les blocs qui ont change.
        // La comparaison se fait hors du fil de rendu ; le resultat revient ensuite par onDiffReady.
        boolean patchable = renderData != null && pendingTemplate == null && lastTemplateTag != null && latestInfo != null
                && java.util.Objects.equals(latestInfo.origin(), payload.origin())
                && java.util.Objects.equals(latestInfo.biomePalette(), payload.biomePalette())
                && java.util.Arrays.equals(latestInfo.biomes(), payload.biomes());
        currentDim = payload.pocketDim();
        currentRadius = payload.radius();
        net.minecraft.nbt.CompoundTag base = lastTemplateTag;
        // La nouvelle photo complete remplace les envois partiels recus avant elle ; leurs positions sont
        // reverifiees contre cette photo (un bloc a pu revenir a son etat d'avant).
        Set<BlockPos> overlayPositions = new HashSet<>(deltaOverlay.keySet());
        deltaOverlay.clear();
        lastTemplateTag = payload.template();
        latestInfo = payload;
        WeatherView.set(payload);
        lastLight = payload.light();
        if (patchable) {
            lastTemplate = null;
            int generation = diffGeneration;
            net.minecraft.nbt.CompoundTag next = payload.template();
            try {
                DIFF_THREAD.execute(() -> {
                    long start = System.nanoTime();
                    Map<BlockPos, BlockChange> changes = null;
                    try {
                        changes = diffTemplates(base, next, overlayPositions);
                    } catch (Exception e) {
                        LOGGER.debug("[Planarium View] Comparaison des photos impossible; reconstruction complete", e);
                    }
                    Map<BlockPos, BlockChange> result = changes;
                    long nanos = System.nanoTime() - start;
                    Minecraft.getInstance().execute(() -> onDiffReady(generation, payload.pocketDim(), payload, result, nanos));
                });
                return;
            } catch (java.util.concurrent.RejectedExecutionException e) {
                // Fil de comparaison indisponible : reconstruction complete ci-dessous.
            }
        }
        scheduleFullBuild();
    }

    /** Reconstruction complete a partir de la derniere photo recue ; les comparaisons en cours sont abandonnees. */
    private static void scheduleFullBuild() {
        diffGeneration++;
        if (lastTemplateTag == null) {
            return;
        }
        StructureTemplate template = loadTemplate(lastTemplateTag);
        pendingPatch = null;
        pendingPatchLight = null;
        pendingPatchInfo = null;
        pendingDiffNanos = 0L;
        reapplyOverlay();
        pendingTemplate = template;
        pendingLight = lastLight;
        pendingInfo = latestInfo;
        lastTemplate = template;
    }

    /** Resultat d'une comparaison (fil principal, dans l'ordre de reception des photos). */
    private static void onDiffReady(int generation, ResourceLocation dim, OutsideViewPayload payload,
                                    Map<BlockPos, BlockChange> changes, long nanos) {
        if (generation != diffGeneration || currentDim == null || !currentDim.equals(dim)) {
            return;
        }
        if (changes == null || (pendingPatch != null && pendingPatch.size() + changes.size() > MAX_PATCH_BLOCKS)) {
            scheduleFullBuild();
            return;
        }
        if (pendingPatch == null) {
            pendingPatch = new java.util.LinkedHashMap<>();
            // Envoi partiel (sans photo) : la lumiere et les infos restent celles de la derniere photo complete.
            pendingPatchLight = lastLight;
            pendingPatchInfo = latestInfo;
        }
        pendingPatch.putAll(changes);
        if (payload != null) {
            pendingPatchLight = payload.light();
            pendingPatchInfo = payload;
        }
        pendingDiffNanos += nanos;
    }

    /** Apres une reconstruction complete depuis la photo gardee : reappliquer les blocs recus depuis par envoi partiel. */
    private static void reapplyOverlay() {
        if (deltaOverlay.isEmpty()) {
            return;
        }
        pendingPatch = new java.util.LinkedHashMap<>(deltaOverlay);
        pendingPatchLight = lastLight;
        pendingPatchInfo = latestInfo;
    }

    /** Blocs changes envoyes seuls par le serveur (voir {@link OutsideViewDeltaPayload}). */
    public static void receiveDelta(OutsideViewDeltaPayload payload) {
        if (currentDim == null || !currentDim.equals(payload.pocketDim()) || currentRadius != payload.radius()
                || latestInfo == null || !java.util.Objects.equals(latestInfo.origin(), payload.origin())
                || lastTemplateTag == null) {
            // Pas de photo complete correspondante ici : la prochaine photo complete remettra tout a jour.
            return;
        }
        Map<BlockPos, BlockChange> changes = new java.util.LinkedHashMap<>();
        try {
            net.minecraft.nbt.ListTag blocks = payload.changes().getList("blocks", net.minecraft.nbt.Tag.TAG_COMPOUND);
            for (int i = 0; i < blocks.size(); i++) {
                net.minecraft.nbt.CompoundTag entry = blocks.getCompound(i);
                BlockPos pos = readPos(entry);
                if (pos == null) continue;
                BlockState state = entry.contains("state", net.minecraft.nbt.Tag.TAG_COMPOUND)
                        ? net.minecraft.nbt.NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), entry.getCompound("state"))
                        : null;
                net.minecraft.nbt.CompoundTag nbt = entry.contains("nbt", net.minecraft.nbt.Tag.TAG_COMPOUND)
                        ? entry.getCompound("nbt") : null;
                changes.put(pos, new BlockChange(state, nbt));
            }
        } catch (Exception e) {
            LOGGER.debug("[Planarium View] Blocs changes illisibles; ignores jusqu'a la prochaine photo", e);
            return;
        }
        if (changes.isEmpty()) {
            return;
        }
        deltaOverlay.putAll(changes);
        // Passe par le meme fil que les comparaisons, pour rester dans l'ordre avec les photos deja en cours.
        int generation = diffGeneration;
        ResourceLocation dim = payload.pocketDim();
        try {
            DIFF_THREAD.execute(() -> Minecraft.getInstance().execute(
                    () -> onDiffReady(generation, dim, null, changes, 0L)));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            onDiffReady(generation, dim, null, changes, 0L);
        }
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
     * Rayon detaille affiche : celui du serveur. Le jeu dessine toujours assez loin pour tout le decor (voir
     * sceneryDepthFar), donc la distance d'affichage ne le limite plus, sauf avec le brouillard "Distance d'affichage" :
     * seulement ce que ce brouillard laisse voir. Il finit a la distance d'affichage en blocs normaux, et un bloc du
     * decor en fait 32 : environ la moitie de la distance d'affichage, plus un bloc de marge.
     */
    /**
     * Zone ou le decor detaille garde sa vie (particules de tous les blocs, mods compris, pluie, blocs speciaux,
     * entites). Les blocs detailles s'arretent au cylindre (voir DetailShape), mais les coins du cube restent dans le faux
     * monde : quand l'anneau lointain dessine ces coins, on y garde tout (l'anneau n'a ni particules ni coffres). Sans
     * anneau, les coins sont vides : on s'arrete au cylindre.
     */
    public static boolean liveArea(double dx, double dz, double view) {
        if (FarRing.visible() || !roundDetail) return Math.abs(dx) <= view && Math.abs(dz) <= view;
        return com.matthieu.planariumview.DetailShape.horizontal(dx, dz, view);
    }

    /**
     * Vrai si le serveur connait le decor detaille en cylindre (meme version : il connait aussi les Planariums voisins).
     * Un ancien serveur exclut encore tout le cube de son anneau lointain : on garde alors le cube, sinon les 4 coins
     * seraient vides. Le serveur fait le meme choix de son cote (voir FarRingServer).
     */
    private static boolean roundDetail;

    private static void updateRoundDetail() {
        net.minecraft.client.multiplayer.ClientPacketListener connection = Minecraft.getInstance().getConnection();
        roundDetail = connection != null
                && connection.hasChannel(com.matthieu.planariumview.network.NeighborPocketsPayload.TYPE);
    }

    /** Colonne (dx, dz) du decor detaille : cercle (voir DetailShape), ou carre avec un ancien serveur. */
    public static boolean inDetail(int dx, int dz, int view) {
        if (!roundDetail) return Math.abs(dx) <= view && Math.abs(dz) <= view;
        return com.matthieu.planariumview.DetailShape.horizontal(dx, dz, view);
    }

    public static int viewRadius() {
        int radius = currentRadius;
        if (Config.FOG.get() == Config.Fog.RENDER_DISTANCE) {
            int renderDistance = Minecraft.getInstance().options.getEffectiveRenderDistance();
            int visible = (int) Math.ceil(renderDistance * 16.0 / GIANT) + 1;
            radius = Math.min(radius, visible);
        }
        return Math.max(1, radius);
    }

    /** Bord vise du decor : celui de l'anneau lointain s'il est la, sinon celui du rayon detaille. */
    private static int targetEdge() {
        if (FlightView.active()) {
            return Config.FOG.get() == Config.Fog.RENDER_DISTANCE
                    ? Math.min(viewRadius(), FlightView.visibleRadius()) : FlightView.visibleRadius();
        }
        return FarRing.visible() ? FarRing.radius() : viewRadius();
    }

    private static float shownEdge = -1.0F;
    private static long shownEdgeNanos;

    /**
     * Bord du decor dessine (brouillard, mur, dome), en blocs du monde. Quand l'anneau lointain arrive (ou part), le
     * bord glisse en environ une seconde au lieu de sauter d'un coup.
     */
    static float edgeRadius() {
        int target = targetEdge();
        long now = System.nanoTime();
        if (shownEdge < 0.0F) {
            shownEdge = target;
        } else if (target < shownEdge) {
            // A receding frontier can expose missing terrain; only expansion may be smoothed.
            shownEdge = target;
        } else if (shownEdge != target) {
            float seconds = Math.min(0.25F, (now - shownEdgeNanos) / 1.0e9F);
            shownEdge += (target - shownEdge) * (1.0F - (float) Math.exp(-seconds / 0.35F));
            if (Math.abs(target - shownEdge) < 0.05F) {
                shownEdge = target;
            }
        }
        shownEdgeNanos = now;
        return shownEdge;
    }

    /**
     * Distance de dessin necessaire dans le bocal pour tout le decor (et le mur de brouillard ou le dome a son bord),
     * 0 sans decor. Voir GameRendererFarMixin.
     */
    public static float sceneryDepthFar() {
        Minecraft mc = Minecraft.getInstance();
        if (renderData == null || currentDim == null || mc.level == null || !Config.enabled()
                || !currentDim.equals(mc.level.dimension().location())) {
            return 0.0F;
        }
        // Meme distance tant que le bocal a un anneau, visible ou non : une distance qui change provoquait une saccade.
        int far = Config.FOG.get() == Config.Fog.RENDER_DISTANCE ? 0
                : Math.max(FarRing.knownRadius, FlightView.knownRadius());
        int edge = Math.max(Math.max(targetEdge(), far), (int) Math.ceil(Math.max(0.0F, shownEdge)));
        return (edge * GIANT + GIANT + 4.0F) / 0.6F;
    }

    /** La derniere photo normale recue est construite (fin du vol, voir FlightView). */
    static boolean nearCaughtUp() {
        return renderData != null && pendingTemplate == null && pendingPatch == null && pendingSectionData == null
                && pendingSections.isEmpty() && lastInfo != null && lastInfo == latestInfo;
    }

    /** Dimension de poche du decor actuel (null sans decor). */
    static ResourceLocation currentDimension() {
        return currentDim;
    }

    /** Position du Planarium dans le monde pour le decor proche dessine (null sans decor). */
    static BlockPos nearCenter() {
        return lastInfo == null ? null : lastInfo.origin().offset(currentRadius, currentRadius, currentRadius);
    }

    static int nearRadius() {
        return currentRadius;
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
        if (lastTemplate == null && lastTemplateTag != null) {
            lastTemplate = loadTemplate(lastTemplateTag);
        }
        if (lastTemplate != null && currentDim != null) {
            pendingPatch = null;
            pendingDiffNanos = 0L;
            diffGeneration++;
            reapplyOverlay();
            pendingTemplate = lastTemplate;
            pendingLight = lastLight;
            pendingInfo = latestInfo;
            forceAllSections = true;
        }
        MiniLight.rebuildAll();
    }

    /**
     * Un nouvel anneau lointain est arrive : le decor proche est reconstruit, pour que son bord se raccorde a l'anneau
     * (l'eau du bord ne s'abaisse plus vers le vide). Les sections actuelles restent visibles pendant ce temps.
     */
    static void farRingArrived() {
        if (renderData == null || lastTemplate == null && lastTemplateTag == null || currentDim == null) {
            return;
        }
        if (lastTemplate == null) {
            lastTemplate = loadTemplate(lastTemplateTag);
        }
        pendingPatch = null;
        pendingDiffNanos = 0L;
        diffGeneration++;
        reapplyOverlay();
        pendingTemplate = lastTemplate;
        pendingLight = lastLight;
        pendingInfo = latestInfo;
        forceAllSections = true;
    }

    /** Dernier etat de l'interrupteur general envoye au serveur (null : rien d'envoye sur cette connexion). */
    private static Boolean sentEnabled;

    /** Previent le serveur quand l'interrupteur general change (et a la connexion) : OFF, il ne capture plus rien pour nous. */
    private static final net.minecraft.util.RandomSource AMBIENT_RANDOM = net.minecraft.util.RandomSource.create();
    /** Blocs dont l'ambiance a leve une erreur dans le faux monde : on ne les essaie plus. */
    private static final Set<net.minecraft.world.level.block.Block> AMBIENT_BROKEN = new HashSet<>();

    /**
     * Sons d'ambiance des blocs du decor (feu qui crepite, lave, eau qui coule, portail...). Le serveur ne les joue
     * jamais : comme Minecraft autour du joueur, on tire chaque tick des blocs au hasard autour du Planarium dans le
     * faux monde du decor, et on leur fait jouer leur ambiance (les particules sont ignorees).
     */
    private static void tickSceneryAmbience(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        com.matthieu.planariumview.client.jar.SubLevelLight.tick();
        OutsideFluidView.tick();
        Minecraft mc = Minecraft.getInstance();
        // Cascades du decor avec les sons de Dynamic Surroundings (integration DS et sons d'ambiance du decor).
        // En vol, le decor normal est cache (voir FlightView) : pas de sons, flaques ni particules a son ancienne place.
        boolean insideJar = mc.level != null && currentDim != null && mc.level.dimension().location().equals(currentDim);
        boolean inJar = insideJar && !FlightView.active();
        com.matthieu.planariumview.client.sound.MachineSounds.tick(inJar ? sceneryWorld() : null, currentRadius,
                lastInfo == null ? null : lastInfo.origin(), insideJar);
        CopyParticles.tick();
        if (!mc.isPaused()) {
            PuddlesCompat.tick(inJar && Config.enabled() ? sceneryWorld() : null, lastInfo);
        }
        com.matthieu.planariumview.client.sound.SceneryWaterfalls.tick(
                !mc.isPaused() && inJar && Config.sceneryAmbience() && Config.outsideAmbience() ? sceneryWorld() : null,
                currentRadius);
        if (!mc.isPaused()) {
            SceneryParticles.tick();
            if (Config.sceneryParticles()) {
                MiniParticles.tick();
            }
        }
        boolean sounds = Config.sceneryAmbience();
        boolean particles = Config.sceneryParticles();
        if (mc.level == null || mc.isPaused() || renderData == null || currentDim == null || (!sounds && !particles)
                || !inJar
                || !(renderData.fakeRenderingWorld instanceof LitRenderingWorld lit)) {
            return;
        }
        int radius = currentRadius;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        LitRenderingWorld.captureAmbientSounds(sounds);
        LitRenderingWorld.captureSceneryParticles(particles
                ? (world, options, x, y, z, vx, vy, vz) -> onSceneryParticle(options, x, y, z, vx, vy, vz) : null);
        try {
            // Comme ClientLevel.animateTick : 667 tirages a 16 blocs et 667 a 32 blocs du point d'ecoute.
            windChecks = particles;
            for (int i = 0; i < 667; i++) {
                ambientTick(lit, radius, 16, pos);
                ambientTick(lit, radius, 32, pos);
            }
            windChecks = false;
            if (particles) {
                SceneryBubbles.tick(lit, radius);
            }
            if (particles) {
                campfireSmoke(lit, radius);
            }
        } finally {
            LitRenderingWorld.captureAmbientSounds(false);
            LitRenderingWorld.captureSceneryParticles(null);
        }
    }

    private static void ambientTick(LitRenderingWorld lit, int radius, int range, BlockPos.MutableBlockPos pos) {
        int x = radius + AMBIENT_RANDOM.nextInt(range) - AMBIENT_RANDOM.nextInt(range);
        int y = radius + AMBIENT_RANDOM.nextInt(range) - AMBIENT_RANDOM.nextInt(range);
        int z = radius + AMBIENT_RANDOM.nextInt(range) - AMBIENT_RANDOM.nextInt(range);
        // Le Planarium lui-meme et le bloc au-dessus ne font pas partie du decor.
        if (x == radius && z == radius && (y == radius || y == radius + 1)) {
            return;
        }
        pos.set(x, y, z);
        BlockState state = lit.getBlockState(pos);
        // Rafales de Windy, comme Minecraft le lui permet autour du joueur : sur les blocs pas pleins (souvent de l'air).
        if (windChecks && !state.isCollisionShapeFullBlock(lit, pos)) {
            OutsideViewPayload info = lastInfo;
            com.matthieu.planariumview.compat.WindyCompat.tryWind(lit, pos.immutable(), radius,
                    info != null ? info.origin() : null, AMBIENT_RANDOM);
        }
        if (state.isAir() || AMBIENT_BROKEN.contains(state.getBlock())) {
            return;
        }
        // Jets de bulles de Dynamic Surroundings (integration DS).
        if (windChecks && Config.outsideAmbience()) {
            SceneryBubbles.tryStart(lit, pos, state);
            OutsideViewPayload info = lastInfo;
            com.matthieu.planariumview.compat.DsFirefliesCompat.animate(lit, pos, state, AMBIENT_RANDOM,
                    info != null ? info.origin() : BlockPos.ZERO);
        }
        animateBlock(lit, pos.immutable(), state);
    }

    /** Vrai pendant le tirage des blocs du decor si les particules du decor sont actives (vent de Windy). */
    private static boolean windChecks;

    /**
     * Ambiance d'un bloc d'un faux monde (decor geant ou miniature), comme ClientLevel.doAnimateTick : celle du bloc,
     * de son liquide, et les gouttes sous un plafond de liquide.
     */
    public static void animateBlock(LitRenderingWorld lit, BlockPos at, BlockState state) {
        if (state.isAir() || AMBIENT_BROKEN.contains(state.getBlock())) {
            return;
        }
        boolean leaves = false;
        try {
            // Feuilles de Falling Leaves (integration).
            leaves = com.matthieu.planariumview.compat.FallingLeavesCompat.animate(lit, at, state, AMBIENT_RANDOM);
            state.getBlock().animateTick(state, lit, at, AMBIENT_RANDOM);
            FluidState fluid = state.getFluidState();
            if (!fluid.isEmpty()) {
                fluid.animateTick(lit, at, AMBIENT_RANDOM);
                // Gouttes sous un plafond de lave ou d'eau (ClientLevel.doAnimateTick, pas le bloc lui-meme).
                net.minecraft.core.particles.ParticleOptions drip = fluid.getDripParticle();
                if (drip != null && AMBIENT_RANDOM.nextInt(10) == 0) {
                    boolean sturdy = state.isFaceSturdy(lit, at, net.minecraft.core.Direction.DOWN);
                    BlockPos below = at.below();
                    dripUnder(lit, below, lit.getBlockState(below), drip, sturdy);
                }
            }
        } catch (Throwable t) {
            if (leaves && t instanceof ClassCastException) {
                // Falling Leaves sur un feuillage (voir LeavesBlockMixin) : ses feuilles sont deja faites.
                return;
            }
            // Un bloc modde qui suppose un vrai monde : on l'ignore pour le reste de la session.
            AMBIENT_BROKEN.add(state.getBlock());
            LOGGER.debug("[Planarium Panorama] Ambiance du bloc {} ignoree dans le decor", state.getBlock(), t);
        }
    }

    /** Comme ClientLevel.trySpawnDripParticles : une goutte qui pend sous le bloc sous le liquide. */
    private static void dripUnder(LitRenderingWorld lit, BlockPos pos, BlockState state,
                                  net.minecraft.core.particles.ParticleOptions drip, boolean sturdy) {
        if (!state.getFluidState().isEmpty()) {
            return;
        }
        net.minecraft.world.phys.shapes.VoxelShape shape = state.getCollisionShape(lit, pos);
        double top = shape.max(net.minecraft.core.Direction.Axis.Y);
        if (top < 1.0) {
            if (sturdy) {
                dripAt(lit, pos.getX(), pos.getX() + 1, pos.getZ(), pos.getZ() + 1, pos.getY() + 1 - 0.05, drip);
            }
        } else if (!state.is(net.minecraft.tags.BlockTags.IMPERMEABLE)) {
            double bottom = shape.min(net.minecraft.core.Direction.Axis.Y);
            if (bottom > 0.0) {
                dripUnder(lit, pos, shape, pos.getY() + bottom - 0.05, drip);
            } else {
                BlockPos below = pos.below();
                BlockState under = lit.getBlockState(below);
                if (under.getCollisionShape(lit, below).max(net.minecraft.core.Direction.Axis.Y) < 1.0
                        && under.getFluidState().isEmpty()) {
                    dripUnder(lit, pos, shape, pos.getY() - 0.05, drip);
                }
            }
        }
    }

    private static void dripUnder(LitRenderingWorld lit, BlockPos pos, net.minecraft.world.phys.shapes.VoxelShape shape,
                                  double y, net.minecraft.core.particles.ParticleOptions drip) {
        dripAt(lit, pos.getX() + shape.min(net.minecraft.core.Direction.Axis.X),
                pos.getX() + shape.max(net.minecraft.core.Direction.Axis.X),
                pos.getZ() + shape.min(net.minecraft.core.Direction.Axis.Z),
                pos.getZ() + shape.max(net.minecraft.core.Direction.Axis.Z), y, drip);
    }

    private static void dripAt(LitRenderingWorld lit, double x1, double x2, double z1, double z2, double y,
                               net.minecraft.core.particles.ParticleOptions drip) {
        lit.addParticle(drip, net.minecraft.util.Mth.lerp(AMBIENT_RANDOM.nextDouble(), x1, x2), y,
                net.minecraft.util.Mth.lerp(AMBIENT_RANDOM.nextDouble(), z1, z2), 0.0, 0.0, 0.0);
    }

    /** Augmente a chaque changement du decor (les feux de camp sont alors recherches de nouveau). */
    private static int sceneryVersion;
    private static int campfireVersion = -1;
    private static List<BlockPos> campfires = List.of();

    /**
     * Fumee des feux de camp allumes du decor. Minecraft la fait chaque tick depuis le feu de camp lui-meme
     * (CampfireBlockEntity.particleTick), pas depuis les blocs tires au hasard : on refait la meme chose.
     */
    private static void campfireSmoke(LitRenderingWorld lit, int radius) {
        if (campfireVersion != sceneryVersion) {
            campfireVersion = sceneryVersion;
            campfires = findCampfires(statePosByPos.values());
        }
        int view = viewRadius();
        for (BlockPos at : campfires) {
            if (!liveArea(at.getX() - radius, at.getZ() - radius, view)) {
                continue;
            }
            campfireTick(lit, at, lit.getBlockState(at));
        }
    }

    /** Positions des feux de camp parmi ces blocs (allumes ou non). */
    public static List<BlockPos> findCampfires(Iterable<CulledStatePos> states) {
        List<BlockPos> found = new ArrayList<>();
        for (CulledStatePos statePos : states) {
            BlockState state = statePos.state;
            if (state != null && state.getBlock() instanceof net.minecraft.world.level.block.CampfireBlock
                    && state.hasProperty(net.minecraft.world.level.block.CampfireBlock.LIT)) {
                found.add(statePos.pos.immutable());
            }
        }
        return found;
    }

    /** Un tick de fumee d'un feu de camp allume, comme CampfireBlockEntity.particleTick. */
    public static void campfireTick(LitRenderingWorld lit, BlockPos at, BlockState state) {
        if (!(state.getBlock() instanceof net.minecraft.world.level.block.CampfireBlock)
                || !state.getValue(net.minecraft.world.level.block.CampfireBlock.LIT)
                || AMBIENT_RANDOM.nextFloat() >= 0.11F) {
            return;
        }
        boolean signal = state.getValue(net.minecraft.world.level.block.CampfireBlock.SIGNAL_FIRE);
        int count = AMBIENT_RANDOM.nextInt(2) + 2;
        try {
            for (int i = 0; i < count; i++) {
                net.minecraft.world.level.block.CampfireBlock.makeParticles(lit, at, signal, false);
            }
        } catch (Throwable t) {
            LOGGER.debug("[Planarium Panorama] Fumee de feu de camp ignoree dans le decor", t);
        }
    }

    /** Faux monde du decor geant et rayon du Planarium (pour les particules du decor), ou null. */
    public static LitRenderingWorld sceneryWorld() {
        return renderData != null && renderData.fakeRenderingWorld instanceof LitRenderingWorld lit ? lit : null;
    }

    /**
     * Liquide du decor a la position de la camera : Planarium pose au sol (et repli pour un serveur sans l'echantillon
     * de liquide des structures Sable).
     */
    static FluidState outsideFluid(Vec3 camera) {
        LitRenderingWorld world = sceneryWorld();
        if (world == null || lastInfo == null) return net.minecraft.world.level.material.Fluids.EMPTY.defaultFluidState();
        org.joml.Vector3d local = new org.joml.Vector3d(camera.x / GIANT + ARS_OFFSET,
                (camera.y + Y_SHIFT) / GIANT + ARS_OFFSET, camera.z / GIANT + ARS_OFFSET);
        org.joml.Matrix4d toCapture = SubLevelView.sourceFrame(lastInfo.origin(), currentRadius).invert();
        FluidState fluid = capturedFluid(world, toCapture, local);
        boolean inPlanarium = local.x >= 0 && local.x < 1 && local.y >= 0 && local.y < 1 && local.z >= 0 && local.z < 1;
        if (!fluid.isEmpty() || !inPlanarium) return fluid;
        // Le bas du bocal est dans la case du Planarium, qui n'est jamais "waterlogged" : elle compte comme remplie
        // si ses voisines le sont a la meme hauteur, sinon comme la case au-dessus (sinon l'eau clignote en montant).
        double[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (double[] side : sides) {
            fluid = capturedFluid(world, toCapture, new org.joml.Vector3d(local).add(side[0], 0, side[1]));
            if (!fluid.isEmpty()) return fluid;
        }
        return capturedFluid(world, toCapture, new org.joml.Vector3d(local.x, 1.05, local.z));
    }

    /** Liquide de la photo du decor en un point (coordonnees relatives au Planarium), avec la hauteur de sa surface. */
    private static FluidState capturedFluid(LitRenderingWorld world, org.joml.Matrix4d toCapture, org.joml.Vector3d local) {
        org.joml.Vector3d point = toCapture.transformPosition(new org.joml.Vector3d(local));
        BlockPos pos = BlockPos.containing(point.x, point.y, point.z);
        FluidState fluid = world.getCapturedBlockState(pos).getFluidState();
        return !fluid.isEmpty() && point.y < pos.getY() + fluid.getHeight(world, pos)
                ? fluid : net.minecraft.world.level.material.Fluids.EMPTY.defaultFluidState();
    }

    static int sceneryVersion() {
        return sceneryVersion;
    }

    static int sceneryRadius() {
        return currentRadius;
    }

    /**
     * Son d'ambiance d'un bloc du decor (positions du gabarit). On l'attenue comme si on l'ecoutait a la place du
     * Planarium, puis on le joue dans sa direction dans le decor geant.
     */
    /**
     * Particule d'ambiance d'un bloc du decor (positions du gabarit) : creee a sa place dans le decor geant, si elle est
     * dans le rayon de vue (au-dela, le decor n'est pas dessine). Elle prend la lumiere du decor a cet endroit.
     */
    public static void onSceneryParticle(net.minecraft.core.particles.ParticleOptions options, double x, double y, double z,
                                  double vx, double vy, double vz) {
        if (options == null || !SceneryParticles.allowed()) {
            return;
        }
        int radius = currentRadius;
        int view = viewRadius();
        double dx = x - radius;
        double dy = y - radius;
        double dz = z - radius;
        if (!liveArea(dx - 0.5, dz - 0.5, view + 0.5)) {
            return;
        }
        int light = net.minecraft.client.renderer.LightTexture.pack(15, 15);
        if (renderData != null && renderData.fakeRenderingWorld instanceof LitRenderingWorld lit) {
            BlockPos at = BlockPos.containing(x, y, z);
            light = net.minecraft.client.renderer.LightTexture.pack(
                    lit.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, at),
                    lit.getBrightness(net.minecraft.world.level.LightLayer.SKY, at));
        }
        LitRenderingWorld world = sceneryWorld();
        if (world != null) {
            SceneryParticles.spawn(SceneryParticles.giantSpace(world, radius), options, x, y, z, vx, vy, vz, light);
        }
    }

    /** Explosion du dehors (position par rapport au Planarium) : ses particules, si elle est dans le rayon de vue. */
    public static void onOutsideExplosion(double dx, double dy, double dz) {
        int view = viewRadius();
        if (!Config.sceneryParticles() || !liveArea(dx - 0.5, dz - 0.5, view + 2.0)) {
            return;
        }
        LitRenderingWorld world = sceneryWorld();
        if (world != null) {
            int radius = currentRadius;
            SceneryParticles.explosion(SceneryParticles.giantSpace(world, radius), dx + radius, dy + radius, dz + radius);
        }
    }

    static void onSceneryAmbientSound(double x, double y, double z, net.minecraft.sounds.SoundEvent sound,
                                      net.minecraft.sounds.SoundSource source, float volume, float pitch) {
        if (sound == null || volume <= 0.0F) {
            return;
        }
        int radius = currentRadius;
        double dx = x - radius;
        double dy = y - radius;
        double dz = z - radius;
        double distance = Math.sqrt((dx - 0.5) * (dx - 0.5) + (dy - 0.5) * (dy - 0.5) + (dz - 0.5) * (dz - 0.5));
        double range = sound.getRange(volume);
        if (!(range > 0.0) || distance >= range) {
            return;
        }
        OutsideSoundClient.play(sound, source, (float) (volume * (1.0 - distance / range)) * OutsideSoundClient.AMBIENCE_VOLUME,
                pitch, dx, dy, dz, true);
    }

    /** Dernieres options des sons du monde exterieur envoyees au serveur (null : rien d'envoye sur cette connexion). */
    private static SoundPrefsPayload sentSoundPrefs;
    private static Boolean sentSubLevelViews;
    /** Nombre de Planariums voisins envoye au serveur (null : rien d'envoye sur cette connexion). */
    private static Integer sentNeighbors;

    private static void sendToggle(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        net.minecraft.client.multiplayer.ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null || Minecraft.getInstance().player == null) {
            return;
        }
        boolean subLevels = Config.subLevelViews();
        if (sentSubLevelViews == null || sentSubLevelViews != subLevels) {
            sentSubLevelViews = subLevels;
            if (!subLevels) SubLevelView.clearStructures();
            try {
                if (connection.hasChannel(SubLevelPrefsPayload.TYPE)) {
                    net.neoforged.neoforge.network.PacketDistributor.sendToServer(new SubLevelPrefsPayload(subLevels));
                }
            } catch (Exception e) {
                LOGGER.debug("[Planarium Panorama] Sub-level preference could not be sent", e);
            }
        }
        int neighbors = Config.neighborPlanariums();
        if (sentNeighbors == null || sentNeighbors != neighbors) {
            sentNeighbors = neighbors;
            try {
                if (connection.hasChannel(com.matthieu.planariumview.network.NeighborPrefsPayload.TYPE)) {
                    net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                            new com.matthieu.planariumview.network.NeighborPrefsPayload(neighbors));
                }
            } catch (Exception e) {
                LOGGER.debug("[Planarium Panorama] Neighbor preference could not be sent", e);
            }
        }
        SoundPrefsPayload prefs = new SoundPrefsPayload(Config.worldSounds(), Config.mobSounds(), Config.blockSounds(),
                Config.explosionSounds());
        if (!prefs.equals(sentSoundPrefs)) {
            sentSoundPrefs = prefs;
            try {
                if (connection.hasChannel(SoundPrefsPayload.TYPE)) {
                    net.neoforged.neoforge.network.PacketDistributor.sendToServer(prefs);
                }
            } catch (Exception e) {
                LOGGER.debug("[Planarium Panorama] Options des sons non envoyees au serveur", e);
            }
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
        sentSoundPrefs = null;
        sentSubLevelViews = null;
        sentNeighbors = null;
        com.matthieu.planariumview.client.sound.MachineSounds.clear();
        OutsideRecordClient.clear();
        SceneryParticles.clear();
        SceneryBubbles.clear();
        MiniParticles.clear();
        dispose();
        MiniLight.clear();
        com.matthieu.planariumview.client.mini.MiniEntities.clear();
        SubLevelView.clear();
        LunarClient.clear();
    }

    private static void dispose() {
        SubLevelView.clearOutside();
        FarRing.clear();
        NeighborPockets.clear();
        FarRing.knownRadius = 0;
        FlightView.clear();
        shownEdge = -1.0F;
        disposeRender();
        lastTemplate = null;
        lastTemplateTag = null;
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
        SceneryParticles.cancelHues();
        PuddlesCompat.clear();
        GiantEntities.clear();
        sceneryEntityLight.clear();
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
        pendingPatch = null;
        pendingPatchLight = null;
        pendingPatchInfo = null;
        pendingDiffNanos = 0L;
        diffGeneration++;
        deltaOverlay.clear();
        statePosByPos = new HashMap<>();
        currentSectionStates = new HashMap<>();
        builtViewRadius = -1;
        pendingTemplate = null;
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

    private static StructureRenderData build(StructureTemplate template, byte[] light, OutsideViewPayload info,
                                             int radius, Vec3 camera) {
        long buildStart = System.nanoTime();
        // Gabarit vide : Ars ne construit pas son propre faux monde (aussitot remplace par le notre ci-dessous).
        StructureRenderData data = LitRenderingWorld.asGiant(() -> new StructureRenderData(new StructureTemplate(), 0L));
        data.statePosCache = statesOf(template);
        long templateNanos = System.nanoTime() - buildStart;
        long stageStart = System.nanoTime();
        // Remplace le faux monde d'Ars (lumiere 15 partout) par le notre, avec la vraie lumiere.
        // (et avec les couleurs du vrai biome pour l'herbe, les feuilles et l'eau).
        LitRenderingWorld lit = LitRenderingWorld.giant(Minecraft.getInstance().level, data.statePosCache);
        lit.useFarRingBeyond(radius);
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
        // Decor detaille en cylindre (voir DetailShape) : les coins sont dessines par l'anneau lointain.
        updateRoundDetail();
        data.statePosCache.removeIf(p -> !inDetail(p.pos.getX() - radius, p.pos.getZ() - radius, view));
        PuddlesCompat.prepare(lit, info);

        long specialLightNanos;
        ColorCompat.begin(data.fakeRenderingWorld);
        try {
            stageStart = System.nanoTime();
            computeBlockEntityLight(data, info);
            specialLightNanos = System.nanoTime() - stageStart;
        } finally {
            ColorCompat.end();
        }

        Map<SectionPos, List<CulledStatePos>> sectionStates = groupSections(data.statePosCache);
        for (BlockPos pos : PuddlesCompat.extraPositions(lit)) {
            if (visibleSceneryPos(pos, radius, view)) sectionStates.computeIfAbsent(SectionPos.of(pos), ignored -> new ArrayList<>());
        }
        currentSectionStates = sectionStates;
        Map<BlockPos, CulledStatePos> byPos = new HashMap<>(data.statePosCache.size() * 2);
        for (CulledStatePos state : data.statePosCache) byPos.put(state.pos, state);
        statePosByPos = byPos;
        sceneryVersion++;
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
        PuddlesCompat.collectSprites(lit);
        long spritesNanos = System.nanoTime() - stageStart;
        LOGGER.info("[Planarium Panorama] Preparation de {} blocs : total={} ms, gabarit={} ms, donnees={} ms, " +
                        "lumiere speciale={} ms, sections a retesseler={}, sprites={} ms",
                data.statePosCache.size(), (System.nanoTime() - buildStart) / 1_000_000L,
                templateNanos / 1_000_000L, sceneryDataNanos / 1_000_000L,
                specialLightNanos / 1_000_000L, pendingSections.size(), spritesNanos / 1_000_000L);
        return data;
    }

    /** Lit une photo recue (NBT) en gabarit. */
    private static StructureTemplate loadTemplate(net.minecraft.nbt.CompoundTag tag) {
        StructureTemplate template = new StructureTemplate();
        template.load(BuiltInRegistries.BLOCK.asLookup(), tag);
        return template;
    }

    /** Blocs du gabarit, comme Ars les prepare (sans construire son faux monde). */
    static ArrayList<CulledStatePos> statesOf(StructureTemplate template) {
        ArrayList<CulledStatePos> states = new ArrayList<>();
        var palettes = ((com.hollingsworth.arsnouveau.common.mixin.structure.StructureTemplateAccessor) template).getPalettes();
        if (palettes.isEmpty()) {
            return states;
        }
        for (StructureTemplate.StructureBlockInfo info : palettes.get(0).blocks()) {
            states.add(new CulledStatePos(info.state(), info.pos(), info.nbt()));
        }
        return states;
    }

    /**
     * Compare deux photos (NBT) et renvoie les blocs qui ont change, ou null si elles ne sont pas comparables
     * (format inattendu, trop de changements) : il faut alors tout reconstruire.
     * Les palettes sont comparees par contenu, car leur ordre change des qu'un nouvel etat de bloc apparait.
     */
    private static Map<BlockPos, BlockChange> diffTemplates(net.minecraft.nbt.CompoundTag oldTag,
                                                           net.minecraft.nbt.CompoundTag newTag,
                                                           Set<BlockPos> overlayPositions) {
        int compound = net.minecraft.nbt.Tag.TAG_COMPOUND;
        int list = net.minecraft.nbt.Tag.TAG_LIST;
        if (!oldTag.contains("palette", list) || !newTag.contains("palette", list)
                || !oldTag.contains("blocks", list) || !newTag.contains("blocks", list)) {
            return null;
        }
        net.minecraft.nbt.ListTag oldPalette = oldTag.getList("palette", compound);
        net.minecraft.nbt.ListTag newPalette = newTag.getList("palette", compound);
        net.minecraft.nbt.ListTag oldBlocks = oldTag.getList("blocks", compound);
        net.minecraft.nbt.ListTag newBlocks = newTag.getList("blocks", compound);
        Map<net.minecraft.nbt.Tag, Integer> oldIndex = new HashMap<>();
        for (int i = 0; i < oldPalette.size(); i++) oldIndex.putIfAbsent(oldPalette.get(i), i);
        int[] newToOld = new int[newPalette.size()];
        for (int i = 0; i < newPalette.size(); i++) {
            Integer old = oldIndex.get(newPalette.get(i));
            newToOld[i] = old == null ? -1 : old;
        }
        BlockState[] decoded = new BlockState[newPalette.size()];
        Map<BlockPos, BlockChange> changes = new java.util.LinkedHashMap<>();

        // Cas courant : memes positions dans le meme ordre, seuls des etats changent.
        boolean aligned = oldBlocks.size() == newBlocks.size();
        if (aligned) {
            for (int i = 0; i < newBlocks.size(); i++) {
                net.minecraft.nbt.CompoundTag before = oldBlocks.getCompound(i);
                net.minecraft.nbt.CompoundTag now = newBlocks.getCompound(i);
                // Positions comparees telles quelles (sans les decoder), pour aller vite sur 100 000+ blocs.
                net.minecraft.nbt.Tag posTag = now.get("pos");
                if (posTag == null || !posTag.equals(before.get("pos"))) {
                    aligned = false;
                    break;
                }
                if (!sameBlock(before, now, newToOld)) {
                    BlockPos pos = readPos(now);
                    if (pos == null) return null;
                    changes.put(pos, changeOf(now, newPalette, decoded));
                    if (changes.size() > MAX_PATCH_BLOCKS) return null;
                }
            }
        }
        // Des blocs sont apparus ou ont disparu : comparaison par position.
        if (!aligned) {
            changes.clear();
            Map<BlockPos, net.minecraft.nbt.CompoundTag> old = new HashMap<>(oldBlocks.size() * 2);
            for (int i = 0; i < oldBlocks.size(); i++) {
                net.minecraft.nbt.CompoundTag before = oldBlocks.getCompound(i);
                BlockPos pos = readPos(before);
                if (pos == null) return null;
                old.put(pos, before);
            }
            for (int i = 0; i < newBlocks.size(); i++) {
                net.minecraft.nbt.CompoundTag now = newBlocks.getCompound(i);
                BlockPos pos = readPos(now);
                if (pos == null) return null;
                net.minecraft.nbt.CompoundTag before = old.remove(pos);
                if (before == null || !sameBlock(before, now, newToOld)) {
                    changes.put(pos, changeOf(now, newPalette, decoded));
                    if (changes.size() > MAX_PATCH_BLOCKS) return null;
                }
            }
            for (BlockPos removed : old.keySet()) {
                changes.put(removed, new BlockChange(null, null));
                if (changes.size() > MAX_PATCH_BLOCKS) return null;
            }
        }
        // Blocs recus par envoi partiel apres l'ancienne photo : on prend leur etat dans la nouvelle photo,
        // meme s'il est identique a l'ancienne (le decor affiche, lui, a l'etat de l'envoi partiel).
        if (!overlayPositions.isEmpty()) {
            Set<BlockPos> missing = new HashSet<>(overlayPositions);
            for (int i = 0; i < newBlocks.size() && !missing.isEmpty(); i++) {
                net.minecraft.nbt.CompoundTag now = newBlocks.getCompound(i);
                BlockPos pos = readPos(now);
                if (pos != null && missing.remove(pos)) {
                    changes.put(pos, changeOf(now, newPalette, decoded));
                }
            }
            for (BlockPos removed : missing) {
                changes.put(removed, new BlockChange(null, null));
            }
            if (changes.size() > MAX_PATCH_BLOCKS) return null;
        }
        return changes;
    }

    private static boolean sameBlock(net.minecraft.nbt.CompoundTag before, net.minecraft.nbt.CompoundTag now, int[] newToOld) {
        int state = now.getInt("state");
        int mapped = state >= 0 && state < newToOld.length ? newToOld[state] : -1;
        return mapped >= 0 && mapped == before.getInt("state") && java.util.Objects.equals(before.get("nbt"), now.get("nbt"));
    }

    private static BlockChange changeOf(net.minecraft.nbt.CompoundTag block, net.minecraft.nbt.ListTag palette,
                                        BlockState[] decoded) {
        int index = block.getInt("state");
        if (index < 0 || index >= palette.size()) {
            throw new IllegalStateException("Etat de bloc hors palette : " + index);
        }
        if (decoded[index] == null) {
            decoded[index] = net.minecraft.nbt.NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(),
                    palette.getCompound(index));
        }
        net.minecraft.nbt.CompoundTag nbt = block.contains("nbt", net.minecraft.nbt.Tag.TAG_COMPOUND)
                ? block.getCompound("nbt") : null;
        return new BlockChange(decoded[index], nbt);
    }

    /** Position d'un bloc de la photo (liste de 3 entiers, ou tableau d'entiers). */
    private static BlockPos readPos(net.minecraft.nbt.CompoundTag block) {
        if (block.contains("pos", net.minecraft.nbt.Tag.TAG_INT_ARRAY)) {
            int[] pos = block.getIntArray("pos");
            return pos.length == 3 ? new BlockPos(pos[0], pos[1], pos[2]) : null;
        }
        net.minecraft.nbt.ListTag pos = block.getList("pos", net.minecraft.nbt.Tag.TAG_INT);
        return pos.size() == 3 ? new BlockPos(pos.getInt(0), pos.getInt(1), pos.getInt(2)) : null;
    }

    /**
     * Corrige le decor deja construit avec les blocs qui ont change, au lieu de tout reconstruire :
     * faux monde, blocs speciaux, lumiere, puis seules les sections touchees sont retesselees.
     */
    private static void applyPatch(StructureRenderData data, Map<BlockPos, BlockChange> patch, byte[] light,
                                   OutsideViewPayload info, int radius, long diffNanos) {
        long start = System.nanoTime();
        sceneryVersion++;
        LitRenderingWorld lit = (LitRenderingWorld) data.fakeRenderingWorld;
        Set<SectionPos> dirty = new HashSet<>();
        BlockPos planarium = new BlockPos(radius, radius, radius);
        BlockPos above = planarium.above();
        int view = builtViewRadius;
        Set<CulledStatePos> removedStates = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

        // Blocs qui arretent ou laissent passer la lumiere avant le changement (pour l'ouverture sur le ciel).
        Set<BlockPos> opaqueBefore = new HashSet<>();
        boolean blockEntitiesChanged = false;
        // Une source de lumiere changee (couleur differente a intensite egale, par exemple) ne change pas la lumiere
        // vanilla : sans ca, seules les sections touchant le bloc etaient refaites et une bande gardait l'ancienne teinte.
        boolean emitterChanged = false;
        for (Map.Entry<BlockPos, BlockChange> entry : patch.entrySet()) {
            BlockPos pos = entry.getKey();
            if (isOpaque(lit, pos)) opaqueBefore.add(pos);
            if (lit.blockEntityMap.containsKey(pos)) blockEntitiesChanged = true;
            if (com.matthieu.planariumview.compat.ColorCompat.present() && Config.coloredLight()) {
                BlockState before = lit.getBlockState(pos);
                BlockState after = entry.getValue().state();
                if (!java.util.Objects.equals(before, after) && (before.getLightEmission(lit, pos) > 0
                        || after != null && after.getLightEmission(lit, pos) > 0)) {
                    // Portee d'une lumiere coloree : 15 blocs autour de la source.
                    markBox(dirty, pos.getX() - 15, pos.getY() - 15, pos.getZ() - 15,
                            pos.getX() + 15, pos.getY() + 15, pos.getZ() + 15);
                    emitterChanged = true;
                }
            }
        }
        Set<BlockState> newStates = new HashSet<>();

        // 1. Faux monde : les etats captures avant de preparer les blocs speciaux et le rendu.
        for (Map.Entry<BlockPos, BlockChange> entry : patch.entrySet()) {
            if (entry.getValue().state() == null) {
                lit.positions.remove(entry.getKey());
            } else {
                lit.setBlock(entry.getKey(), entry.getValue().state(), 0);
            }
        }
        // Cache de couleurs de Contraption Lights partage par toute la photo : effacer seulement ces sections.
        ColorCompat.blocksChanged(lit, patch.keySet());
        for (Map.Entry<BlockPos, BlockChange> entry : patch.entrySet()) {
            BlockPos pos = entry.getKey();
            BlockChange change = entry.getValue();
            // Meme conservation que dans PlanariumRenderingWorldMixin, y compris apres plantation ou croissance.
            BlockState captured = change.state();
            placeBlockEntity(lit, pos, captured, change.nbt());
            if (lit.blockEntityMap.containsKey(pos)) blockEntitiesChanged = true;
            if (captured != null) newStates.add(captured);

            // 2. Blocs a dessiner (memes filtres que la construction complete).
            boolean kept = !pos.equals(planarium) && !pos.equals(above) && (view < 0
                    || inDetail(pos.getX() - radius, pos.getZ() - radius, Math.min(view, radius)));
            CulledStatePos statePos = statePosByPos.get(pos);
            SectionPos section = SectionPos.of(pos);
            if (change.state() == null || !kept) {
                if (statePos != null) {
                    statePosByPos.remove(pos);
                    removedStates.add(statePos);
                    List<CulledStatePos> sectionList = currentSectionStates.get(section);
                    if (sectionList != null) sectionList.remove(statePos);
                }
            } else if (statePos == null) {
                CulledStatePos added = new CulledStatePos(change.state(), pos, change.nbt());
                data.statePosCache.add(added);
                statePosByPos.put(pos, added);
                currentSectionStates.computeIfAbsent(section, ignored -> new ArrayList<>()).add(added);
            } else {
                statePos.state = change.state();
                statePos.tag = change.nbt();
                statePos.setNeedsUpdate(true);
                statePos.setSkipRender(false);
            }
            // Les faces cachees des voisins dependent de ce bloc.
            for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.values()) {
                CulledStatePos neighbour = statePosByPos.get(pos.relative(direction));
                if (neighbour != null) {
                    neighbour.setNeedsUpdate(true);
                    neighbour.setSkipRender(false);
                }
            }
            markAffectedSections(dirty, pos);
        }
        if (!removedStates.isEmpty()) {
            data.statePosCache.removeIf(removedStates::contains);
        }
        PuddlesCompat.blocksChanged(lit, patch.keySet());

        // 3. Lumiere : seules les sections proches des cases qui ont change.
        boolean lightChanged = !java.util.Arrays.equals(light, renderedLight);
        if (lightChanged) {
            if (!markLightChanges(dirty, light, renderedLight, radius * 2 + 3)) {
                dirty.addAll(currentSectionStates.keySet());
            }
            if (Config.realLighting()) {
                lit.setLight(light == null ? new byte[0] : light, radius * 2 + 3);
            }
            renderedLight = light == null ? null : light.clone();
        }

        // 4. Ce qui depend des blocs ou de la lumiere, recalcule seulement si besoin.
        long stageStart = System.nanoTime();
        boolean opacityChanged = false;
        for (BlockPos pos : patch.keySet()) {
            if (isOpaque(lit, pos) != opaqueBefore.contains(pos)) {
                opacityChanged = true;
                break;
            }
        }
        // Ouverture sur le ciel (1500 rayons) : seulement si la lumiere ou l'opacite d'un bloc a change.
        boolean skyRecomputed = lightChanged || opacityChanged;
        if (skyRecomputed) {
            openSkyShare = computeOpenSkyShare(lit, radius);
        }
        long skyNanos = System.nanoTime() - stageStart;
        stageStart = System.nanoTime();
        if (lightChanged || blockEntitiesChanged || emitterChanged) {
            ColorCompat.begin(lit);
            try {
                computeBlockEntityLight(data, info);
            } finally {
                ColorCompat.end();
            }
        }
        // Textures animees : seulement celles des nouveaux etats, sans reparcourir tout le decor.
        GiantSprites.add(newStates);
        long restNanos = System.nanoTime() - stageStart;

        // 5. Sections a retesseler, les plus proches du Planarium d'abord.
        dirty.retainAll(currentSectionStates.keySet());
        List<SectionPos> ordered = new ArrayList<>(dirty);
        ordered.sort(Comparator.comparingDouble(section -> section.distanceFromPlanariumSqr(radius)));
        for (SectionPos section : ordered) {
            if (pendingSectionSet.add(section)) pendingSections.addLast(section);
        }
        if (!pendingSections.isEmpty()) {
            pendingSectionData = data;
            pendingSectionStates = currentSectionStates;
        }
        com.matthieu.planariumview.client.sound.SceneryWaterfalls.blocksChanged(lit, radius, patch.keySet());
        LOGGER.info("[Planarium Panorama] Correction du decor : {} blocs changes, lumiere={}, comparaison (hors rendu)={} ms, " +
                        "correction={} ms (ciel={} ms{}, blocs speciaux et textures={} ms), sections a retesseler={}",
                patch.size(), lightChanged, String.format(java.util.Locale.ROOT, "%.1f", diffNanos / 1e6),
                String.format(java.util.Locale.ROOT, "%.1f", (System.nanoTime() - start) / 1e6),
                String.format(java.util.Locale.ROOT, "%.1f", skyNanos / 1e6), skyRecomputed ? "" : ", non recalcule",
                String.format(java.util.Locale.ROOT, "%.1f", restNanos / 1e6), dirty.size());
    }

    /** Meme critere que le calcul de l'ouverture sur le ciel : le bloc arrete completement la lumiere. */
    private static boolean isOpaque(LitRenderingWorld world, BlockPos pos) {
        try {
            return world.getBlockState(pos).getLightBlock(world, pos) >= 15;
        } catch (Exception e) {
            return false;
        }
    }

    /** Remplace le bloc special d'une position (ou le met a jour s'il est du meme type). */
    private static void placeBlockEntity(LitRenderingWorld world, BlockPos pos, BlockState state,
                                         net.minecraft.nbt.CompoundTag nbt) {
        BlockEntity existing = world.blockEntityMap.get(pos);
        if (state == null || !state.hasBlockEntity()
                || !(state.getBlock() instanceof net.minecraft.world.level.block.EntityBlock entityBlock)) {
            world.blockEntityMap.remove(pos);
            return;
        }
        try {
            net.minecraft.nbt.CompoundTag data = nbt != null ? nbt : new net.minecraft.nbt.CompoundTag();
            if (existing != null && existing.getBlockState() == state) {
                existing.loadWithComponents(data, world.registryAccess());
                return;
            }
            world.blockEntityMap.remove(pos);
            BlockEntity blockEntity = entityBlock.newBlockEntity(pos, state);
            if (blockEntity == null) {
                return;
            }
            com.matthieu.planariumview.compat.CreateCopies.attach(blockEntity, world, Minecraft.getInstance().level);
            blockEntity.onLoad();
            blockEntity.loadWithComponents(data, world.registryAccess());
            world.blockEntityMap.put(pos, blockEntity);
        } catch (Exception e) {
            world.blockEntityMap.remove(pos);
            LOGGER.debug("[Planarium View] Bloc special ignore", e);
        }
    }

    /** Lumiere des blocs speciaux, calculee pendant que Contraption Lights lit notre faux monde (ColorCompat actif). */
    private static void computeBlockEntityLight(StructureRenderData data, OutsideViewPayload info) {
        // Couleur de Contraption Lights pour les particules, la pluie et la neige du decor (voir SceneryParticles.readHues).
        if (data.fakeRenderingWorld instanceof LitRenderingWorld lit) {
            SceneryParticles.readHues(lit, data.statePosCache, true, currentRadius, viewRadius());
        }
        blockEntityLight.clear();
        sceneryEntityLight.clear();
        for (BlockPos pos : data.fakeRenderingWorld.blockEntityMap.keySet()) {
            if (!visibleSceneryPos(pos, currentRadius, viewRadius())) continue;
            try {
                int light = LevelRenderer.getLightColor(data.fakeRenderingWorld, pos);
                if (com.matthieu.planariumview.compat.ColorCompat.building() && (light & 0xF0) != 0
                        && (light & ~0x00F000F0) == 0) {
                    // Pas de couleur dans la case du bloc (boite a outils de Create...) : celle d'une case voisine.
                    light |= FaceHueConsumer.hueAround(data.fakeRenderingWorld, pos);
                }
                blockEntityLight.put(pos, light);
            } catch (Exception e) {
                LOGGER.debug("[Planarium View] Lumiere de bloc special ignoree", e);
            }
        }
        if (info != null && data.fakeRenderingWorld instanceof net.minecraft.world.level.Level fakeWorld) {
            for (net.minecraft.world.entity.Entity entity : GiantEntities.entities(data, fakeWorld, info.template(), info.origin())) {
                try {
                    sceneryEntityLight.put(entity, GiantEntities.packedLight(fakeWorld, entity, 0.0F));
                } catch (Exception e) {
                    LOGGER.debug("[Planarium View] Lumiere d'entite du decor ignoree", e);
                }
            }
        }
    }

    private static Map<SectionPos, List<CulledStatePos>> groupSections(List<CulledStatePos> states) {
        Map<SectionPos, List<CulledStatePos>> sections = new HashMap<>();
        for (CulledStatePos state : states) {
            sections.computeIfAbsent(SectionPos.of(state.pos), ignored -> new ArrayList<>()).add(state);
        }
        return sections;
    }

    private static boolean visibleSceneryPos(BlockPos pos, int radius, int view) {
        return !(pos.getX() == radius && pos.getZ() == radius
                && (pos.getY() == radius || pos.getY() == radius + 1))
                && (view < 0 || inDetail(pos.getX() - radius, pos.getZ() - radius, view));
    }

    /** Une flaque change : retesseler son voisinage avec le meme budget que les mises a jour du serveur. */
    public static void visualBlocksChanged(LitRenderingWorld world, Collection<BlockPos> positions) {
        // Pas d'effacement des couleurs de Contraption Lights : une flaque ne bloque ni n'emet de lumiere. Les effacer
        // forcait Contraption Lights a tout recalculer autour de chaque flaque, une reconstruction de section pouvait
        // alors prendre pres d'une seconde (gels sous la pluie).
        if (renderData == null || renderData.fakeRenderingWorld != world || positions.isEmpty()) return;
        Set<SectionPos> dirty = new HashSet<>();
        for (BlockPos pos : positions) {
            if (!visibleSceneryPos(pos, currentRadius, builtViewRadius)) continue;
            if (PuddlesCompat.extraPositions(world).contains(pos)) {
                currentSectionStates.computeIfAbsent(SectionPos.of(pos), ignored -> new ArrayList<>());
            }
            markAffectedSections(dirty, pos);
            CulledStatePos state = statePosByPos.get(pos);
            if (state != null) {
                state.setNeedsUpdate(true);
                state.setSkipRender(false);
            }
            for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.values()) {
                CulledStatePos neighbour = statePosByPos.get(pos.relative(direction));
                if (neighbour != null) {
                    neighbour.setNeedsUpdate(true);
                    neighbour.setSkipRender(false);
                }
            }
        }
        for (SectionPos section : dirty) {
            if ((currentSectionStates.containsKey(section) || sectionMeshes.containsKey(section))
                    && pendingSectionSet.add(section)) pendingSections.addLast(section);
        }
        if (!pendingSections.isEmpty()) {
            pendingSectionData = renderData;
            pendingSectionStates = currentSectionStates;
        }
    }

    /** A neighbor snapshot arrived: only rebuild sections containing giant Planarium shells. */
    static void neighborPocketsChanged(ResourceLocation dim) {
        if (currentDim == null || !currentDim.equals(dim)) return;
        if (pendingTemplate != null) {
            forceAllSections = true;
            return;
        }
        if (renderData == null) return;
        for (var entry : currentSectionStates.entrySet()) {
            if (entry.getValue().stream().noneMatch(state -> state.state.getBlock()
                    instanceof com.hollingsworth.arsnouveau.common.block.Planarium)) continue;
            if (pendingSectionSet.add(entry.getKey())) pendingSections.addLast(entry.getKey());
        }
        if (!pendingSections.isEmpty()) {
            pendingSectionData = renderData;
            pendingSectionStates = currentSectionStates;
        }
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
                // Sommets sur une grille fixe : pas de fentes entre les blocs voisins (voir GridSnap).
                GridSnap.snapMesh(mesh);
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
        if (pendingSectionData.fakeRenderingWorld instanceof LitRenderingWorld lit) {
            states = PuddlesCompat.sectionStates(lit, key.x(), key.y(), key.z(), states, currentRadius, builtViewRadius);
        }
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
                        com.mojang.blaze3d.vertex.VertexConsumer faces = com.matthieu.planariumview.compat.ColorCompat.building()
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

    /** Moment du dernier dessin du decor geant (voir particleFog). */
    private static volatile long lastDrawNanos;

    /**
     * Particules : quand le decor geant est dessine, elles prennent son brouillard. Avec celui du jeu, une particule du
     * decor un peu loin (fumee d'un feu de camp) prenait la couleur du brouillard alors que le decor autour restait net.
     * Les particules du bocal sont tout pres : ce brouillard plus lointain ne les touche pas. Faux si rien n'a change.
     */
    public static boolean particleFog() {
        if (renderData == null || System.nanoTime() - lastDrawNanos > 250_000_000L) {
            return false;
        }
        applySceneryFog();
        return true;
    }

    private static void draw(StructureRenderData data, RenderLevelStageEvent event) {
        lastDrawNanos = System.nanoTime();
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
        float[] cave = client.level != null ? caveColor(client.level) : null;
        if (cave != null) {
            RenderSystem.setShaderFogColor(cave[0], cave[1], cave[2], fogColor[3]);
        }
        try {
            Matrix4f projectionMatrix = new Matrix4f(event.getProjectionMatrix());
            // Fond du decor avant les blocs : l'eau et le verre melangent leur couleur avec ce qui est deja dessine,
            // puis ecrivent la profondeur. Apres eux, le fond ne pouvait plus remplacer le ciel vu par transparence.
            // Meme repere du bocal que precedemment, avant le placement du terrain Sable.
            drawFogWall(poseStack, projectionMatrix, camera);
            drawCaveDome(poseStack, projectionMatrix);
            boolean flight = FlightView.active();
            FlightView.prepare(poseStack, projectionMatrix);
            // Repere du bocal, sans le placement du terrain : les Planariums voisins y sont fixes (voir NeighborPockets).
            Matrix4f jarPose = new Matrix4f(poseStack.last().pose());
            poseStack.pushPose();
            if (lastInfo != null) SubLevelView.terrain(poseStack,
                    lastInfo.origin().offset(currentRadius, currentRadius, currentRadius));
            // Sections du decor detaille dans le champ de la camera, une fois pour toutes les couches. Meme matrice que
            // le dessin (placement Sable compris) : meme repere que les sommets. Le tri du verre et de l'eau est garde.
            NEAR_FRUSTUM.update(poseStack.last().pose(), projectionMatrix);
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

    /**
     * Brouillard "Rayon de vue" : au-dela du bord du decor, il n'y a que le ciel. Le jour, il a la couleur du brouillard
     * et tout se fond ; la nuit (etoiles, ciels moddes comme AstroCraft), on voyait le ciel la ou le sol devrait etre.
     * On ferme donc l'horizon : un mur et un fond de la couleur du brouillard, juste apres le bord, jusqu'a l'horizon.
     */
    private static VertexBuffer fogWall;
    private static float fogWallEdge = -1;

    private static void drawFogWall(PoseStack poseStack, Matrix4f projection, Vec3 camera) {
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

    private static void drawCaveDome(PoseStack poseStack, Matrix4f projection) {
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
    public static void applySceneryFog() {
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
                if (renderer == null || com.matthieu.planariumview.compat.SwingingLanternsCompat.skip(renderer)) {
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
                        com.matthieu.planariumview.compat.CreateCopies.render(renderer, blockEntity, data.fakeRenderingWorld,
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

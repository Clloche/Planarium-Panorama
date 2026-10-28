package com.matthieu.planariumview.client.scenery;

import com.matthieu.planariumview.client.scenery.SceneryMeshes.SectionMesh;
import com.matthieu.planariumview.client.scenery.SceneryMeshes.SectionPos;
import com.matthieu.planariumview.client.scenery.SceneryPatcher.BlockChange;
import com.matthieu.planariumview.client.scenery.entities.GiantEntities;
import com.matthieu.planariumview.client.scenery.entities.MobShadows;
import com.matthieu.planariumview.client.scenery.entities.OutsideMobs;
import com.matthieu.planariumview.client.scenery.entities.OutsideVehicles;
import com.matthieu.planariumview.LogFilter;
import com.matthieu.planariumview.client.jar.JarInteriorBiome;
import com.matthieu.planariumview.client.jar.WallColors;
import com.matthieu.planariumview.client.sky.LightningView;
import com.matthieu.planariumview.client.sky.LunarClient;
import com.matthieu.planariumview.client.sky.SkySync;
import com.matthieu.planariumview.client.sky.WeatherView;
import com.matthieu.planariumview.compat.alexs_caves.AmbersolCompat;
import com.matthieu.planariumview.compat.contraption_lights.ColorCompat;
import com.matthieu.planariumview.compat.serene_seasons.SeasonColors;
import com.matthieu.planariumview.config.Config;
import com.matthieu.planariumview.config.ConfigScreen;
import com.matthieu.planariumview.network.OutsideViewDeltaPayload;
import com.matthieu.planariumview.network.OutsideViewPayload;

import com.hollingsworth.arsnouveau.client.renderer.tile.PlanariumRenderer;
import com.hollingsworth.arsnouveau.client.renderer.tile.PlanariumRenderer.StructureRenderData;
import com.hollingsworth.arsnouveau.client.renderer.world.CulledStatePos;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
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
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Matrix4f;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;

import static com.matthieu.planariumview.client.scenery.SceneryMeshes.buildPendingSections;
import static com.matthieu.planariumview.client.scenery.SceneryMeshes.builtViewRadius;
import static com.matthieu.planariumview.client.scenery.SceneryMeshes.pendingSectionData;
import static com.matthieu.planariumview.client.scenery.SceneryMeshes.pendingSections;
import static com.matthieu.planariumview.client.scenery.SceneryMeshes.sectionMeshes;

import static com.matthieu.planariumview.client.scenery.SceneryPatcher.applyPatch;
import static com.matthieu.planariumview.client.scenery.SceneryPatcher.blockEntityLight;
import static com.matthieu.planariumview.client.scenery.SceneryPatcher.build;
import static com.matthieu.planariumview.client.scenery.SceneryPatcher.openSkyShare;
import static com.matthieu.planariumview.client.scenery.SceneryPatcher.sceneryEntityLight;
import static com.matthieu.planariumview.client.scenery.SceneryPatcher.sceneryVersion;
import static com.matthieu.planariumview.client.scenery.SceneryPatcher.statePosByPos;

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
import static com.matthieu.planariumview.client.scenery.SceneryState.latestInfo;
import static com.matthieu.planariumview.client.scenery.SceneryState.renderData;

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

    private static int sortCounter;
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
        // Rechargement des ressources (F3+T, pack change) : les sommets gardes des parois du bocal sont a refaire.
        container.getEventBus().addListener(net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent.class,
                event -> event.registerReloadListener((net.minecraft.server.packs.resources.ResourceManagerReloadListener)
                        manager -> com.matthieu.planariumview.client.jar.ProjectorLighting.clear()));
        container.getEventBus().addListener(net.neoforged.fml.event.lifecycle.FMLClientSetupEvent.class,
                event -> event.enqueueWork(() -> {
                    WallColors.register();
                    LogFilter.install();
                }));
    }

    public static void receive(OutsideViewPayload payload) {
        SceneryReceiver.receive(payload);
    }

    /** Blocs changes envoyes seuls par le serveur (voir {@link OutsideViewDeltaPayload}). */
    public static void receiveDelta(OutsideViewDeltaPayload payload) {
        SceneryReceiver.receiveDelta(payload);
    }

    public static float openSkyShare() {
        return openSkyShare;
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

    static void updateRoundDetail() {
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

    static float shownEdge = -1.0F;
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

    public static void rebuild() {
        SceneryReceiver.rebuild();
    }

    /**
     * Un nouvel anneau lointain est arrive : le decor proche est reconstruit, pour que son bord se raccorde a l'anneau
     * (l'eau du bord ne s'abaisse plus vers le vide). Les sections actuelles restent visibles pendant ce temps.
     */
    static void farRingArrived() {
        SceneryReceiver.farRingArrived();
    }

    /**
     * Sons d'ambiance des blocs du decor (feu qui crepite, lave, eau qui coule, portail...). Le serveur ne les joue
     * jamais : comme Minecraft autour du joueur, on tire chaque tick des blocs au hasard autour du Planarium dans le
     * faux monde du decor, et on leur fait jouer leur ambiance (les particules sont ignorees).
     */
    private static void tickSceneryAmbience(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        SceneryAmbience.tickSceneryAmbience(event);
    }

    /**
     * Ambiance d'un bloc d'un faux monde (decor geant ou miniature), comme ClientLevel.doAnimateTick : celle du bloc,
     * de son liquide, et les gouttes sous un plafond de liquide.
     */
    public static void animateBlock(LitRenderingWorld lit, BlockPos at, BlockState state) {
        SceneryAmbience.animateBlock(lit, at, state);
    }

    /** Positions des feux de camp parmi ces blocs (allumes ou non). */
    public static List<BlockPos> findCampfires(Iterable<CulledStatePos> states) {
        return SceneryAmbience.findCampfires(states);
    }

    /** Un tick de fumee d'un feu de camp allume, comme CampfireBlockEntity.particleTick. */
    public static void campfireTick(LitRenderingWorld lit, BlockPos at, BlockState state) {
        SceneryAmbience.campfireTick(lit, at, state);
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

    /** Blocs courants du decor pour les effets d'ambiance. */
    static Collection<CulledStatePos> sceneryStates() {
        return statePosByPos.values();
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
        SceneryHooks.onSceneryParticle(options, x, y, z, vx, vy, vz);
    }

    /** Death particles follow the same captured origin and moving frame as the rendered mob. */
    public static void onMobDeath(double sourceX, double sourceY, double sourceZ, float width, float height) {
        SceneryHooks.onMobDeath(sourceX, sourceY, sourceZ, width, height);
    }

    /** Potion particles start at the interpolated mob, in source-world coordinates, just like its death puff. */
    public static void onMobParticle(net.minecraft.core.particles.ParticleOptions options,
                                     double sourceX, double sourceY, double sourceZ) {
        SceneryHooks.onMobParticle(options, sourceX, sourceY, sourceZ);
    }

    public static void onMobParticle(net.minecraft.core.particles.ParticleOptions options,
                                    double sourceX, double sourceY, double sourceZ, double vx, double vy, double vz) {
        SceneryHooks.onMobParticle(options, sourceX, sourceY, sourceZ, vx, vy, vz);
    }

    /** Explosion du dehors (position par rapport au Planarium) : ses particules, si elle est dans le rayon de vue. */
    public static void onOutsideExplosion(double dx, double dy, double dz) {
        SceneryHooks.onOutsideExplosion(dx, dy, dz);
    }

    static void onSceneryAmbientSound(double x, double y, double z, net.minecraft.sounds.SoundEvent sound,
                                      net.minecraft.sounds.SoundSource source, float volume, float pitch) {
        SceneryHooks.onSceneryAmbientSound(x, y, z, sound, source, volume, pitch);
    }

    private static void sendToggle(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        ViewPrefsSender.sendToggle(event);
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        SceneryReceiver.onLoggingOut(event);
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

    /** Blocs du gabarit, comme Ars les prepare (sans construire son faux monde). */
    static ArrayList<CulledStatePos> statesOf(StructureTemplate template) {
        return SceneryPatcher.statesOf(template);
    }

    static boolean visibleSceneryPos(BlockPos pos, int radius, int view) {
        return !(pos.getX() == radius && pos.getZ() == radius
                && (pos.getY() == radius || pos.getY() == radius + 1))
                && (view < 0 || inDetail(pos.getX() - radius, pos.getZ() - radius, view));
    }

    /** Une flaque change : retesseler son voisinage avec le meme budget que les mises a jour du serveur. */
    public static void visualBlocksChanged(LitRenderingWorld world, Collection<BlockPos> positions) {
        SceneryPatcher.visualBlocksChanged(world, positions);
    }

    /** A neighbor snapshot arrived: only rebuild sections containing giant Planarium shells. */
    static void neighborPocketsChanged(ResourceLocation dim) {
        SceneryReceiver.neighborPocketsChanged(dim);
    }

    /**
     * Particules : quand le decor geant est dessine, elles prennent son brouillard. Avec celui du jeu, une particule du
     * decor un peu loin (fumee d'un feu de camp) prenait la couleur du brouillard alors que le decor autour restait net.
     * Les particules du bocal sont tout pres : ce brouillard plus lointain ne les touche pas. Faux si rien n'a change.
     */
    public static boolean particleFog() {
        return SceneryFog.particleFog(renderData != null);
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

    /**
     * Brouillard du decor geant, applique de la meme facon aux blocs normaux et aux blocs speciaux.
     * Sans brouillard : repousse a l'infini. Avec brouillard : brouillard de terrain vanilla calcule
     * sur la vraie distance d'affichage (sans l'extension de Voxy, qui le repousserait tres loin).
     */
    public static void applySceneryFog() {
        SceneryFog.applySceneryFog();
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

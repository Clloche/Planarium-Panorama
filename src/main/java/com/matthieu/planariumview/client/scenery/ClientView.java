package com.matthieu.planariumview.client.scenery;

import com.matthieu.planariumview.LogFilter;
import com.matthieu.planariumview.client.jar.JarInteriorBiome;
import com.matthieu.planariumview.client.jar.WallColors;
import com.matthieu.planariumview.client.sky.LightningView;
import com.matthieu.planariumview.client.sky.LunarClient;
import com.matthieu.planariumview.client.sky.WeatherView;
import com.matthieu.planariumview.compat.serene_seasons.SeasonColors;
import com.matthieu.planariumview.config.Config;
import com.matthieu.planariumview.config.ConfigScreen;
import com.matthieu.planariumview.network.OutsideViewDeltaPayload;
import com.matthieu.planariumview.network.OutsideViewPayload;

import com.hollingsworth.arsnouveau.client.renderer.tile.PlanariumRenderer.StructureRenderData;
import com.hollingsworth.arsnouveau.client.renderer.world.CulledStatePos;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.List;
import java.util.ArrayList;
import java.util.Collection;

import static com.matthieu.planariumview.client.scenery.SceneryMeshes.pendingSectionData;
import static com.matthieu.planariumview.client.scenery.SceneryMeshes.pendingSections;

import static com.matthieu.planariumview.client.scenery.SceneryPatcher.openSkyShare;
import static com.matthieu.planariumview.client.scenery.SceneryPatcher.sceneryVersion;
import static com.matthieu.planariumview.client.scenery.SceneryPatcher.statePosByPos;

import static com.matthieu.planariumview.client.scenery.SceneryReceiver.pendingPatch;
import static com.matthieu.planariumview.client.scenery.SceneryReceiver.pendingTemplate;

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

    private ClientView() {
    }

    public static void init(net.neoforged.fml.ModContainer container) {
        container.registerExtensionPoint(net.neoforged.neoforge.client.gui.IConfigScreenFactory.class,
                (modContainer, parent) -> ConfigScreen.create(parent));
        NeoForge.EVENT_BUS.addListener(ClientView::onRenderStage);
        NeoForge.EVENT_BUS.addListener(ClientView::onLoggingOut);
        // Bilan des mixins a la premiere connexion (aussi a un serveur distant, sans ServerStartedEvent).
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingIn.class,
                event -> com.matthieu.planariumview.diagnostics.MixinHealthReport.summary());
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
        SceneryRenderer.onRenderStage(event);
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

    /**
     * Brouillard du decor geant, applique de la meme facon aux blocs normaux et aux blocs speciaux.
     * Sans brouillard : repousse a l'infini. Avec brouillard : brouillard de terrain vanilla calcule
     * sur la vraie distance d'affichage (sans l'extension de Voxy, qui le repousserait tres loin).
     */
    public static void applySceneryFog() {
        SceneryFog.applySceneryFog();
    }

}

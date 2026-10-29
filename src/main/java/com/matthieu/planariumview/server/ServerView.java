package com.matthieu.planariumview.server;

import com.matthieu.planariumview.server.entities.OutsideMobsServer;
import com.matthieu.planariumview.config.Config;
import com.matthieu.planariumview.network.LightningPayload;
import com.matthieu.planariumview.network.SoundPrefsPayload;

import com.hollingsworth.arsnouveau.ArsNouveau;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.PlayLevelSoundEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Cote serveur : quand un joueur est dans la dimension d'un Planarium, on capture les blocs
 * autour du Planarium dans le monde d'origine et on les envoie au client.
 */
public final class ServerView {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private static final Map<UUID, Track> TRACKS = new HashMap<>();

    static final class Track {
        final ResourceKey<Level> pocket;
        public final UUID owner;
        public GlobalPos source;
        /** Prochaine recherche permise du Planarium quand il n'est plus a sa place (voir PlanariumAnchor.updateAnchor). */
        long nextSearch;
        /** Vaisseau Sable qui porte le Planarium (UUID), ou null sur le terrain normal. */
        UUID body;
        /** Planarium perdu (diagnostic : seulement les passages perdu / retrouve sont ecrits). */
        boolean lost;
        org.joml.Matrix4d anchor;
        Object anchorBody;
        BlockPos center;
        ChunkPos ticketCenter;
        BlockPos capturedCenter;
        CompoundTag lastSent;
        byte[] lastLight;
        List<ResourceLocation> lastBiomePalette;
        byte[] lastBiomes;
        int[] lastRainTops;
        byte[] lastPrecip;
        long nextRefresh;
        /** Rayon de la derniere photo, et rayon (en chunks) du ticket qui garde la zone chargee. */
        int lastRadius = -1;
        int ticketChunks = -1;
        /** Lumiere des parois du bocal et blocs lumineux qui l'eclairent (voir JarWalls). */
        int[] lastWallLight;
        int[] lastWallSources;
        /** Vrai quand la lumiere du ciel du bocal a ete recalculee (les chunks du bocal etaient charges). */
        boolean relit;
        /** Vrai quand les parois du bocal ont ete remises sans lumiere. */
        boolean wallsCleared;
        /** Dernier diagnostic de la lumiere du ciel ecrit dans le log. */
        String lastSkyDiag;
        /** Une modification de bloc dans la zone rend la prochaine capture necessaire. */
        boolean sceneryDirty = true;
        /**
         * Blocs changes depuis la derniere photo complete, quand ces changements ne touchent ni la lumiere ni la meteo
         * (fil de redstone, repeteur...) : on peut alors n'envoyer que ces blocs au lieu de tout recapturer.
         */
        final Set<BlockPos> changedBlocks = new HashSet<>();
        /** Un changement demande une photo complete (lumiere, meteo, trop de blocs, bord de la zone). */
        boolean fullCaptureNeeded = true;
        /** Pourquoi la photo complete est demandee (pour le log). */
        String fullCaptureReason = "premiere capture";
        /** Un changement peut modifier la lumiere : une capture de suivi viendra chercher la lumiere a jour. */
        boolean lightChangePending = true;
        /** Le client a recu au moins une photo complete de cette zone (les envois partiels s'y appliquent). */
        boolean hasFull;
        /** Tick de la derniere capture (pour garder un ecart minimum entre deux captures). */
        long lastCapture = Long.MIN_VALUE / 2;
        /**
         * Capture de suivi apres une capture declenchee par un changement de bloc (0 = aucune) : Minecraft met a jour
         * la lumiere quelques ticks apres le bloc, la premiere capture peut donc encore avoir l'ancienne lumiere.
         */
        long followUpAt;
        /** Limiteur par joueur pour ne pas inonder le client dans une ferme ou une machine redstone. */
        long soundWindowStart;
        /** Sons envoyes dans la seconde en cours : blocs/meteo/disques/explosions, et entites (comptes a part). */
        int soundsInWindow;
        int entitySoundsInWindow;
        /** Mode des tickets de chargement actuels : vrai = la zone tourne, faux = chargee mais en pause. */
        boolean ticketActive;
        /** Synchronisation des jukebox en attente du rechargement des chunks apres reactiver les sons du monde. */
        boolean jukeboxSyncPending;

        Track(UUID owner, ResourceKey<Level> pocket, GlobalPos source) {
            this.owner = owner;
            this.pocket = pocket;
            this.source = source;
            this.center = source.pos();
            this.capturedCenter = source.pos();
            this.anchor = new org.joml.Matrix4d().translation(center.getX(), center.getY(), center.getZ());
        }
    }

    private ServerView() {
    }

    private static final Set<String> LOGGED_ERRORS = new HashSet<>();
    /** Ecrit une erreur dans le log une seule fois par type (sinon elle se repeterait chaque seconde). */
    static void logOnce(String what, Exception e) {
        if (LOGGED_ERRORS.add(what)) {
            LOGGER.warn("[Planarium Panorama] Erreur pendant l'envoi ({}) ; la vue continue sans", what, e);
        }
    }

    /** Joueurs qui ont mis Planarium Panorama sur OFF : rien n'est capture ni envoye pour eux. */
    private static final java.util.Set<UUID> DISABLED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Une source lumineuse placee jusqu'a 15 blocs de la zone peut modifier sa lumiere. */
    private static final long LIGHT_MARGIN = 15L;

    /** Appele apres un changement de bloc, pour eviter les captures completes quand le decor est stable. */
    public static void markSceneryDirty(Level level, BlockPos pos, BlockState previous, BlockState next) {
        if (!(level instanceof ServerLevel serverLevel) || !serverLevel.getServer().isSameThread()) {
            return;
        }
        SubLevelServer.dirty(serverLevel, pos);
        // Deux sources peuvent emettre autant de lumiere, mais avec des couleurs differentes (mods compris).
        boolean lightSourceChanged = previous != next
                && (previous.getLightEmission(level, pos) > 0 || next.getLightEmission(level, pos) > 0);
        // Anneau lointain : une source qui semble enfermee sous terre garde sa photo jusqu'a l'echeance du cache.
        HiddenChanges.Check hidden = new HiddenChanges.Check(serverLevel, pos);
        FarRingServer.blockChanged(serverLevel.dimension(), pos, lightSourceChanged, hidden,
                serverLevel.getServer().getTickCount());
        NeighborPocketsServer.blockChanged(serverLevel.dimension());
        FlightServer.blockChanged(serverLevel.dimension(), pos);
        for (Track track : TRACKS.values()) {
            if (!track.source.dimension().equals(level.dimension())) {
                continue;
            }
            int radius = Math.max(track.lastRadius, Config.RADIUS.get());
            // La zone de la photo affichee : un Planarium qui a un peu bouge montre encore la photo de capturedCenter.
            BlockPos center = track.capturedCenter;
            long dx = Math.abs((long) pos.getX() - center.getX());
            long dy = Math.abs((long) pos.getY() - center.getY());
            long dz = Math.abs((long) pos.getZ() - center.getZ());
            // Changements visibles : zone capturee (cube, hauteur comprise) plus une case pour les dependances
            // immediates des modeles.
            boolean inScenery = dx <= radius + 1L && dy <= radius + 1L && dz <= radius + 1L;
            // Hors de cette zone, seuls l'ajout/retrait/modification d'une source lumineuse justifient une capture.
            boolean nearbyLightSource = lightSourceChanged && dx <= radius + LIGHT_MARGIN
                    && dy <= radius + LIGHT_MARGIN && dz <= radius + LIGHT_MARGIN;
            if (inScenery || nearbyLightSource) {
                track.sceneryDirty = true;
                // Dans la photo et sans effet sur la lumiere ni la meteo : envoi partiel possible.
                boolean inCube = dx <= track.lastRadius && dy <= track.lastRadius && dz <= track.lastRadius;
                boolean lightNeutral = lightNeutral(level, pos, previous, next);
                boolean weatherNeutral = weatherNeutral(previous, next);
                if (!lightNeutral) {
                    track.lightChangePending = true;
                }
                if (inCube && lightNeutral && weatherNeutral && track.changedBlocks.size() < MAX_DELTA_BLOCKS) {
                    track.changedBlocks.add(pos.immutable());
                } else if (!track.fullCaptureNeeded) {
                    track.fullCaptureNeeded = true;
                    track.fullCaptureReason = !inCube ? "bloc en bordure de la zone"
                            : !lightNeutral ? "changement de lumiere"
                            : !weatherNeutral ? "changement pour la pluie"
                            : "plus de " + MAX_DELTA_BLOCKS + " blocs changes";
                }
            }
        }
    }

    /** Au-dela, une photo complete coute moins cher qu'un envoi partiel. */
    private static final int MAX_DELTA_BLOCKS = 4096;

    /**
     * Vrai si le changement de bloc ne peut pas modifier la lumiere (ni emission, ni opacite, ni forme qui bloque
     * la lumiere) ni la hauteur de la pluie (bloc solide pour la pluie, fluide).
     */
    private static boolean lightNeutral(Level level, BlockPos pos, BlockState previous, BlockState next) {
        try {
            return previous.getLightEmission(level, pos) == next.getLightEmission(level, pos)
                    && previous.getLightBlock(level, pos) == next.getLightBlock(level, pos)
                    && !previous.useShapeForLightOcclusion() && !next.useShapeForLightOcclusion();
        } catch (Exception e) {
            return false;
        }
    }

    /** Vrai si le changement ne peut pas modifier la hauteur ou s'arrete la pluie. */
    private static boolean weatherNeutral(BlockState previous, BlockState next) {
        try {
            return previous.blocksMotion() == next.blocksMotion()
                    && previous.getFluidState().isEmpty() == next.getFluidState().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    public static void setEnabled(ServerPlayer player, boolean enabled) {
        boolean changed = enabled ? DISABLED.remove(player.getUUID()) : DISABLED.add(player.getUUID());
        if (changed) {
            LOGGER.debug("[Planarium Panorama] {} : vue {}", player.getGameProfile().getName(), enabled ? "ON" : "OFF");
            TrackUpdater.update(player, true, TRACKS, DISABLED);
        }
    }

    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ServerLevel previous = player.server.getLevel(event.getFrom());
            if (previous != null && isPocket(previous)) {
                try {
                    MiniatureSync.captureOnExit(previous);
                } catch (Exception e) {
                    logOnce("capture de miniature a la sortie", e);
                }
            }
            if (previous != null && (isPocket(previous) || isPocket(player.serverLevel()))) {
                // The client has already discarded that dimension. Do not let Sable send its removals to the new one.
                com.matthieu.planariumview.compat.sable.SubLevelCompat.forgetTracking(previous, player.getUUID());
            }
            SubLevelServer.dimensionChanged(player.getUUID(), player.server.getTickCount());
            TrackUpdater.update(player, true, TRACKS, DISABLED);
        }
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            SubLevelServer.reset(player.getUUID());
            SubLevelServer.dimensionChanged(player.getUUID(), player.server.getTickCount());
            removeTrack(player.server, player.getUUID());
            TrackUpdater.update(player, true, TRACKS, DISABLED);
            MiniatureSync.sendInitialState(player);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            SubLevelServer.reset(player.getUUID());
            NeighborPocketsServer.reset(player.getUUID());
            removeTrack(player.server, player.getUUID());
            DISABLED.remove(player.getUUID());
            OutsideSounds.logout(player.getUUID());
            OutsideMobsServer.logout(player.getUUID());
        }
    }

    /**
     * Ce que le client veut voir en plus des blocs (mobs du dehors). Afficher les mobs garde la zone autour du Planarium
     * active, meme avec les sons du monde exterieur coupes (sinon ils resteraient figes) : les tickets sont revus.
     */
    public static void setViewPrefs(ServerPlayer player, com.matthieu.planariumview.network.entities.ViewPrefsPayload prefs) {
        UUID id = player.getUUID();
        if (!OutsideMobsServer.setPrefs(id, prefs)) {
            return;
        }
        Track track = TRACKS.get(id);
        if (track == null || DISABLED.contains(id) || !track.pocket.equals(player.serverLevel().dimension())) {
            return;
        }
        ViewTickets.keepLoaded(player.server, id, track, Config.RADIUS.get());
    }

    /** Options des sons du client ; la zone autour du Planarium passe en pause ou tourne a la prochaine mise a jour. */
    public static void setSoundPrefs(ServerPlayer player, SoundPrefsPayload prefs) {
        UUID id = player.getUUID();
        SoundPrefsPayload previous = OutsideSounds.setPrefs(id, prefs);
        if (previous == null) {
            previous = SoundPrefsPayload.DEFAULT;
        }
        if (previous.world() == prefs.world()) {
            return;
        }
        Track track = TRACKS.get(id);
        if (track == null || DISABLED.contains(id) || !track.pocket.equals(player.serverLevel().dimension())) {
            return;
        }
        track.jukeboxSyncPending = prefs.world();
        int radius = Config.RADIUS.get();
        ViewTickets.keepLoaded(player.server, id, track, radius);
        if (prefs.world() && track.hasFull) {
            OutsideSounds.trySendPendingJukeboxes(player, track, radius, DISABLED);
        }
    }

    /** Distance (en blocs) a laquelle on entend le tonnerre d'un eclair du monde d'origine. */
    private static final double THUNDER_RANGE = 512.0;

    /** Fin du suivi d'une entite : derniere position pour la miniature, ou retrait si elle a disparu. */
    @SubscribeEvent
    public static void onEntityLeave(net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel level && isPocket(level)) {
            try {
                MiniatureSync.entityLeft(level, event.getEntity());
            } catch (Exception e) {
                logOnce("mob de miniature", e);
            }
        }
    }

    /** Un eclair tombe dans un monde ou se trouve le Planarium d'un joueur dans son bocal : on le lui envoie. */
    @SubscribeEvent
    public static void onEntityJoin(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event) {
        if (TRACKS.isEmpty() || !(event.getEntity() instanceof net.minecraft.world.entity.LightningBolt bolt)
                || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        try {
            for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
                Track track = TRACKS.get(player.getUUID());
                if (track == null || !track.source.dimension().equals(level.dimension())
                        || !player.serverLevel().dimension().equals(track.pocket)) {
                    continue;
                }
                Vec3 relative = PlanariumAnchor.relative(track, bolt.position());
                double dx = relative.x;
                double dy = relative.y;
                double dz = relative.z;
                if (dx * dx + dz * dz > THUNDER_RANGE * THUNDER_RANGE) {
                    continue;
                }
                PacketDistributor.sendToPlayer(player,
                        new LightningPayload(track.pocket.location(), dx, dy, dz, bolt.seed));
            }
        } catch (Exception e) {
            logOnce("eclair", e);
        }
    }

    /** Sons du monde d'origine : les sons directement causes par un joueur sont filtres par SoundCauseMixin. */
    @SubscribeEvent
    public static void onPlayLevelSound(PlayLevelSoundEvent event) {
        OutsideSounds.onPlayLevelSound(event, TRACKS, DISABLED);
    }

    /**
     * Sons joues par les "evenements de niveau" (ServerLevel.levelEvent, voir LevelEventSoundMixin) : ils ne passent pas
     * par PlayLevelSoundEvent. Seulement les plus utiles ; ceux causes par un joueur sont exclus, comme les autres sons.
     */
    public static void onLevelEvent(ServerLevel level, Player player, int type, BlockPos pos, int data) {
        OutsideSounds.onLevelEvent(level, player, type, pos, data, TRACKS, DISABLED);
    }

    /** Minecraft emits explosion audio locally, outside PlayLevelSoundEvent, so forward this event separately. */
    @SubscribeEvent
    public static void onExplosion(ExplosionEvent.Detonate event) {
        OutsideSounds.onExplosion(event, TRACKS, DISABLED);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        // Filet de securite si un appel de son a leve une exception avant l'injection RETURN du mixin.
        PlayerSoundCause.clear();
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % 2 == 0 && com.matthieu.planariumview.compat.sable.SubLevelCompat.available()) {
            try {
                updateSubLevels(server);
            } catch (Exception e) {
                logOnce("sous-niveaux", e);
            }
        }
        try {
            updateFarRings(server);
        } catch (Exception e) {
            logOnce("anneau lointain", e);
        }
        if (server.getTickCount() % 20 != 0) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            TrackUpdater.update(player, false, TRACKS, DISABLED);
        }
        try {
            MiniatureSync.updateMiniatures(server);
        } catch (Exception e) {
            LOGGER.error("[Planarium View] Erreur lors de la capture de la lumiere des miniatures", e);
        }
        try {
            MiniatureSync.updateMiniEntities(server);
        } catch (Exception e) {
            LOGGER.error("[Planarium Panorama] Erreur lors de la capture des entites des miniatures", e);
        }
        ViewTickets.keepSourcesAwake(server, TRACKS, DISABLED);
    }

    static boolean isPocket(ServerLevel level) {
        return level.dimensionTypeRegistration().is(ArsNouveau.DIMENSION_TYPE_KEY);
    }

    /** Anneau lointain (voir FarRingServer) : avance chaque tick, avec un petit budget. */
    private static void updateFarRings(MinecraftServer server) {
        // Un seul temps de capture par tick pour tous les joueurs et les deux systemes (voir CaptureBudget). L'ordre
        // tourne a chaque tick (joueurs, puis anneau ou vol en premier) : personne n'attend toujours son tour.
        CaptureBudget.reset();
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        int count = players.size();
        if (count == 0) {
            return;
        }
        int tick = server.getTickCount();
        boolean flightFirst = (tick & 1) == 1;
        for (int i = 0; i < count; i++) {
            ServerPlayer player = players.get((i + tick) % count);
            Track track = TRACKS.get(player.getUUID());
            if (track == null || DISABLED.contains(player.getUUID()) || !track.pocket.equals(player.serverLevel().dimension())) continue;
            ServerLevel source = server.getLevel(track.source.dimension());
            if (source == null) continue;
            if (flightFirst) {
                FlightServer.tick(player, source, track.pocket, track.center, track.capturedCenter, Config.RADIUS.get(), track.hasFull);
            }
            FarRingServer.tick(player, source, track.pocket, track.capturedCenter, track.center, track.lastRadius, track.hasFull);
            NeighborPocketsServer.tick(player, source, track.pocket, track.source.pos(), track.lastRadius, track.hasFull);
            try {
                OutsideMobsServer.tick(player, source, track.pocket, track.capturedCenter, track.lastRadius, track.hasFull,
                        FlightServer.active(player.getUUID()));
            } catch (Exception e) {
                logOnce("mobs du dehors", e);
            }
            if (!flightFirst) {
                FlightServer.tick(player, source, track.pocket, track.center, track.capturedCenter, Config.RADIUS.get(), track.hasFull);
            }
        }
    }

    private static void updateSubLevels(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Track track = TRACKS.get(player.getUUID());
            if (track == null || DISABLED.contains(player.getUUID()) || !track.pocket.equals(player.serverLevel().dimension())) continue;
            ServerLevel source = server.getLevel(track.source.dimension());
            if (source == null || !PlanariumAnchor.updateAnchor(track, source)) continue;
            int radius = Config.RADIUS.get();
            ViewTickets.keepLoaded(server, player.getUUID(), track, radius);
            // Avec l'anneau lointain, les structures qui volent plus loin restent visibles (blocs et mouvement).
            int far = Config.FAR_RADIUS.get();
            net.minecraft.world.phys.AABB area = far > radius
                    ? new net.minecraft.world.phys.AABB(track.center).inflate(far + 1, far + 1, far + 1)
                    : new net.minecraft.world.phys.AABB(track.center).inflate(radius + 1);
            SubLevelServer.send(player, track.pocket.location(), source, area, track.anchor, false,
                    track.source.pos(), OutsideSounds.prefsOf(player.getUUID()).world());
        }
        SubLevelServer.updateMiniatures(server);
    }

    static boolean enabled(UUID player) {
        return !DISABLED.contains(player);
    }

    @SubscribeEvent
    public static void onServerStarted(net.neoforged.neoforge.event.server.ServerStartedEvent event) {
        for (ServerLevel level : event.getServer().getAllLevels()) {
            com.matthieu.planariumview.compat.sable.SubLevelCompat.clearVisitorTickets(level);
        }
    }

    @SubscribeEvent
    public static void onServerStopping(net.neoforged.neoforge.event.server.ServerStoppingEvent event) {
        for (UUID id : new ArrayList<>(TRACKS.keySet())) removeTrack(event.getServer(), id);
        SubLevelServer.clear();
        FarRingServer.clearAll();
        OutsideMobsServer.clearAll();
        HiddenChanges.clear();
        NeighborPocketsServer.clearAll();
        FlightServer.clearAll();
    }

    @SubscribeEvent
    public static void onServerStopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event) {
        SubLevelServer.clear();
        // Un autre monde solo peut s'ouvrir ensuite : ne pas lui montrer les miniatures de celui-ci.
        MiniatureSync.clear();
        com.matthieu.planariumview.compat.sable.SubLevelCompat.clearRestoreFailures();
        TRACKS.clear();
    }

    static void removeTrack(MinecraftServer server, UUID id) {
        SubLevelServer.forgetOutside(id);
        LunarServer.forget(id);
        FarRingServer.forget(server, id);
        NeighborPocketsServer.forget(server, id);
        FlightServer.forget(id);
        OutsideMobsServer.forget(id);
        Track track = TRACKS.remove(id);
        if (track == null) {
            return;
        }
        ServerLevel source = server.getLevel(track.source.dimension());
        if (source != null) {
            com.matthieu.planariumview.compat.sable.SubLevelCompat.releaseAnchor(source, id, track.anchorBody);
            ViewTickets.releaseTickets(source, track);
        }
        track.ticketChunks = -1;
    }

}

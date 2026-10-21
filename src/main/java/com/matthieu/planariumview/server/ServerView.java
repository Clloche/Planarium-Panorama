package com.matthieu.planariumview.server;

import com.matthieu.planariumview.client.jar.JarWalls;
import com.matthieu.planariumview.client.scenery.BiomeGrid;
import com.matthieu.planariumview.client.scenery.GiantEntities;
import com.matthieu.planariumview.compat.SeasonCompat;
import com.matthieu.planariumview.config.Config;
import com.matthieu.planariumview.network.AmbiencePayload;
import com.matthieu.planariumview.network.LightningPayload;
import com.matthieu.planariumview.network.MiniEntitiesPayload;
import com.matthieu.planariumview.network.MiniLightPayload;
import com.matthieu.planariumview.network.OutsideRecordPayload;
import com.matthieu.planariumview.network.OutsideSoundPayload;
import com.matthieu.planariumview.network.OutsideViewDeltaPayload;
import com.matthieu.planariumview.network.OutsideViewPayload;
import com.matthieu.planariumview.network.SkyPayload;
import com.matthieu.planariumview.network.SoundPrefsPayload;
import com.matthieu.planariumview.network.WallPayload;

import com.hollingsworth.arsnouveau.ArsNouveau;
import com.hollingsworth.arsnouveau.common.block.tile.PlanariumTile;
import com.hollingsworth.arsnouveau.common.world.saved_data.JarDimData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.server.level.TicketType;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.PlayLevelSoundEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Arrays;
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
    /** Rayon de recherche du Planarium autour de l'endroit d'ou le joueur est entre. */
    private static final int SEARCH_RADIUS = 8;

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private static final Map<UUID, Track> TRACKS = new HashMap<>();
    private static final Set<ResourceKey<Level>> RELIT = new HashSet<>();
    /** Lumiere des bocaux deja envoyee pour les miniatures, et la version du gabarit d'Ars correspondante. */
    private static final Map<ResourceKey<Level>, byte[]> MINI_LIGHT = new HashMap<>();
    private static final Map<ResourceKey<Level>, Long> MINI_VERSION = new HashMap<>();
    /** Porte-armures, tableaux et cadres de chaque bocal, deja envoyes (voir updateMiniEntities). */
    private static final Map<ResourceKey<Level>, net.minecraft.nbt.CompoundTag> MINI_ENTITIES = new HashMap<>();
    /** Bocaux dont Ars vient de refaire le gabarit : leurs entites sont relues aussi. */
    private static final Set<ResourceKey<Level>> MINI_ENTITIES_DUE = new HashSet<>();
    /** Prochain rafraichissement de secours des entites de chaque bocal (tick du serveur). */
    private static final Map<ResourceKey<Level>, Long> MINI_ENTITIES_NEXT = new HashMap<>();

    private static final class Track {
        final ResourceKey<Level> pocket;
        public final UUID owner;
        public GlobalPos source;
        /** Prochaine recherche permise du Planarium quand il n'est plus a sa place (voir updateAnchor). */
        long nextSearch;
        org.joml.Matrix4d anchor;
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
    /** Limites separees : une ferme de mobs ne peut pas empecher d'entendre une porte ou un repeteur. */
    private static final int MAX_SOUNDS_PER_SECOND = 30;
    private static final int MAX_ENTITY_SOUNDS_PER_SECOND = 10;
    /** Options des sons du monde exterieur de chaque joueur (SoundPrefsPayload ; sans message : tout permis). */
    private static final Map<UUID, SoundPrefsPayload> SOUND_PREFS = new java.util.concurrent.ConcurrentHashMap<>();

    private static SoundPrefsPayload prefsOf(UUID id) {
        return SOUND_PREFS.getOrDefault(id, SoundPrefsPayload.DEFAULT);
    }

    /** Ecrit une erreur dans le log une seule fois par type (sinon elle se repeterait chaque seconde). */
    private static void logOnce(String what, Exception e) {
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
        FarRingServer.blockChanged(serverLevel.dimension(), pos);
        boolean lightSourceChanged = previous.getLightEmission(level, pos) != next.getLightEmission(level, pos)
                && (previous.getLightEmission(level, pos) > 0 || next.getLightEmission(level, pos) > 0);
        for (Track track : TRACKS.values()) {
            if (!track.source.dimension().equals(level.dimension())) {
                continue;
            }
            int radius = Math.max(track.lastRadius, Config.RADIUS.get());
            // La zone de la photo affichee : un Planarium qui a un peu bouge montre encore la photo de capturedCenter.
            BlockPos center = track.capturedCenter;
            long dx = Math.abs((long) pos.getX() - center.getX());
            long dz = Math.abs((long) pos.getZ() - center.getZ());
            // Changements visibles : zone capturee plus une case pour les dependances immediates des modeles.
            boolean inScenery = dx <= radius + 1L && dz <= radius + 1L;
            // Hors de cette zone, seuls l'ajout/retrait/modification d'une source lumineuse justifient une capture.
            boolean nearbyLightSource = lightSourceChanged && dx <= radius + LIGHT_MARGIN
                    && dz <= radius + LIGHT_MARGIN;
            if (inScenery || nearbyLightSource) {
                track.sceneryDirty = true;
                // Dans la photo et sans effet sur la lumiere ni la meteo : envoi partiel possible.
                long dy = Math.abs((long) pos.getY() - center.getY());
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
            LOGGER.info("[Planarium Panorama] {} : vue {}", player.getGameProfile().getName(), enabled ? "ON" : "OFF");
            update(player, true);
        }
    }

    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ServerLevel previous = player.server.getLevel(event.getFrom());
            if (previous != null && (isPocket(previous) || isPocket(player.serverLevel()))) {
                // The client has already discarded that dimension. Do not let Sable send its removals to the new one.
                com.matthieu.planariumview.compat.SubLevelCompat.forgetTracking(previous, player.getUUID());
            }
            SubLevelServer.dimensionChanged(player.getUUID(), player.server.getTickCount());
            update(player, true);
        }
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            SubLevelServer.reset(player.getUUID());
            SubLevelServer.dimensionChanged(player.getUUID(), player.server.getTickCount());
            removeTrack(player.server, player.getUUID());
            update(player, true);
            for (Map.Entry<ResourceKey<Level>, byte[]> entry : MINI_LIGHT.entrySet()) {
                PacketDistributor.sendToPlayer(player, new MiniLightPayload(entry.getKey().location(), entry.getValue()));
            }
            if (player.connection.hasChannel(MiniEntitiesPayload.TYPE)) {
                for (Map.Entry<ResourceKey<Level>, net.minecraft.nbt.CompoundTag> entry : MINI_ENTITIES.entrySet()) {
                    PacketDistributor.sendToPlayer(player, new MiniEntitiesPayload(entry.getKey().location(), entry.getValue()));
                }
            }
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            SubLevelServer.reset(player.getUUID());
            removeTrack(player.server, player.getUUID());
            DISABLED.remove(player.getUUID());
            SOUND_PREFS.remove(player.getUUID());
        }
    }

    /** Options des sons du client ; la zone autour du Planarium passe en pause ou tourne a la prochaine mise a jour. */
    public static void setSoundPrefs(ServerPlayer player, SoundPrefsPayload prefs) {
        UUID id = player.getUUID();
        SoundPrefsPayload previous = SOUND_PREFS.put(id, prefs);
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
        keepLoaded(player.server, id, track, radius);
        if (prefs.world() && track.hasFull) {
            trySendPendingJukeboxes(player, track, radius);
        }
    }

    /** Distance (en blocs) a laquelle on entend le tonnerre d'un eclair du monde d'origine. */
    private static final double THUNDER_RANGE = 512.0;

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
                Vec3 relative = relative(track, bolt.position());
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
        if (!(event.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread()
                || event.isCanceled() || PlayerSoundCause.isPlayerCaused()) {
            return;
        }
        SoundSource source = event.getSource();
        if (source != SoundSource.BLOCKS && source != SoundSource.WEATHER && source != SoundSource.RECORDS
                && source != SoundSource.AMBIENT && source != SoundSource.HOSTILE && source != SoundSource.NEUTRAL) {
            return;
        }
        Holder<SoundEvent> sound = event.getSound();
        if (sound == null) {
            return;
        }
        int category = source == SoundSource.HOSTILE || source == SoundSource.NEUTRAL
                ? OutsideSoundPayload.MOBS : OutsideSoundPayload.BLOCKS;
        Vec3 position;
        if (event instanceof PlayLevelSoundEvent.AtEntity atEntity) {
            if (atEntity.getEntity() instanceof net.minecraft.world.entity.player.Player) {
                return;
            }
            position = atEntity.getEntity().position();
            category = OutsideSoundPayload.MOBS;
        } else if (event instanceof PlayLevelSoundEvent.AtPosition atPosition) {
            position = atPosition.getPosition();
        } else {
            return;
        }
        sendOutsideSound(level, position, sound, source, event.getNewVolume(), event.getNewPitch(), category);
    }

    /**
     * Sons joues par les "evenements de niveau" (ServerLevel.levelEvent, voir LevelEventSoundMixin) : ils ne passent pas
     * par PlayLevelSoundEvent. Seulement les plus utiles ; ceux causes par un joueur sont exclus, comme les autres sons.
     */
    public static void onLevelEvent(ServerLevel level, Player player, int type, BlockPos pos, int data) {
        if (TRACKS.isEmpty() || !level.getServer().isSameThread()) {
            return;
        }
        // Jukebox : debut (1010, avec le morceau) et fin (1011) de la musique, joues par le client dans Minecraft.
        if (type == 1010 || type == 1011) {
            String song = "";
            if (type == 1010) {
                song = level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.JUKEBOX_SONG)
                        .getHolder(data).map(holder -> holder.key().location().toString()).orElse("");
                if (song.isEmpty()) {
                    return;
                }
            }
            sendOutsideRecord(level, pos, song);
            return;
        }
        if (player != null || PlayerSoundCause.isPlayerCaused()) {
            return;
        }
        net.minecraft.util.RandomSource random = level.random;
        SoundEvent sound;
        float volume;
        float pitch;
        switch (type) {
            case 2001 -> {
                // Bloc casse (par un mob, un villageois, un enderman...) : son de cassure du bloc.
                BlockState state = net.minecraft.world.level.block.Block.stateById(data);
                if (state.isAir()) {
                    return;
                }
                net.minecraft.world.level.block.SoundType soundType = state.getSoundType();
                sound = soundType.getBreakSound();
                volume = (soundType.getVolume() + 1.0F) / 2.0F;
                pitch = soundType.getPitch() * 0.8F;
            }
            case 1000 -> { sound = SoundEvents.DISPENSER_DISPENSE; volume = 1.0F; pitch = 1.0F; }
            case 1001 -> { sound = SoundEvents.DISPENSER_FAIL; volume = 1.0F; pitch = 1.2F; }
            case 1002 -> { sound = SoundEvents.DISPENSER_LAUNCH; volume = 1.0F; pitch = 1.2F; }
            case 1009 -> {
                sound = SoundEvents.FIRE_EXTINGUISH;
                volume = 0.5F;
                pitch = 2.6F + (random.nextFloat() - random.nextFloat()) * 0.8F;
            }
            case 1030 -> { sound = SoundEvents.ANVIL_USE; volume = 1.0F; pitch = random.nextFloat() * 0.1F + 0.9F; }
            case 1031 -> { sound = SoundEvents.ANVIL_LAND; volume = 0.3F; pitch = random.nextFloat() * 0.1F + 0.9F; }
            case 1501 -> {
                sound = SoundEvents.LAVA_EXTINGUISH;
                volume = 0.5F;
                pitch = 2.6F + (random.nextFloat() - random.nextFloat()) * 0.8F;
            }
            case 1502 -> {
                sound = SoundEvents.REDSTONE_TORCH_BURNOUT;
                volume = 0.5F;
                pitch = 2.6F + (random.nextFloat() - random.nextFloat()) * 0.8F;
            }
            default -> {
                return;
            }
        }
        if (sound == null) {
            return;
        }
        sendOutsideSound(level, Vec3.atCenterOf(pos), net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound),
                SoundSource.BLOCKS, volume, pitch, OutsideSoundPayload.BLOCKS);
    }

    /**
     * Pistons en mouvement dans la photo : pendant 2 ticks, le bloc est un "moving_piston" invisible qui contient
     * l'etat d'arrivee. Une capture tombee a ce moment faisait disparaitre le piston (ou sa tete) du decor jusqu'a la
     * capture suivante. On remplace donc ces blocs par leur etat d'arrivee.
     */
    private static void settleMovingPistons(CompoundTag tag) {
        net.minecraft.nbt.ListTag palette = tag.getList("palette", net.minecraft.nbt.Tag.TAG_COMPOUND);
        net.minecraft.nbt.ListTag blocks = tag.getList("blocks", net.minecraft.nbt.Tag.TAG_COMPOUND);
        Set<Integer> movingStates = new HashSet<>();
        Map<net.minecraft.nbt.Tag, Integer> indexOf = new HashMap<>();
        for (int i = 0; i < palette.size(); i++) {
            CompoundTag state = palette.getCompound(i);
            indexOf.putIfAbsent(state, i);
            if ("minecraft:moving_piston".equals(state.getString("Name"))) {
                movingStates.add(i);
            }
        }
        if (movingStates.isEmpty()) {
            return;
        }
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag block = blocks.getCompound(i);
            if (!movingStates.contains(block.getInt("state")) || !block.contains("nbt", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
                continue;
            }
            CompoundTag nbt = block.getCompound("nbt");
            if (!nbt.contains("blockState", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
                continue;
            }
            CompoundTag arrival = nbt.getCompound("blockState");
            Integer index = indexOf.get(arrival);
            if (index == null) {
                index = palette.size();
                palette.add(arrival.copy());
                indexOf.put(arrival, index);
            }
            block.putInt("state", index);
            block.remove("nbt");
        }
    }

    /** Debut ("" = fin) d'un morceau de jukebox, pour les joueurs dont le Planarium est a portee (64 blocs). */
    private static void sendOutsideRecord(ServerLevel level, BlockPos pos, String song) {
        Vec3 position = com.matthieu.planariumview.compat.SubLevelCompat.worldPosition(level, Vec3.atCenterOf(pos));
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            Track track = TRACKS.get(player.getUUID());
            if (track == null || DISABLED.contains(player.getUUID())
                    || !track.source.dimension().equals(level.dimension())
                    || !player.serverLevel().dimension().equals(track.pocket)
                    || !player.connection.hasChannel(OutsideRecordPayload.TYPE)) {
                continue;
            }
            Vec3 relative = relative(track, position);
            double dx = relative.x;
            double dy = relative.y;
            double dz = relative.z;
            // Fin : toujours envoyee (le client l'ignore s'il ne jouait rien). Debut : a portee, et si le joueur le veut.
            SoundPrefsPayload prefs = prefsOf(player.getUUID());
            if (!song.isEmpty() && (dx * dx + dy * dy + dz * dz > 64.0 * 64.0 || !prefs.world())) {
                continue;
            }
            PacketDistributor.sendToPlayer(player,
                    new OutsideRecordPayload(track.pocket.location(), pos.asLong(), song, dx, dy, dz));
        }
    }

    /**
     * En entrant dans le bocal : les jukebox qui jouent deja autour du Planarium (Minecraft n'envoie le morceau qu'au
     * moment ou le disque est mis). Le morceau reprend depuis le debut, comme dans Minecraft en revenant d'une dimension.
     */
    private static void sendPlayingJukeboxes(ServerPlayer player, Track track) {
        SoundPrefsPayload prefs = prefsOf(player.getUUID());
        ServerLevel level = player.server.getLevel(track.source.dimension());
        if (level == null || !prefs.world() || !player.connection.hasChannel(OutsideRecordPayload.TYPE)) {
            return;
        }
        BlockPos center = track.center;
        ChunkPos centerChunk = new ChunkPos(center);
        for (int cx = centerChunk.x - 4; cx <= centerChunk.x + 4; cx++) {
            for (int cz = centerChunk.z - 4; cz <= centerChunk.z + 4; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) {
                    continue;
                }
                for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                    if (!(blockEntity instanceof net.minecraft.world.level.block.entity.JukeboxBlockEntity jukebox)
                            || !jukebox.getSongPlayer().isPlaying() || jukebox.getSongPlayer().getSong() == null) {
                        continue;
                    }
                    Vec3 position = com.matthieu.planariumview.compat.SubLevelCompat.worldPosition(level, Vec3.atCenterOf(jukebox.getBlockPos()));
                    Vec3 relative = relative(track, position);
                    double dx = relative.x;
                    double dy = relative.y;
                    double dz = relative.z;
                    if (dx * dx + dy * dy + dz * dz > 64.0 * 64.0) {
                        continue;
                    }
                    ResourceLocation songId = level.registryAccess()
                            .registryOrThrow(net.minecraft.core.registries.Registries.JUKEBOX_SONG)
                            .getKey(jukebox.getSongPlayer().getSong());
                    String song = songId == null ? "" : songId.toString();
                    if (song.isEmpty()) {
                        continue;
                    }
                    PacketDistributor.sendToPlayer(player, new OutsideRecordPayload(track.pocket.location(),
                            jukebox.getBlockPos().asLong(), song, dx, dy, dz));
                }
            }
        }
    }

    /** Attend que les chunks du decor soient de nouveau charges avant de resynchroniser les disques en cours. */
    private static void trySendPendingJukeboxes(ServerPlayer player, Track track, int radius) {
        if (!track.jukeboxSyncPending) {
            return;
        }
        UUID id = player.getUUID();
        if (DISABLED.contains(id) || !prefsOf(id).world()
                || !player.connection.hasChannel(OutsideRecordPayload.TYPE)) {
            track.jukeboxSyncPending = false;
            return;
        }
        ServerLevel source = player.server.getLevel(track.source.dimension());
        if (source == null) {
            return;
        }
        BlockPos center = track.center;
        BlockPos min = center.offset(-radius, -radius, -radius);
        BlockPos max = center.offset(radius, radius, radius);
        if (!source.hasChunksAt(min, max)) {
            return;
        }
        sendPlayingJukeboxes(player, track);
        track.jukeboxSyncPending = false;
    }

    /** Minecraft emits explosion audio locally, outside PlayLevelSoundEvent, so forward this event separately. */
    @SubscribeEvent
    public static void onExplosion(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread()) {
            return;
        }
        // Meme son et meme hauteur que Minecraft (le son d'une charge de vent n'est pas celui d'une TNT).
        float pitch = (1.0F + (level.random.nextFloat() - level.random.nextFloat()) * 0.2F) * 0.7F;
        sendOutsideSound(level, event.getExplosion().center(), event.getExplosion().getExplosionSound(),
                SoundSource.BLOCKS, 4.0F, pitch, OutsideSoundPayload.EXPLOSIONS);
    }

    private static void sendOutsideSound(ServerLevel level, Vec3 position, Holder<SoundEvent> sound,
                                         SoundSource source, float volume, float pitch, int category) {
        if (TRACKS.isEmpty() || volume <= 0.0F) {
            return;
        }
        ResourceLocation soundId = sound.unwrapKey().map(ResourceKey::location).orElse(null);
        if (soundId == null) {
            return;
        }
        double range = sound.value().getRange(volume);
        if (!(range > 0.0) || !Double.isFinite(range)) {
            return;
        }
        long now = System.nanoTime();
        position = com.matthieu.planariumview.compat.SubLevelCompat.worldPosition(level, position);
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            Track track = TRACKS.get(player.getUUID());
            if (track == null || DISABLED.contains(player.getUUID())
                    || !track.source.dimension().equals(level.dimension())
                    || !player.serverLevel().dimension().equals(track.pocket)
                    || !player.connection.hasChannel(OutsideSoundPayload.TYPE)
                    || !prefsOf(player.getUUID()).allows(category)) {
                continue;
            }
            Vec3 relative = relative(track, position);
            double dx = relative.x;
            double dy = relative.y;
            double dz = relative.z;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (distance >= range) {
                continue;
            }
            if (track.soundWindowStart == 0L || now - track.soundWindowStart >= 1_000_000_000L) {
                track.soundWindowStart = now;
                track.soundsInWindow = 0;
                track.entitySoundsInWindow = 0;
            }
            if (category == OutsideSoundPayload.MOBS) {
                if (track.entitySoundsInWindow >= MAX_ENTITY_SOUNDS_PER_SECOND) {
                    continue;
                }
                track.entitySoundsInWindow++;
            } else {
                if (track.soundsInWindow >= MAX_SOUNDS_PER_SECOND) {
                    continue;
                }
                track.soundsInWindow++;
            }
            float attenuatedVolume = (float) (volume * (1.0 - distance / range));
            PacketDistributor.sendToPlayer(player, new OutsideSoundPayload(track.pocket.location(), soundId,
                    source.ordinal(), attenuatedVolume, pitch, dx, dy, dz, category));
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        // Filet de securite si un appel de son a leve une exception avant l'injection RETURN du mixin.
        PlayerSoundCause.clear();
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % 2 == 0 && com.matthieu.planariumview.compat.SubLevelCompat.available()) {
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
            update(player, false);
        }
        try {
            updateMiniatures(server);
        } catch (Exception e) {
            LOGGER.error("[Planarium View] Erreur lors de la capture de la lumiere des miniatures", e);
        }
        try {
            updateMiniEntities(server);
        } catch (Exception e) {
            LOGGER.error("[Planarium Panorama] Erreur lors de la capture des entites des miniatures", e);
        }
        keepSourcesAwake(server);
    }

    /**
     * Option "Sons du monde exterieur" ON.
     * Minecraft arrete de faire tourner les entites et les blocs speciaux d'une dimension 15 s apres que le dernier
     * joueur l'a quittee (pistons figes en plein mouvement, fours, entonnoirs et mobs arretes). Un joueur qui regarde
     * cette dimension depuis un bocal la garde donc "occupee", comme s'il y etait. Les chunks qui tournent restent ceux
     * que le Planarium garde charges, plus ceux que Minecraft garde deja.
     */
    private static void keepSourcesAwake(MinecraftServer server) {
        for (Map.Entry<UUID, Track> entry : TRACKS.entrySet()) {
            // Seulement pour les joueurs qui veulent les sons du monde exterieur (zone active, voir keepLoaded).
            if (DISABLED.contains(entry.getKey()) || !entry.getValue().ticketActive) {
                continue;
            }
            ServerLevel source = server.getLevel(entry.getValue().source.dimension());
            if (source != null && source.players().isEmpty()) {
                source.resetEmptyTime();
            }
        }
    }

    private static boolean isPocket(ServerLevel level) {
        return level.dimensionTypeRegistration().is(ArsNouveau.DIMENSION_TYPE_KEY);
    }

    private static void update(ServerPlayer player, boolean force) {
        try {
            doUpdate(player, force);
        } catch (Exception e) {
            LOGGER.error("[Planarium View] Erreur lors de la capture des alentours", e);
        }
    }

    private static void doUpdate(ServerPlayer player, boolean force) {
        ServerLevel level = player.serverLevel();
        UUID id = player.getUUID();
        Track track = TRACKS.get(id);

        if (!isPocket(level) || DISABLED.contains(id)) {
            if (track != null) {
                removeTrack(player.server, id);
                PacketDistributor.sendToPlayer(player, OutsideViewPayload.clear(track.pocket.location()));
            }
            return;
        }

        if (track == null || !track.pocket.equals(level.dimension())) {
            GlobalPos source = findPlanarium(player, level);
            if (source == null) {
                if (track != null) {
                    removeTrack(player.server, id);
                    PacketDistributor.sendToPlayer(player, OutsideViewPayload.clear(track.pocket.location()));
                }
                return;
            }
            if (track != null) {
                removeTrack(player.server, id);
            }
            track = new Track(id, level.dimension(), source);
            TRACKS.put(id, track);
            force = true;
        }
        ServerLevel outside = player.server.getLevel(track.source.dimension());
        if (outside != null && !updateAnchor(track, outside)) return;
        // On attend que les chunks du bocal soient charges : sinon le recalcul serait saute.
        if (!track.relit) {
            removeLightsBelowFloor(level);
            track.relit = relightPocket(level);
        }

        // Chaque envoi est protege : une erreur (souvent un autre mod) ne doit pas bloquer la capture du decor.
        try {
            sendSky(player, track);
        } catch (Exception e) {
            logOnce("ciel", e);
        }
        // Evenement lunaire d'Enhanced Celestials du monde d'origine (envoye seulement s'il change).
        ServerLevel lunarSource = player.server.getLevel(track.source.dimension());
        if (lunarSource != null) {
            LunarServer.send(player, track.pocket.location(), lunarSource);
        }
        try {
            sendAmbience(player, track);
        } catch (Exception e) {
            logOnce("ambiance", e);
        }
        try {
            // Les parois seulement apres le recalcul de la lumiere du ciel, qui doit partir de donnees a jour.
            if (track.relit) {
                updateWalls(player, track);
                if (player.server.getTickCount() % 20 == 0) {
                    logSkyDiag(level, track);
                }
            }
        } catch (Exception e) {
            logOnce("parois", e);
        }

        int radius = Config.RADIUS.get();
        // Le rayon a change dans la config : nouvelle photo tout de suite, sans ressortir du bocal.
        if (radius != track.lastRadius) {
            force = true;
        }
        keepLoaded(player.server, player.getUUID(), track, radius);
        if (track.hasFull) {
            trySendPendingJukeboxes(player, track, radius);
        }

        long now = player.server.getTickCount();
        if (track.center.distSqr(track.capturedCenter) > Math.max(4, radius * radius / 4)
                && now - track.lastCapture >= 20) force = true;
        int refresh = Config.REFRESH_SECONDS.get() * 20;
        // Les changements de blocs declenchent une capture au prochain tick de suivi, mais au plus toutes les 5 secondes
        // (une ferme, un four ou de la redstone changent des blocs sans arret). La minuterie reste un filet de securite
        // pour les donnees moddees qui changent sans appeler LevelChunk.setBlockState.
        // Ecart minimum entre deux captures declenchees par des changements de blocs (option, 0 = OFF).
        // 5 s minimum (une ancienne config peut contenir une valeur plus basse) ; 0 = OFF.
        int blockSeconds = Config.BLOCK_UPDATE_SECONDS.get();
        int blockGap = blockSeconds > 0 ? Math.max(blockSeconds, 5) * 20 : 0;
        boolean dirtyReady = blockGap > 0 && track.sceneryDirty && now >= track.lastCapture + blockGap;
        boolean followUpReady = track.followUpAt != 0 && now >= track.followUpAt;
        // Secours OFF (0) : seulement les captures declenchees par les changements de blocs.
        boolean backupReady = refresh > 0 && now >= track.nextRefresh;
        if (!force && !dirtyReady && !followUpReady && !backupReady) {
            return;
        }
        // Seulement des blocs sans effet sur la lumiere ont change : on n'envoie que ces blocs (quelques ms)
        // au lieu de recapturer toute la zone (60 a 90 ms a rayon 32).
        if (dirtyReady && !force && !followUpReady && !backupReady && track.hasFull && !track.fullCaptureNeeded
                && radius == track.lastRadius && player.connection.hasChannel(OutsideViewDeltaPayload.TYPE)) {
            track.lastCapture = now;
            track.sceneryDirty = false;
            sendDelta(player, track, radius);
            return;
        }
        track.lastCapture = now;
        // Apres un changement de bloc qui peut toucher la lumiere : une capture de plus un peu plus tard (au moins 5 s),
        // avec la lumiere a jour. Pas pour les blocs sans effet sur la lumiere (sinon une horloge de redstone
        // relancerait une capture complete toutes les 5 s).
        track.followUpAt = dirtyReady && track.lightChangePending ? now + Math.max(blockGap, 5 * 20) : 0;
        String reason = force ? "rayon, dimension ou interrupteur" : followUpReady ? "suivi (lumiere a jour)"
                : dirtyReady ? (track.fullCaptureReason.isEmpty() ? "changement de bloc" : track.fullCaptureReason)
                : "capture de secours";
        // Les entites ou donnees de biome moddees peuvent changer sans changement de bloc ; garde une capture de securite,
        // toutes les 5 minutes par defaut, et jamais plus d'une fois par minute (meme si une ancienne config dit 5 s).
        track.nextRefresh = refresh > 0 ? now + Math.max(refresh, 20 * 60) : Long.MAX_VALUE;

        long captureStart = System.nanoTime();
        Snapshot snap = capture(player.server, track, radius, force);
        if (snap == null) {
            return;
        }
        boolean originMoved = !track.capturedCenter.equals(track.center);
        track.capturedCenter = track.center;
        track.sceneryDirty = false;
        // La photo complete contient tous les changements en attente.
        track.changedBlocks.clear();
        track.fullCaptureNeeded = false;
        track.fullCaptureReason = "";
        track.lightChangePending = false;
        long compareStart = System.nanoTime();
        // Capture declenchee par un changement de bloc : on sait deja que le decor a change, inutile de comparer
        // toute la photo (10 a 15 ms). Si le bloc est revenu a son etat d'avant, le client ne trouvera rien a corriger.
        boolean changed = originMoved || (dirtyReady && track.lastSent != null)
                || !snap.tag().equals(track.lastSent) || !Arrays.equals(snap.light(), track.lastLight)
                || !snap.biomePalette().equals(track.lastBiomePalette) || !Arrays.equals(snap.biomes(), track.lastBiomes)
                || !Arrays.equals(snap.rainTops(), track.lastRainTops) || !Arrays.equals(snap.precip(), track.lastPrecip);
        long compareNanos = System.nanoTime() - compareStart;
        if (!changed) {
            return;
        }
        // Temps passe sur le fil du serveur (en solo, il partage le processeur avec le jeu).
        LOGGER.info("[Planarium Panorama] Envoi du decor complet (cause : {}, capture={} ms, comparaison={} ms)",
                reason, (compareStart - captureStart) / 1_000_000L, compareNanos / 1_000_000L);
        // Description detaillee des changements : elle parcourt aussi les blocs (10 a 50 ms), donc seulement en DEBUG.
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("[Planarium Panorama] Changements du decor : {}", describeSnapshotChanges(track, snap));
        }
        track.lastSent = snap.tag();
        track.lastLight = snap.light();
        track.lastBiomePalette = snap.biomePalette();
        track.lastBiomes = snap.biomes();
        track.lastRainTops = snap.rainTops();
        track.lastPrecip = snap.precip();
        track.lastRadius = radius;
        boolean firstSend = !track.hasFull;
        track.hasFull = true;
        PacketDistributor.sendToPlayer(player,
                new OutsideViewPayload(true, track.pocket.location(), radius, snap.tag(), snap.light(),
                        snap.origin(), snap.biomePalette(), snap.biomes(), snap.rainTops(), snap.precip()));
        if (firstSend) {
            sendPlayingJukeboxes(player, track);
            track.jukeboxSyncPending = false;
        }
    }

    /** Envoie seulement les blocs changes (positions relatives a la photo, etat, donnees du bloc special). */
    private static void sendDelta(ServerPlayer player, Track track, int radius) {
        long start = System.nanoTime();
        ServerLevel level = player.server.getLevel(track.source.dimension());
        if (level == null) {
            track.changedBlocks.clear();
            return;
        }
        BlockPos min = track.capturedCenter.offset(-radius, -radius, -radius);
        int size = radius * 2 + 1;
        net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
        for (BlockPos pos : track.changedBlocks) {
            int x = pos.getX() - min.getX();
            int y = pos.getY() - min.getY();
            int z = pos.getZ() - min.getZ();
            if (x < 0 || y < 0 || z < 0 || x >= size || y >= size || z >= size || !level.isLoaded(pos)) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            // Bloc de piston en mouvement (2 ticks) : invisible tel quel, on envoie son etat d'arrivee.
            boolean moving = false;
            if (state.is(Blocks.MOVING_PISTON)
                    && level.getBlockEntity(pos) instanceof net.minecraft.world.level.block.piston.PistonMovingBlockEntity piston) {
                state = piston.getMovedState();
                moving = true;
            }
            CompoundTag entry = new CompoundTag();
            entry.put("pos", new net.minecraft.nbt.IntArrayTag(new int[]{x, y, z}));
            // Comme la photo complete : l'air n'y figure pas (absence d'etat = bloc retire).
            if (!state.is(Blocks.AIR)) {
                entry.put("state", net.minecraft.nbt.NbtUtils.writeBlockState(state));
                BlockEntity blockEntity = moving ? null : level.getBlockEntity(pos);
                if (blockEntity != null) {
                    entry.put("nbt", blockEntity.saveWithId(level.registryAccess()));
                }
            }
            list.add(entry);
        }
        track.changedBlocks.clear();
        if (list.isEmpty()) {
            return;
        }
        // La photo complete gardee ne correspond plus au client : la prochaine sera envoyee meme si elle lui ressemble.
        track.lastSent = null;
        CompoundTag changes = new CompoundTag();
        changes.put("blocks", list);
        PacketDistributor.sendToPlayer(player,
                new OutsideViewDeltaPayload(track.pocket.location(), radius, min, changes));
        LOGGER.info("[Planarium Panorama] Envoi des blocs changes : {} blocs, {} ms", list.size(),
                String.format(java.util.Locale.ROOT, "%.1f", (System.nanoTime() - start) / 1e6));
    }

    /** Envoie l'heure, la meteo et les couleurs du ciel du monde d'origine (si ce monde a un ciel). */
    private static void sendSky(ServerPlayer player, Track track) {
        ServerLevel source = player.server.getLevel(track.source.dimension());
        if (source == null) {
            return;
        }
        // Sans ciel (le Nether...) : pas de ciel dans le bocal, mais la lumiere du dehors s'applique quand meme
        // (interieur sombre sans torche a cote du Planarium, comme le decor).
        boolean hasSky = source.dimensionType().hasSkyLight() && !source.dimensionType().hasCeiling();
        Biome biome = source.getBiome(track.center).value();
        PacketDistributor.sendToPlayer(player, new SkyPayload(
                track.pocket.location(),
                source.getDayTime(),
                source.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT),
                source.getRainLevel(1.0F),
                source.getThunderLevel(1.0F),
                // Lues directement dans les effets du biome : en solo, Dynamic Surroundings modifie
                // Biome.getFogColor pour le client, et son code plante s'il est appele cote serveur.
                biome.getModifiedSpecialEffects().getSkyColor(),
                biome.getModifiedSpecialEffects().getFogColor(),
                SeasonCompat.seasonTicks(source),
                hasSky ? Math.min(outsideLight(source, track.source.pos(), LightLayer.SKY), outsideLight(source, track.center, LightLayer.SKY)) : 0,
                Math.max(outsideLight(source, track.source.pos(), LightLayer.BLOCK), outsideLight(source, track.center, LightLayer.BLOCK)),
                // Le ciel de la dimension d'origine (celui de l'Aether, par exemple).
                source.dimensionType().effectsLocation(),
                // Le type de dimension d'origine : sa facon de compter les jours (plus longs dans l'Aether).
                source.dimensionTypeRegistration().unwrapKey().map(net.minecraft.resources.ResourceKey::location)
                        .orElse(ResourceLocation.withDefaultNamespace("overworld")),
                hasSky,
                planariumName(source, track.source.pos())));
    }

    /** Lumiere qui arrive au Planarium : la plus forte parmi sa case et ses 6 voisines (le bocal est en verre). */
    /** Nom donne au Planarium (renomme dans une enclume), ou vide. Sert au titre de Traveler's Titles. */
    private static String planariumName(ServerLevel source, BlockPos pos) {
        if (!source.isLoaded(pos) || !(source.getBlockEntity(pos) instanceof PlanariumTile tile)) {
            return "";
        }
        net.minecraft.network.chat.Component name = tile.getCustomName();
        if (name == null) {
            return "";
        }
        String text = name.getString();
        return text.length() > 64 ? text.substring(0, 64) : text;
    }

    private static int outsideLight(ServerLevel level, BlockPos pos, LightLayer layer) {
        int best = level.getBrightness(layer, pos);
        for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.values()) {
            best = Math.max(best, level.getBrightness(layer, pos.relative(direction)));
        }
        return best;
    }

    /**
     * Retrouve le Planarium par lequel le joueur est entre, a partir de la position
     * memorisee par Ars Nouveau (JarDimData.enteredFrom).
     */
    private static GlobalPos findPlanarium(ServerPlayer player, ServerLevel pocket) {
        JarDimData.RotPos from = JarDimData.from(pocket).getEnteredFrom(player.getUUID());
        if (from == null) {
            return null;
        }
        ServerLevel sourceLevel = player.server.getLevel(from.pos().dimension());
        if (sourceLevel == null) {
            return null;
        }
        BlockPos center = from.pos().pos();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-SEARCH_RADIUS, -SEARCH_RADIUS, -SEARCH_RADIUS),
                center.offset(SEARCH_RADIUS, SEARCH_RADIUS, SEARCH_RADIUS))) {
            BlockEntity be = sourceLevel.getBlockEntity(pos);
            if (be instanceof PlanariumTile tile && pocket.dimension().equals(tile.key)) {
                double d = pos.distSqr(center);
                if (d < bestDist) {
                    bestDist = d;
                    best = pos.immutable();
                }
            }
        }
        if (best == null) {
            best = com.matthieu.planariumview.compat.SubLevelCompat.findPlanarium(sourceLevel, pocket.dimension(),
                    Vec3.atLowerCornerOf(center), SEARCH_RADIUS);
        }
        return best == null ? null : GlobalPos.of(sourceLevel.dimension(), best);
    }

    private record Snapshot(CompoundTag tag, byte[] light, BlockPos origin, List<ResourceLocation> biomePalette,
                            byte[] biomes, int[] rainTops, byte[] precip) {
    }

    /** Explique en une ligne quelles parties du snapshot ont change depuis le dernier envoi. */
    private static String describeSnapshotChanges(Track track, Snapshot current) {
        if (track.lastSent == null) {
            return "premiere capture";
        }
        net.minecraft.nbt.ListTag oldBlocks = track.lastSent.getList("blocks", net.minecraft.nbt.Tag.TAG_COMPOUND);
        net.minecraft.nbt.ListTag newBlocks = current.tag().getList("blocks", net.minecraft.nbt.Tag.TAG_COMPOUND);
        net.minecraft.nbt.ListTag oldPalette = track.lastSent.getList("palette", net.minecraft.nbt.Tag.TAG_COMPOUND);
        net.minecraft.nbt.ListTag newPalette = current.tag().getList("palette", net.minecraft.nbt.Tag.TAG_COMPOUND);
        boolean blockStatesChanged = false;
        boolean blockEntityDataChanged = false;
        String blockExample = null;
        String blockStateExample = null;
        String blockEntityExample = null;
        int firstDifference = 0;
        int sharedSize = Math.min(oldBlocks.size(), newBlocks.size());
        while (firstDifference < sharedSize
                && oldBlocks.getCompound(firstDifference).equals(newBlocks.getCompound(firstDifference))) {
            firstDifference++;
        }
        if (firstDifference < sharedSize) {
            var oldBlock = oldBlocks.getCompound(firstDifference);
            var newBlock = newBlocks.getCompound(firstDifference);
            int[] oldPos = oldBlock.getIntArray("pos");
            int[] newPos = newBlock.getIntArray("pos");
            boolean samePos = Arrays.equals(oldPos, newPos);
            int oldState = oldBlock.getInt("state");
            int newState = newBlock.getInt("state");
            boolean sameState = oldState == newState && oldPalette.equals(newPalette)
                    || paletteState(oldPalette, oldState).equals(paletteState(newPalette, newState));
            if (!samePos || !sameState) {
                blockStatesChanged = true;
                blockExample = worldPosition(newBlock, current.origin());
                blockStateExample = paletteState(oldPalette, oldState) + " -> " + paletteState(newPalette, newState);
            }
            if (samePos && !java.util.Objects.equals(oldBlock.get("nbt"), newBlock.get("nbt"))) {
                blockEntityDataChanged = true;
                blockEntityExample = worldPosition(newBlock, current.origin());
            }
        } else if (oldBlocks.size() != newBlocks.size()) {
            blockStatesChanged = true;
            var block = firstDifference < newBlocks.size()
                    ? newBlocks.getCompound(firstDifference) : oldBlocks.getCompound(firstDifference);
            blockExample = worldPosition(block, current.origin());
            blockStateExample = firstDifference < newBlocks.size()
                    ? "<absent> -> " + paletteState(newPalette, block.getInt("state"))
                    : paletteState(oldPalette, block.getInt("state")) + " -> <absent>";
        }
        boolean entitiesChanged = !track.lastSent.getList("entities", net.minecraft.nbt.Tag.TAG_COMPOUND)
                .equals(current.tag().getList("entities", net.minecraft.nbt.Tag.TAG_COMPOUND));
        boolean lightChanged = !Arrays.equals(current.light(), track.lastLight);
        boolean biomesChanged = !current.biomePalette().equals(track.lastBiomePalette)
                || !Arrays.equals(current.biomes(), track.lastBiomes);
        boolean weatherChanged = !Arrays.equals(current.rainTops(), track.lastRainTops)
                || !Arrays.equals(current.precip(), track.lastPrecip);
        boolean otherTagChanged = !blockStatesChanged && !blockEntityDataChanged && !entitiesChanged
                && !current.tag().equals(track.lastSent);
        return "blocs=" + blockStatesChanged + positionSuffix(blockExample) + stateSuffix(blockStateExample)
                + ", donnees de blocs speciaux=" + blockEntityDataChanged + positionSuffix(blockEntityExample)
                + ", entites=" + entitiesChanged + ", lumiere=" + lightChanged
                + ", biomes=" + biomesChanged + ", meteo=" + weatherChanged + ", autres donnees=" + otherTagChanged;
    }

    private static String paletteState(net.minecraft.nbt.ListTag palette, int index) {
        return index >= 0 && index < palette.size() ? palette.getCompound(index).toString() : "<index invalide:" + index + ">";
    }

    private static String worldPosition(net.minecraft.nbt.CompoundTag block, BlockPos origin) {
        int[] pos = block.getIntArray("pos");
        return pos.length == 3 ? "[" + (origin.getX() + pos[0]) + "," + (origin.getY() + pos[1]) + ","
                + (origin.getZ() + pos[2]) + "]" : "[position inconnue]";
    }

    private static String positionSuffix(String position) {
        return position == null ? "" : " (exemple " + position + ")";
    }

    private static String stateSuffix(String state) {
        return state == null ? "" : " [" + state + "]";
    }

    private static Snapshot capture(MinecraftServer server, Track track, int radius, boolean allowLoad) {
        ServerLevel sourceLevel = server.getLevel(track.source.dimension());
        if (sourceLevel == null) {
            return null;
        }
        BlockPos planarium = track.center;
        BlockPos min = planarium.offset(-radius, -radius, -radius);
        BlockPos max = planarium.offset(radius, radius, radius);
        // Pour les rafraichissements, on ne force pas le chargement des chunks d'origine.
        if (!allowLoad && !sourceLevel.hasChunksAt(min, max)) {
            return null;
        }
        // Le Planarium doit toujours etre la et pointer vers cette dimension.
        if (!(sourceLevel.getBlockEntity(track.source.pos()) instanceof PlanariumTile tile) || !track.pocket.equals(tile.key)) {
            return null;
        }
        StructureTemplate template = new StructureTemplate();
        int size = radius * 2 + 1;
        template.fillFromWorld(sourceLevel, min, new Vec3i(size, size, size), false, Blocks.AIR);
        // Biomes en 3D (comme Minecraft : un par cube de 4 blocs), pour les couleurs d'herbe, de feuilles et d'eau.
        BiomeGrid grid = BiomeGrid.of(min, size);
        List<ResourceLocation> palette = new ArrayList<>();
        Map<ResourceLocation, Integer> indexOf = new HashMap<>();
        byte[] biomes = new byte[grid.volume()];
        for (int qx = grid.minQx(); qx < grid.minQx() + grid.sizeX(); qx++) {
            for (int qy = grid.minQy(); qy < grid.minQy() + grid.sizeY(); qy++) {
                for (int qz = grid.minQz(); qz < grid.minQz() + grid.sizeZ(); qz++) {
                    ResourceLocation id = sourceLevel.getNoiseBiome(qx, qy, qz).unwrapKey()
                            .map(net.minecraft.resources.ResourceKey::location)
                            .orElse(ResourceLocation.withDefaultNamespace("plains"));
                    Integer index = indexOf.get(id);
                    if (index == null) {
                        if (palette.size() >= 255) {
                            index = 0;
                        } else {
                            index = palette.size();
                            palette.add(id);
                            indexOf.put(id, index);
                        }
                    }
                    biomes[grid.index(qx, qy, qz)] = (byte) (int) index;
                }
            }
        }
        // Meteo : pour chaque colonne, ou la pluie s'arrete (premier bloc qui la bloque) et si c'est de la pluie ou de la neige.
        int[] rainTops = new int[size * size];
        byte[] precip = new byte[size * size];
        boolean hasWeather = sourceLevel.dimensionType().hasSkyLight() && !sourceLevel.dimensionType().hasCeiling();
        BlockPos.MutableBlockPos column = new BlockPos.MutableBlockPos();
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                int wx = min.getX() + x;
                int wz = min.getZ() + z;
                int top = sourceLevel.getHeight(Heightmap.Types.MOTION_BLOCKING, wx, wz);
                rainTops[x * size + z] = top;
                if (hasWeather) {
                    column.set(wx, top, wz);
                    Biome.Precipitation type = SeasonCompat.precipitation(sourceLevel, sourceLevel.getBiome(column), column);
                    precip[x * size + z] = (byte) (type == Biome.Precipitation.RAIN ? 1 : type == Biome.Precipitation.SNOW ? 2 : 0);
                }
            }
        }
        CompoundTag tag = template.save(new CompoundTag());
        settleMovingPistons(tag);
        GiantEntities.capture(sourceLevel, min, size, tag);
        return new Snapshot(tag, captureLight(sourceLevel, min, size), min, palette, biomes,
                rainTops, precip);
    }

    /**
     * Lumiere du ciel et des blocs pour chaque case du cube, avec une marge d'un bloc
     * (les faces au bord prennent la lumiere de la case voisine). Un octet par case : ciel << 4 | blocs.
     */
    private static byte[] captureLight(ServerLevel level, BlockPos min, int size) {
        return captureLight(level, min.offset(-1, -1, -1), size + 2, size + 2, size + 2);
    }

    /** Lit la lumiere d'une boite qui commence a {@code start} (marge deja incluse). */
    static byte[] captureLight(ServerLevel level, BlockPos start, int sizeX, int sizeY, int sizeZ) {
        byte[] light = new byte[sizeX * sizeY * sizeZ];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int i = 0;
        for (int x = 0; x < sizeX; x++) {
            for (int y = 0; y < sizeY; y++) {
                for (int z = 0; z < sizeZ; z++) {
                    pos.set(start.getX() + x, start.getY() + y, start.getZ() + z);
                    int sky = level.getBrightness(LightLayer.SKY, pos);
                    int block = level.getBrightness(LightLayer.BLOCK, pos);
                    light[i++] = (byte) ((sky << 4) | block);
                }
            }
        }
        return light;
    }

    /**
     * Les bocaux crees avant l'ajout de la lumiere du ciel n'ont pas de donnees de lumiere du ciel.
     * On recalcule l'eclairage des chunks du bocal une fois par session et on le renvoie aux joueurs.
     */
    /**
     * Diagnostic : lumiere du ciel au centre du bocal, du plafond au plancher, et comment Minecraft voit le plafond.
     * Ecrit dans le log seulement quand ca change.
     */
    private static void logSkyDiag(ServerLevel pocket, Track track) {
        StringBuilder line = new StringBuilder("ciel au centre (y 40, 33, 32, 31, 24, 16, 8, 1) :");
        for (int y : new int[]{40, 33, 32, 31, 24, 16, 8, 1}) {
            line.append(' ').append(pocket.getBrightness(net.minecraft.world.level.LightLayer.SKY, new BlockPos(16, y, 16)));
        }
        BlockPos ceiling = new BlockPos(16, JarWalls.MAX_Y, 16);
        net.minecraft.world.level.block.state.BlockState state = pocket.getBlockState(ceiling);
        line.append(" ; plafond ").append(state)
                .append(" bloque ").append(state.getLightBlock(pocket, ceiling))
                .append(" laisse passer ").append(state.propagatesSkylightDown(pocket, ceiling))
                .append(" ; source du ciel la plus basse ")
                .append(pocket.getChunk(ceiling).getSkyLightSources().getLowestSourceY(16 & 15, 16 & 15))
                .append(" ; ciel du monde ").append(pocket.getSkyDarken());
        String text = line.toString();
        if (!text.equals(track.lastSkyDiag)) {
            track.lastSkyDiag = text;
            LOGGER.info("[Planarium Panorama] Diagnostic du bocal : {}", text);
        }
    }

    private static final java.util.Set<net.minecraft.resources.ResourceKey<Level>> FLOOR_CLEANED = new java.util.HashSet<>();
    private static final net.minecraft.resources.ResourceLocation AMBERSOL_LIGHT =
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("alexscaves", "ambersol_light");

    /**
     * Alex's Caves : avant le correctif (voir AcAmbersolLightMixin), un ambersol posait des lumieres invisibles a travers
     * le plancher du bocal, qui restaient apres l'avoir casse et eclairaient le plancher. On les retire une fois.
     */
    private static void removeLightsBelowFloor(ServerLevel pocket) {
        if (FLOOR_CLEANED.contains(pocket.dimension())
                || !net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(AMBERSOL_LIGHT)) {
            return;
        }
        net.minecraft.world.level.block.Block light = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(AMBERSOL_LIGHT);
        java.util.List<BlockPos> found = new java.util.ArrayList<>();
        for (int cx = -1; cx <= 2; cx++) {
            for (int cz = -1; cz <= 2; cz++) {
                LevelChunk chunk = pocket.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) {
                    return;
                }
                for (int y = pocket.getMinBuildHeight(); y < JarWalls.MIN_Y; y++) {
                    if (chunk.getSection(chunk.getSectionIndex(y)).hasOnlyAir()) {
                        continue;
                    }
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            BlockPos pos = new BlockPos(cx * 16 + x, y, cz * 16 + z);
                            if (chunk.getBlockState(pos).is(light)) {
                                found.add(pos);
                            }
                        }
                    }
                }
            }
        }
        FLOOR_CLEANED.add(pocket.dimension());
        for (BlockPos pos : found) {
            pocket.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
        }
        if (!found.isEmpty()) {
            LOGGER.info("[Planarium Panorama] {} lumieres d'ambersol retirees sous le bocal ({})", found.size(),
                    pocket.dimension().location());
        }
    }

    /** Retourne vrai si c'est fait (ou inutile), faux si les chunks du bocal ne sont pas encore tous charges. */
    private static boolean relightPocket(ServerLevel pocket) {
        if (!pocket.dimensionType().hasSkyLight() || RELIT.contains(pocket.dimension())) {
            return true;
        }
        LevelChunk[] chunks = new LevelChunk[16];
        int n = 0;
        for (int cx = -1; cx <= 2; cx++) {
            for (int cz = -1; cz <= 2; cz++) {
                LevelChunk chunk = pocket.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) {
                    return false;
                }
                chunks[n++] = chunk;
            }
        }
        RELIT.add(pocket.dimension());
        ThreadedLevelLightEngine engine = pocket.getChunkSource().getLightEngine();
        for (LevelChunk chunk : chunks) {
            ChunkPos chunkPos = chunk.getPos();
            // Recalcule d'ou part la lumiere du ciel (les parois du bocal la laissent passer).
            chunk.initializeLightSources();
            engine.lightChunk(chunk, false).thenRunAsync(() -> {
                ClientboundLightUpdatePacket packet = new ClientboundLightUpdatePacket(chunkPos, engine, null, null);
                for (ServerPlayer p : pocket.players()) {
                    p.connection.send(packet);
                }
                // La miniature sera recapturee avec l'eclairage recalcule.
                MINI_VERSION.remove(pocket.dimension());
            }, pocket.getServer());
        }
        LOGGER.info("[Planarium Panorama] Lumiere du ciel du bocal recalculee ({})", pocket.dimension().location());
        return true;
    }

    /**
     * Quand Ars met a jour le gabarit d'un bocal (blocs changes), on lit aussi la lumiere
     * de l'interieur du bocal et on l'envoie aux clients pour la miniature.
     */
    private static void updateMiniatures(MinecraftServer server) {
        for (Map.Entry<ResourceKey<Level>, PlanariumTile.DimManager.Entry> entry : PlanariumTile.dimManager.entries.entrySet()) {
            ResourceKey<Level> key = entry.getKey();
            PlanariumTile.DimManager.Entry dimEntry = entry.getValue();
            if (dimEntry == null || dimEntry.dirty()) {
                continue;
            }
            Long version = MINI_VERSION.get(key);
            if (version != null && version == dimEntry.lastUpdated()) {
                continue;
            }
            ServerLevel pocket = server.getLevel(key);
            if (pocket == null) {
                continue;
            }
            MINI_VERSION.put(key, dimEntry.lastUpdated());
            MINI_ENTITIES_DUE.add(key);
            relightPocket(pocket);
            // Charge les chunks du bocal (comme Ars le fait pour son gabarit) avant de lire la lumiere.
            for (int cx = -1; cx <= 2; cx++) {
                for (int cz = -1; cz <= 2; cz++) {
                    pocket.getChunk(cx, cz);
                }
            }
            byte[] light = captureLight(pocket, new BlockPos(-1, 0, -1),
                    MiniLightPayload.SIZE_X, MiniLightPayload.SIZE_Y, MiniLightPayload.SIZE_Z);
            if (Arrays.equals(light, MINI_LIGHT.get(key))) {
                continue;
            }
            MINI_LIGHT.put(key, light);
            PacketDistributor.sendToAllPlayers(new MiniLightPayload(key.location(), light));
        }
    }

    /**
     * Porte-armures, tableaux et cadres de chaque bocal, pour la miniature (Ars ne dessine que les blocs). Comme pour le
     * decor geant : relus quand le gabarit du bocal change (blocs), et au rafraichissement de secours (option
     * "Backup refresh"). Envoyes seulement s'ils ont change ; un bocal decharge garde la derniere liste envoyee.
     */
    private static void updateMiniEntities(MinecraftServer server) {
        long now = server.getTickCount();
        int seconds = Config.REFRESH_SECONDS.get();
        long refresh = seconds > 0 ? Math.max(60, seconds) * 20L : 0L;
        for (ResourceKey<Level> key : PlanariumTile.dimManager.entries.keySet()) {
            boolean due = !MINI_ENTITIES.containsKey(key) || MINI_ENTITIES_DUE.contains(key)
                    || (refresh > 0 && now >= MINI_ENTITIES_NEXT.getOrDefault(key, 0L));
            if (!due) {
                continue;
            }
            ServerLevel pocket = server.getLevel(key);
            if (pocket == null) {
                continue;
            }
            boolean loaded = true;
            for (int cx = 0; cx <= 1 && loaded; cx++) {
                for (int cz = 0; cz <= 1; cz++) {
                    if (!pocket.areEntitiesLoaded(ChunkPos.asLong(cx, cz))) {
                        loaded = false;
                        break;
                    }
                }
            }
            if (!loaded) {
                continue;
            }
            MINI_ENTITIES_DUE.remove(key);
            MINI_ENTITIES_NEXT.put(key, now + refresh);
            net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
            tag.put("entities", GiantEntities.list(pocket, new net.minecraft.world.phys.AABB(0, 0, 0, 32, 34, 32)));
            net.minecraft.nbt.CompoundTag previous = MINI_ENTITIES.put(key, tag);
            if (tag.equals(previous) || (previous == null && tag.getList("entities", 10).isEmpty())) {
                continue;
            }
            MiniEntitiesPayload payload = new MiniEntitiesPayload(key.location(), tag);
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (player.connection.hasChannel(MiniEntitiesPayload.TYPE)) {
                    PacketDistributor.sendToPlayer(player, payload);
                }
            }
        }
    }

    /** Anneau lointain (voir FarRingServer) : avance chaque tick, avec un petit budget. */
    private static void updateFarRings(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Track track = TRACKS.get(player.getUUID());
            if (track == null || DISABLED.contains(player.getUUID()) || !track.pocket.equals(player.serverLevel().dimension())) continue;
            ServerLevel source = server.getLevel(track.source.dimension());
            if (source == null) continue;
            FarRingServer.tick(player, source, track.pocket, track.capturedCenter, track.lastRadius, track.hasFull);
        }
    }

    private static void updateSubLevels(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Track track = TRACKS.get(player.getUUID());
            if (track == null || DISABLED.contains(player.getUUID()) || !track.pocket.equals(player.serverLevel().dimension())) continue;
            ServerLevel source = server.getLevel(track.source.dimension());
            if (source == null || !updateAnchor(track, source)) continue;
            int radius = Config.RADIUS.get();
            keepLoaded(server, player.getUUID(), track, radius);
            // Avec l'anneau lointain, les structures qui volent plus loin restent visibles (blocs et mouvement).
            int far = Config.FAR_RADIUS.get();
            net.minecraft.world.phys.AABB area = far > radius
                    ? new net.minecraft.world.phys.AABB(track.center).inflate(far + 1, far + 1, far + 1)
                    : new net.minecraft.world.phys.AABB(track.center).inflate(radius + 1);
            SubLevelServer.send(player, track.pocket.location(), source, area, track.anchor, false,
                    track.source.pos(), prefsOf(player.getUUID()).world());
        }
        SubLevelServer.updateMiniatures(server);
    }

    static boolean enabled(UUID player) {
        return !DISABLED.contains(player);
    }

    private static Vec3 relative(Track track, Vec3 world) {
        org.joml.Vector3d relative = new org.joml.Matrix4d(track.anchor).invert()
                .transformPosition(new org.joml.Vector3d(world.x, world.y, world.z));
        return new Vec3(relative.x, relative.y, relative.z);
    }

    private static boolean updateAnchor(Track track, ServerLevel source) {
        if (!(source.getBlockEntity(track.source.pos()) instanceof PlanariumTile tile) || !track.pocket.equals(tile.key)) {
            // Planarium casse ou introuvable : au plus une recherche par seconde (sinon 17^3 blocs toutes les 2 ticks).
            long tick = source.getServer().getTickCount();
            if (tick < track.nextSearch) return false;
            track.nextSearch = tick + 20;
            BlockPos found = com.matthieu.planariumview.compat.SubLevelCompat.findPlanarium(source, track.pocket,
                    new Vec3(track.anchor.m30(), track.anchor.m31(), track.anchor.m32()), SEARCH_RADIUS);
            if (found == null) {
                for (BlockPos at : BlockPos.betweenClosed(track.center.offset(-SEARCH_RADIUS, -SEARCH_RADIUS, -SEARCH_RADIUS),
                        track.center.offset(SEARCH_RADIUS, SEARCH_RADIUS, SEARCH_RADIUS))) {
                    if (source.hasChunkAt(at) && source.getBlockEntity(at) instanceof PlanariumTile candidate
                            && track.pocket.equals(candidate.key)) {
                        found = at.immutable();
                        break;
                    }
                }
            }
            if (found == null) return false;
            track.source = GlobalPos.of(source.dimension(), found);
        }
        org.joml.Matrix4d anchor = com.matthieu.planariumview.compat.SubLevelCompat.anchor(source, track.source.pos());
        BlockPos center = BlockPos.containing(anchor.m30(), anchor.m31(), anchor.m32());
        // Pas de capture complete a chaque bloc parcouru : le client recale l'ancienne photo autour du bocal
        // (SubLevelView.terrain), et update() refait une capture quand le centre s'est assez eloigne de celui capture.
        track.anchor = anchor;
        track.center = center;
        return true;
    }

    @SubscribeEvent
    public static void onServerStarted(net.neoforged.neoforge.event.server.ServerStartedEvent event) {
        for (ServerLevel level : event.getServer().getAllLevels()) {
            com.matthieu.planariumview.compat.SubLevelCompat.clearVisitorTickets(level);
        }
    }

    @SubscribeEvent
    public static void onServerStopping(net.neoforged.neoforge.event.server.ServerStoppingEvent event) {
        SubLevelServer.clear();
    }

    @SubscribeEvent
    public static void onServerStopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event) {
        SubLevelServer.clear();
        TRACKS.clear();
    }

    /** Ticket de chargement : garde la zone autour du Planarium chargee tant qu'un joueur est dans le bocal. */
    private static final TicketType<UUID> VIEW_TICKET =
            TicketType.create("planariumview", UUID::compareTo);

    /**
     * Sans joueur a proximite, les chunks autour du Planarium se dechargent et les rafraichissements
     * ne peuvent plus lire les blocs. On les garde charges (sans les faire "tourner") pendant la visite.
     */
    /**
     * Garde la zone du decor chargee. Option "Sons du monde exterieur" ON : un seul ticket qui fait aussi tourner la zone
     * (redstone, machines, mobs), comme si le joueur y etait. OFF : un ticket par chunk, au niveau juste suffisant pour que
     * le chunk soit charge et lisible, sans rien y faire tourner (comme quand un joueur s'en va).
     */
    private static void keepLoaded(MinecraftServer server, UUID player, Track track, int radius) {
        int chunks = Math.floorDiv(radius + 15, 16) + 1;
        boolean active = prefsOf(player).world();
        ChunkPos center = new ChunkPos(track.center);
        if (chunks == track.ticketChunks && active == track.ticketActive && center.equals(track.ticketCenter)) {
            return;
        }
        ServerLevel source = server.getLevel(track.source.dimension());
        if (source == null) {
            return;
        }
        releaseTickets(source, track);
        if (active) {
            source.getChunkSource().addRegionTicket(VIEW_TICKET, center, chunks, track.owner);
        } else {
            for (int dx = -chunks; dx <= chunks; dx++) {
                for (int dz = -chunks; dz <= chunks; dz++) {
                    ChunkPos chunk = new ChunkPos(center.x + dx, center.z + dz);
                    source.getChunkSource().addRegionTicket(VIEW_TICKET, chunk, 0, track.owner);
                }
            }
        }
        track.ticketChunks = chunks;
        track.ticketActive = active;
        track.ticketCenter = center;
    }

    /** Retire les tickets de chargement de ce suivi, selon le mode avec lequel ils ont ete poses. */
    private static void releaseTickets(ServerLevel source, Track track) {
        if (track.ticketChunks < 0) {
            return;
        }
        ChunkPos center = track.ticketCenter;
        if (track.ticketActive) {
            source.getChunkSource().removeRegionTicket(VIEW_TICKET, center, track.ticketChunks, track.owner);
        } else {
            for (int dx = -track.ticketChunks; dx <= track.ticketChunks; dx++) {
                for (int dz = -track.ticketChunks; dz <= track.ticketChunks; dz++) {
                    ChunkPos chunk = new ChunkPos(center.x + dx, center.z + dz);
                    source.getChunkSource().removeRegionTicket(VIEW_TICKET, chunk, 0, track.owner);
                }
            }
        }
        track.ticketChunks = -1;
    }

    private static void removeTrack(MinecraftServer server, UUID id) {
        SubLevelServer.forgetOutside(id);
        LunarServer.forget(id);
        FarRingServer.forget(server, id);
        Track track = TRACKS.remove(id);
        if (track == null || track.ticketChunks < 0) {
            return;
        }
        ServerLevel source = server.getLevel(track.source.dimension());
        if (source != null) {
            releaseTickets(source, track);
        }
        track.ticketChunks = -1;
    }

    /**
     * Les parois du bocal emettent la lumiere des blocs qui entourent le Planarium, face par face.
     * On ne touche aux parois que quand cette lumiere change.
     */
    private static void updateWalls(ServerPlayer player, Track track) {
        ServerLevel source = player.server.getLevel(track.source.dimension());
        ServerLevel pocket = player.serverLevel();
        BlockPos pos = track.source.pos();
        if (source == null || !source.hasChunkAt(pos)) {
            return;
        }
        // Les parois n'emettent plus de lumiere : la lumiere et la couleur de l'interieur sont calculees chez le client
        // pour tout le bocal (JarLight, JarColor). On remet a 0 celles des bocaux d'anciennes versions, une fois.
        if (!track.wallsCleared) {
            int[] result = JarWalls.apply(pocket, new int[JarWalls.FACES]);
            if (result[0] == 0) {
                // Les chunks du bocal ne sont pas encore charges (on vient d'entrer) : on reessaie a la prochaine seconde.
                return;
            }
            track.wallsCleared = true;
            if (result[2] > 0) {
                LOGGER.info("[Planarium Panorama] Parois du bocal remises sans lumiere ({} modifiees)", result[2]);
            }
        }
        int[] light = JarWalls.measure(source, pos);
        if (!pos.equals(track.center)) {
            BlockPos[] samples = JarWalls.samplePositions(pos);
            org.joml.Matrix4d transform = new org.joml.Matrix4d(track.anchor)
                    .translate(-pos.getX(), -pos.getY(), -pos.getZ());
            for (int i = 0; i < samples.length; i++) {
                org.joml.Vector3d point = transform.transformPosition(new org.joml.Vector3d(
                        samples[i].getX() + 0.5, samples[i].getY() + 0.5, samples[i].getZ() + 0.5));
                light[i] = Math.max(light[i], source.getBrightness(LightLayer.BLOCK, BlockPos.containing(point.x, point.y, point.z)));
            }
        }
        if (!Arrays.equals(light, track.lastWallLight)) {
            LOGGER.info("[Planarium Panorama] Lumiere autour du Planarium : {}", Arrays.toString(light));
            track.lastWallLight = light;
        }
        // Recalcule aussi les sources (une torche remplacee par une torche des ames a la meme lumiere change la couleur).
        track.lastWallSources = JarWalls.findSources(source, pos);
        // Envoye chaque seconde, meme sans changement : le message envoye a l'entree peut se perdre pendant que le client
        // change de dimension. Le client ne recalcule rien si les valeurs n'ont pas change.
        PacketDistributor.sendToPlayer(player, new WallPayload(track.pocket.location(), light, track.lastWallSources));
    }

    /**
     * Ambiance a l'endroit du Planarium, calculee comme Dynamic Surroundings le ferait
     * pour un joueur debout a la place du Planarium : son biome, s'il est abrite, s'il est sous terre.
     */
    private static void sendAmbience(ServerPlayer player, Track track) {
        ServerLevel source = player.server.getLevel(track.source.dimension());
        if (source == null) {
            return;
        }
        BlockPos pos = track.center;
        if (!source.hasChunkAt(pos)) {
            return;
        }
        ResourceLocation biome = source.getBiome(pos).unwrapKey()
                .map(net.minecraft.resources.ResourceKey::location)
                .orElse(ResourceLocation.withDefaultNamespace("plains"));
        boolean underground = pos.getY() < source.getSeaLevel() - 8;
        PacketDistributor.sendToPlayer(player,
                new AmbiencePayload(track.pocket.location(), biome, isSheltered(source, pos), underground,
                        source.dimension().location(), pos.getY(), source.getSeaLevel(), source.getHeight()));
    }

    /**
     * Meme methode que le "CeilingScanner" de Dynamic Surroundings : on regarde 7 x 7 colonnes autour
     * de la position ; une colonne est couverte s'il y a, au-dessus de la tete, un bloc qui bloque le
     * mouvement (sauf feuilles, cloture, portillon et muret). Les colonnes proches comptent plus.
     */
    private static boolean isSheltered(ServerLevel level, BlockPos pos) {
        final int range = 3;
        float total = 0.0F;
        float score = 0.0F;
        int headY = Math.max(pos.getY() + 1, level.getMinBuildHeight());
        BlockPos.MutableBlockPos working = new BlockPos.MutableBlockPos();
        for (int dx = -range; dx <= range; dx++) {
            for (int dz = -range; dz <= range; dz++) {
                float side = Math.min(range - Math.abs(dx) + 1, range - Math.abs(dz) + 1);
                float points = side * side;
                total += points;
                int x = pos.getX() + dx;
                int z = pos.getZ() + dz;
                int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z);
                boolean covered = false;
                for (int y = top; y > headY; y--) {
                    working.set(x, y, z);
                    net.minecraft.world.level.block.state.BlockState state = level.getBlockState(working);
                    if (state.blocksMotion()
                            && !state.is(net.minecraft.tags.BlockTags.LEAVES)
                            && !state.is(net.minecraft.tags.BlockTags.FENCE_GATES)
                            && !state.is(net.minecraft.tags.BlockTags.FENCES)
                            && !state.is(net.minecraft.tags.BlockTags.WALLS)) {
                        covered = true;
                        break;
                    }
                }
                if (!covered) {
                    score += points;
                }
            }
        }
        float coverage = 1.0F - score / total;
        return coverage > 1.0F - 65.0F / 176.0F;
    }
}

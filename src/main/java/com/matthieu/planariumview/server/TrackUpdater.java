package com.matthieu.planariumview.server;

import com.matthieu.planariumview.compat.serene_seasons.SeasonCompat;
import com.matthieu.planariumview.config.Config;
import com.matthieu.planariumview.network.OutsideViewDeltaPayload;
import com.matthieu.planariumview.network.OutsideViewPayload;
import com.matthieu.planariumview.network.SkyPayload;
import com.matthieu.planariumview.server.SceneryCapture.Snapshot;
import com.matthieu.planariumview.server.ServerView.Track;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.matthieu.planariumview.server.ServerView.isPocket;
import static com.matthieu.planariumview.server.ServerView.logOnce;
import static com.matthieu.planariumview.server.ServerView.removeTrack;

/** Mise a jour du suivi d'un joueur : ciel, captures completes et changements de blocs. */
final class TrackUpdater {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(ServerView.class);

    private TrackUpdater() {
    }

    static void update(ServerPlayer player, boolean force, Map<UUID, Track> tracks, Set<UUID> disabled) {
        try {
            doUpdate(player, force, tracks, disabled);
        } catch (Exception e) {
            LOGGER.error("[Planarium View] Erreur lors de la capture des alentours", e);
        }
    }

    private static void doUpdate(ServerPlayer player, boolean force, Map<UUID, Track> tracks, Set<UUID> disabled) {
        ServerLevel level = player.serverLevel();
        UUID id = player.getUUID();
        Track track = tracks.get(id);

        if (!isPocket(level) || disabled.contains(id)) {
            if (track != null) {
                removeTrack(player.server, id);
                PacketDistributor.sendToPlayer(player, OutsideViewPayload.clear(track.pocket.location()));
            }
            return;
        }

        if (track == null || !track.pocket.equals(level.dimension())) {
            GlobalPos source = PlanariumAnchor.findPlanarium(player, level);
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
            ServerLevel sourceLevel = player.server.getLevel(source.dimension());
            track.body = sourceLevel == null ? null
                    : com.matthieu.planariumview.compat.sable.SubLevelCompat.ownerId(sourceLevel, source.pos());
            LOGGER.debug("[Planarium Panorama] Planarium trouve en {} (vaisseau {})", source.pos(), track.body);
            tracks.put(id, track);
            force = true;
        }
        ServerLevel outside = player.server.getLevel(track.source.dimension());
        if (outside != null && !PlanariumAnchor.updateAnchor(track, outside)) return;
        // On attend que les chunks du bocal soient charges : sinon le recalcul serait saute.
        if (!track.relit) {
            PocketLighting.removeLightsBelowFloor(level);
            track.relit = PocketLighting.relightPocket(level);
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
            OutsideSounds.sendAmbience(player, track);
        } catch (Exception e) {
            logOnce("ambiance", e);
        }
        try {
            // Les parois seulement apres le recalcul de la lumiere du ciel, qui doit partir de donnees a jour.
            if (track.relit) {
                PocketLighting.updateWalls(player, track);
                if (player.server.getTickCount() % 20 == 0) {
                    PocketLighting.logSkyDiag(level, track);
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
        ViewTickets.keepLoaded(player.server, player.getUUID(), track, radius);
        if (track.hasFull) {
            OutsideSounds.trySendPendingJukeboxes(player, track, radius, disabled);
        }

        long now = player.server.getTickCount();
        // En vol, le rayon detaille arrive par sections (FlightServer) : pas de photo complete a chaque deplacement.
        // A l'arret, une photo normale a la position actuelle termine le vol.
        if (FlightServer.active(player.getUUID())) {
            if (!FlightServer.wantsNear(player.getUUID(), now) || track.center.equals(track.capturedCenter)) {
                return;
            }
            force = true;
        }
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
        Snapshot snap = SceneryCapture.capture(player.server, track, radius, force);
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
        LOGGER.debug("[Planarium Panorama] Envoi du decor complet (cause : {}, capture={} ms, comparaison={} ms)",
                reason, (compareStart - captureStart) / 1_000_000L, compareNanos / 1_000_000L);
        // Description detaillee des changements : elle parcourt aussi les blocs (10 a 50 ms), donc seulement en DEBUG.
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("[Planarium Panorama] Changements du decor : {}", SceneryCapture.describeSnapshotChanges(track, snap));
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
            OutsideSounds.sendPlayingJukeboxes(player, track);
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
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("[Planarium Panorama] Envoi des blocs changes : {} blocs, {} ms", list.size(),
                    String.format(java.util.Locale.ROOT, "%.1f", (System.nanoTime() - start) / 1e6));
        }
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
                hasSky ? Math.min(PocketLighting.outsideLight(source, track.source.pos(), LightLayer.SKY), PocketLighting.outsideLight(source, track.center, LightLayer.SKY)) : 0,
                Math.max(PocketLighting.outsideLight(source, track.source.pos(), LightLayer.BLOCK), PocketLighting.outsideLight(source, track.center, LightLayer.BLOCK)),
                // Le ciel de la dimension d'origine (celui de l'Aether, par exemple).
                source.dimensionType().effectsLocation(),
                // Le type de dimension d'origine : sa facon de compter les jours (plus longs dans l'Aether).
                source.dimensionTypeRegistration().unwrapKey().map(net.minecraft.resources.ResourceKey::location)
                        .orElse(ResourceLocation.withDefaultNamespace("overworld")),
                hasSky,
                PlanariumAnchor.planariumName(source, track.source.pos())));
    }
}

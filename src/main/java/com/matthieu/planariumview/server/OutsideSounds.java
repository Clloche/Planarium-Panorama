package com.matthieu.planariumview.server;

import com.matthieu.planariumview.network.AmbiencePayload;
import com.matthieu.planariumview.network.OutsideRecordPayload;
import com.matthieu.planariumview.network.OutsideSoundPayload;
import com.matthieu.planariumview.network.SoundPrefsPayload;
import com.matthieu.planariumview.server.ServerView.Track;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.PlayLevelSoundEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Sons, disques et ambiance du monde exterieur pour les joueurs dans un bocal. */
final class OutsideSounds {
    /** Limites separees : une ferme de mobs ne peut pas empecher d'entendre une porte ou un repeteur. */
    private static final int MAX_SOUNDS_PER_SECOND = 30;
    private static final int MAX_ENTITY_SOUNDS_PER_SECOND = 10;
    /** Options des sons du monde exterieur de chaque joueur (SoundPrefsPayload ; sans message : tout permis). */
    private static final Map<UUID, SoundPrefsPayload> SOUND_PREFS = new java.util.concurrent.ConcurrentHashMap<>();

    private OutsideSounds() {
    }

    static SoundPrefsPayload prefsOf(UUID id) {
        return SOUND_PREFS.getOrDefault(id, SoundPrefsPayload.DEFAULT);
    }

    static SoundPrefsPayload setPrefs(UUID id, SoundPrefsPayload prefs) {
        return SOUND_PREFS.put(id, prefs);
    }

    static void logout(UUID id) {
        SOUND_PREFS.remove(id);
    }

    /** Sons du monde d'origine : les sons directement causes par un joueur sont filtres par SoundCauseMixin. */
    static void onPlayLevelSound(PlayLevelSoundEvent event, Map<UUID, Track> tracks, Set<UUID> disabled) {
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
        sendOutsideSound(level, position, sound, source, event.getNewVolume(), event.getNewPitch(), category, tracks, disabled);
    }

    /**
     * Sons joues par les "evenements de niveau" (ServerLevel.levelEvent, voir LevelEventSoundMixin) : ils ne passent pas
     * par PlayLevelSoundEvent. Seulement les plus utiles ; ceux causes par un joueur sont exclus, comme les autres sons.
     */
    static void onLevelEvent(ServerLevel level, Player player, int type, BlockPos pos, int data,
                             Map<UUID, Track> tracks, Set<UUID> disabled) {
        if (tracks.isEmpty() || !level.getServer().isSameThread()) {
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
            sendOutsideRecord(level, pos, song, tracks, disabled);
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
                SoundSource.BLOCKS, volume, pitch, OutsideSoundPayload.BLOCKS, tracks, disabled);
    }

    /** Debut ("" = fin) d'un morceau de jukebox, pour les joueurs dont le Planarium est a portee (64 blocs). */
    private static void sendOutsideRecord(ServerLevel level, BlockPos pos, String song,
                                          Map<UUID, Track> tracks, Set<UUID> disabled) {
        Vec3 position = com.matthieu.planariumview.compat.sable.SubLevelCompat.worldPosition(level, Vec3.atCenterOf(pos));
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            Track track = tracks.get(player.getUUID());
            if (track == null || disabled.contains(player.getUUID())
                    || !track.source.dimension().equals(level.dimension())
                    || !player.serverLevel().dimension().equals(track.pocket)
                    || !player.connection.hasChannel(OutsideRecordPayload.TYPE)) {
                continue;
            }
            Vec3 relative = PlanariumAnchor.relative(track, position);
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
    static void sendPlayingJukeboxes(ServerPlayer player, Track track) {
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
                    Vec3 position = com.matthieu.planariumview.compat.sable.SubLevelCompat.worldPosition(level, Vec3.atCenterOf(jukebox.getBlockPos()));
                    Vec3 relative = PlanariumAnchor.relative(track, position);
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
    static void trySendPendingJukeboxes(ServerPlayer player, Track track, int radius, Set<UUID> disabled) {
        if (!track.jukeboxSyncPending) {
            return;
        }
        UUID id = player.getUUID();
        if (disabled.contains(id) || !prefsOf(id).world()
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
    static void onExplosion(ExplosionEvent.Detonate event, Map<UUID, Track> tracks, Set<UUID> disabled) {
        if (!(event.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread()) {
            return;
        }
        // Meme son et meme hauteur que Minecraft (le son d'une charge de vent n'est pas celui d'une TNT).
        float pitch = (1.0F + (level.random.nextFloat() - level.random.nextFloat()) * 0.2F) * 0.7F;
        sendOutsideSound(level, event.getExplosion().center(), event.getExplosion().getExplosionSound(),
                SoundSource.BLOCKS, 4.0F, pitch, OutsideSoundPayload.EXPLOSIONS, tracks, disabled);
    }

    private static void sendOutsideSound(ServerLevel level, Vec3 position, Holder<SoundEvent> sound,
                                         SoundSource source, float volume, float pitch, int category,
                                         Map<UUID, Track> tracks, Set<UUID> disabled) {
        if (tracks.isEmpty() || volume <= 0.0F) {
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
        position = com.matthieu.planariumview.compat.sable.SubLevelCompat.worldPosition(level, position);
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            Track track = tracks.get(player.getUUID());
            if (track == null || disabled.contains(player.getUUID())
                    || !track.source.dimension().equals(level.dimension())
                    || !player.serverLevel().dimension().equals(track.pocket)
                    || !player.connection.hasChannel(OutsideSoundPayload.TYPE)
                    || !prefsOf(player.getUUID()).allows(category)) {
                continue;
            }
            Vec3 relative = PlanariumAnchor.relative(track, position);
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

    /**
     * Ambiance a l'endroit du Planarium, calculee comme Dynamic Surroundings le ferait
     * pour un joueur debout a la place du Planarium : son biome, s'il est abrite, s'il est sous terre.
     */
    static void sendAmbience(ServerPlayer player, Track track) {
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

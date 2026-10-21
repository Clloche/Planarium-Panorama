package com.matthieu.planariumview.server;

import com.matthieu.planariumview.config.Config;
import com.matthieu.planariumview.network.FarRingPayload;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Anneau lointain : au-dela du rayon de vue, une photo figee des blocs jusqu'a {@code farRadius}.
 *
 * Le rayon proche garde sa capture et ses mises a jour (ServerView). L'anneau est capture une fois en entrant, puis
 * seulement avec le rafraichissement de secours, ou quand un Planarium sur un sous-niveau (Sable) s'est arrete
 * quelques secondes. La capture se fait chunk par chunk avec un petit budget par tick : l'entree dans le bocal n'attend
 * pas l'anneau. Seuls les blocs visibles sont envoyes (ceux qui touchent une case eclairee qui laisse passer la vue),
 * avec la lumiere des cases autour : pas l'interieur du sol ni les cavernes noires.
 */
public final class FarRingServer {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Les tickets expirent seuls (30 s) si une capture est abandonnee sans les retirer. */
    private static final TicketType<UUID> TICKET = TicketType.create("planariumview_far", UUID::compareTo, 600);
    private static final long BUDGET_NANOS = 4_000_000L;
    /** Un Planarium qui bouge doit rester immobile ce temps avant la capture de l'anneau. */
    private static final int STILL_TICKS = 100;
    /** Temps max d'attente des chunks d'une capture (ceux qui manquent encore sont laisses vides). */
    private static final int LOAD_WAIT_TICKS = 400;
    private static final Direction[] DIRECTIONS = Direction.values();

    private record Key(ResourceKey<Level> source, BlockPos center, int near, int far, int height) {
    }

    /** Photos deja prises, reutilisees par une nouvelle entree (ou un autre joueur) tant qu'elles sont recentes. */
    private record Cached(FarRingPayload.Data data, long takenAt) {
    }

    private static final Map<Key, Cached> CACHE = new HashMap<>();

    private static final class State {
        Key sent;
        ResourceKey<Level> pocket;
        Job job;
        BlockPos lastCenter;
        long movedAt = Long.MIN_VALUE / 2;
        long nextRefresh;
    }

    private static final Map<UUID, State> STATES = new HashMap<>();

    private FarRingServer() {
    }

    private static int refreshTicks() {
        int seconds = Config.REFRESH_SECONDS.get();
        return seconds > 0 ? Math.max(seconds, 60) * 20 : 0;
    }

    /**
     * Chaque tick, pour un joueur suivi dans un bocal. {@code near} : rayon de la derniere photo du rayon proche ;
     * {@code ready} : le client a deja recu cette photo.
     */
    static void tick(ServerPlayer player, ServerLevel source, ResourceKey<Level> pocket, BlockPos center, int near, boolean ready) {
        UUID id = player.getUUID();
        State state = STATES.computeIfAbsent(id, ignored -> new State());
        long now = player.server.getTickCount();
        if (state.pocket != null && !state.pocket.equals(pocket)) {
            forget(player.server, id);
            state = STATES.computeIfAbsent(id, ignored -> new State());
        }
        state.pocket = pocket;
        if (!center.equals(state.lastCenter)) {
            if (state.lastCenter != null) {
                state.movedAt = now;
            }
            state.lastCenter = center;
            cancel(player.server, state);
        }
        int far = Config.FAR_RADIUS.get();
        if (far <= near || !ready || !player.connection.hasChannel(FarRingPayload.TYPE)) {
            cancel(player.server, state);
            if (state.sent != null) {
                state.sent = null;
                PacketDistributor.sendToPlayer(player, FarRingPayload.clear(pocket.location()));
            }
            return;
        }
        Key want = new Key(source.dimension(), center, near, far, Math.max(near, far));
        if (state.job != null) {
            if (!state.job.key.equals(want)) {
                cancel(player.server, state);
            } else {
                if (state.job.step(source, now)) {
                    Job done = state.job;
                    state.job = null;
                    FarRingPayload.Data data = done.result();
                    CACHE.put(want, new Cached(data, now));
                    send(player, pocket, want, data);
                    state.sent = want;
                    int refresh = refreshTicks();
                    state.nextRefresh = refresh > 0 ? now + refresh : Long.MAX_VALUE;
                    LOGGER.info("[Planarium Panorama] Anneau lointain envoye : rayon {}, {} blocs visibles, {} ms de capture en {} ticks",
                            far, done.blocks.size(), done.workNanos / 1_000_000L, now - done.startedAt);
                }
                return;
            }
        }
        if (now - state.movedAt < STILL_TICKS) {
            return;
        }
        boolean refreshDue = now >= state.nextRefresh;
        if (want.equals(state.sent) && !refreshDue) {
            return;
        }
        int refresh = refreshTicks();
        Cached cached = CACHE.get(want);
        if (cached != null && (refresh == 0 || now - cached.takenAt < refresh) && !(want.equals(state.sent) && refreshDue)) {
            // Photo recente de la meme zone (nouvelle entree dans le bocal, ou un autre joueur) : tout de suite.
            send(player, pocket, want, cached.data);
            state.sent = want;
            state.nextRefresh = refresh > 0 ? cached.takenAt + refresh : Long.MAX_VALUE;
            return;
        }
        state.job = new Job(want, id, now);
        pruneCache(now);
    }

    private static void send(ServerPlayer player, ResourceKey<Level> pocket, Key key, FarRingPayload.Data data) {
        PacketDistributor.sendToPlayer(player,
                new FarRingPayload(pocket.location(), key.center, key.near, key.far, key.height, data));
    }

    private static void pruneCache(long now) {
        int refresh = refreshTicks();
        long keep = refresh > 0 ? refresh : 20L * 60 * 30;
        CACHE.values().removeIf(cached -> now - cached.takenAt > keep);
        // Au plus quelques photos en memoire (environ 1 Mo chacune).
        while (CACHE.size() > 16) {
            Iterator<Map.Entry<Key, Cached>> oldest = CACHE.entrySet().iterator();
            Map.Entry<Key, Cached> worst = oldest.next();
            while (oldest.hasNext()) {
                Map.Entry<Key, Cached> next = oldest.next();
                if (next.getValue().takenAt < worst.getValue().takenAt) worst = next;
            }
            CACHE.remove(worst.getKey());
        }
    }

    /** Le joueur n'est plus suivi (sorti du bocal, deconnecte, vue OFF). */
    static void forget(net.minecraft.server.MinecraftServer server, UUID id) {
        State state = STATES.remove(id);
        if (state != null) {
            cancel(server, state);
        }
    }

    /**
     * Un bloc a change dans le monde : les photos gardees qui le contiennent ne sont plus reutilisees. Le joueur deja
     * dans le bocal garde son anneau fige ; la prochaine entree prend une nouvelle photo.
     */
    static void blockChanged(ResourceKey<Level> source, BlockPos pos) {
        if (CACHE.isEmpty()) {
            return;
        }
        CACHE.keySet().removeIf(key -> key.source.equals(source)
                && Math.abs(pos.getX() - key.center.getX()) <= key.far
                && Math.abs(pos.getZ() - key.center.getZ()) <= key.far
                && Math.abs(pos.getY() - key.center.getY()) <= key.height + 1);
    }

    private static void cancel(net.minecraft.server.MinecraftServer server, State state) {
        if (state.job != null) {
            state.job.release(server);
            state.job = null;
        }
    }

    /** Capture d'un anneau, faite morceau par morceau. */
    private static final class Job {
        final Key key;
        final UUID owner;
        final long startedAt;
        final List<ChunkPos> chunks = new ArrayList<>();
        int index;
        boolean ticketsAdded;
        long workNanos;
        final int minX, minY, minZ, maxX, maxY, maxZ;
        final Map<BlockState, Integer> paletteIndex = new HashMap<>();
        final IntArrayList palette = new IntArrayList();
        final IntArrayList blocks = new IntArrayList();
        final IntArrayList info = new IntArrayList();
        final IntArrayList lightPos = new IntArrayList();
        final ByteArrayList lightValue = new ByteArrayList();
        final LongOpenHashSet lightSeen = new LongOpenHashSet();
        final List<ResourceLocation> biomePalette = new ArrayList<>();
        final Map<ResourceLocation, Integer> biomeIndex = new HashMap<>();
        final int quarts;
        final byte[] biomes;

        Job(Key key, UUID owner, long now) {
            this.key = key;
            this.owner = owner;
            this.startedAt = now;
            BlockPos c = key.center;
            minX = c.getX() - key.far;
            maxX = c.getX() + key.far;
            minZ = c.getZ() - key.far;
            maxZ = c.getZ() + key.far;
            minY = c.getY() - key.height;
            maxY = c.getY() + key.height;
            for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
                for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                    chunks.add(new ChunkPos(cx, cz));
                }
            }
            // Les chunks les plus proches d'abord.
            ChunkPos middle = new ChunkPos(c);
            chunks.sort(java.util.Comparator.comparingInt(chunk -> Math.max(Math.abs(chunk.x - middle.x), Math.abs(chunk.z - middle.z))));
            quarts = ((maxX - minX) >> 2) + 1;
            biomes = new byte[quarts * quarts];
        }

        /** Vrai quand la capture est finie. */
        boolean step(ServerLevel level, long now) {
            if (!ticketsAdded) {
                for (ChunkPos chunk : chunks) {
                    level.getChunkSource().addRegionTicket(TICKET, chunk, 0, owner);
                }
                ticketsAdded = true;
                return false;
            }
            long start = System.nanoTime();
            try {
                while (index < chunks.size()) {
                    ChunkPos pos = chunks.get(index);
                    LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
                    if (chunk == null) {
                        if (now - startedAt < LOAD_WAIT_TICKS) {
                            return false;
                        }
                        index++;
                        continue;
                    }
                    capture(level, chunk);
                    index++;
                    if (System.nanoTime() - start > BUDGET_NANOS) {
                        return index >= chunks.size() && finish(level);
                    }
                }
                return finish(level);
            } finally {
                workNanos += System.nanoTime() - start;
            }
        }

        private boolean finish(ServerLevel level) {
            release(level.getServer());
            return true;
        }

        void release(net.minecraft.server.MinecraftServer server) {
            if (!ticketsAdded) {
                return;
            }
            ServerLevel level = server.getLevel(key.source);
            if (level != null) {
                for (ChunkPos chunk : chunks) {
                    level.getChunkSource().removeRegionTicket(TICKET, chunk, 0, owner);
                }
            }
            ticketsAdded = false;
        }

        /** Cylindre autour du Planarium (comme le brouillard), pas un carre : les coins ne se verraient qu'a moitie. */
        private boolean inBox(int x, int y, int z) {
            if (y < minY || y > maxY) return false;
            long dx = x - key.center.getX(), dz = z - key.center.getZ();
            return dx * dx + dz * dz <= (long) key.far * key.far + key.far;
        }

        private boolean inNear(int x, int y, int z) {
            BlockPos c = key.center;
            return Math.abs(x - c.getX()) <= key.near && Math.abs(y - c.getY()) <= key.near
                    && Math.abs(z - c.getZ()) <= key.near;
        }

        private int local(int x, int y, int z) {
            return FarRingPayload.pack(x - minX, y - minY, z - minZ);
        }

        /** Etat d'une case voisine sans charger de chunk (null : chunk absent). */
        private static BlockState stateAt(ServerLevel level, LevelChunk home, BlockPos pos) {
            if ((pos.getX() >> 4) == home.getPos().x && (pos.getZ() >> 4) == home.getPos().z) {
                return home.getBlockState(pos);
            }
            LevelChunk other = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            return other == null ? null : other.getBlockState(pos);
        }

        private void capture(ServerLevel level, LevelChunk chunk) {
            int x0 = Math.max(minX, chunk.getPos().getMinBlockX());
            int x1 = Math.min(maxX, chunk.getPos().getMaxBlockX());
            int z0 = Math.max(minZ, chunk.getPos().getMinBlockZ());
            int z1 = Math.min(maxZ, chunk.getPos().getMaxBlockZ());
            int y0 = Math.max(minY, level.getMinBuildHeight());
            int y1 = Math.min(maxY, level.getMaxBuildHeight() - 1);
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            BlockPos.MutableBlockPos next = new BlockPos.MutableBlockPos();
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    // Au-dessus du plus haut bloc de la colonne, il n'y a que de l'air.
                    if (!inBox(x, y0, z)) {
                        continue;
                    }
                    int top = Math.min(y1, chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15));
                    for (int y = y0; y <= top; y++) {
                        if (inNear(x, y, z)) {
                            continue;
                        }
                        pos.set(x, y, z);
                        BlockState state = chunk.getBlockState(pos);
                        if (state.isAir()) {
                            continue;
                        }
                        int solidMask = 0;
                        int waterMask = 0;
                        boolean visible = false;
                        for (Direction direction : DIRECTIONS) {
                            next.setWithOffset(pos, direction);
                            if (!inBox(next.getX(), next.getY(), next.getZ())) {
                                if (direction == Direction.UP) {
                                    visible = true;
                                } else {
                                    solidMask |= 1 << direction.get3DDataValue();
                                }
                                continue;
                            }
                            BlockState neighbor = stateAt(level, chunk, next);
                            if (neighbor == null || neighbor.isSolidRender(level, next)) {
                                solidMask |= 1 << direction.get3DDataValue();
                                continue;
                            }
                            // Eau voisine : le client la remet la ou elle n'est pas envoyee (sous la surface, autour des
                            // algues et des blocs waterlogged), sinon leur eau aurait des faces vers de l'air.
                            if (neighbor.getBlock() instanceof LiquidBlock && neighbor.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) {
                                waterMask |= 1 << direction.get3DDataValue();
                            }
                            // L'eau sous la surface de l'eau ne se voit pas.
                            if (state.getBlock() instanceof LiquidBlock && neighbor.getFluidState().getType().isSame(state.getFluidState().getType())) {
                                continue;
                            }
                            // Une case sans aucune lumiere (caverne noire) : ce qui l'entoure ne se voit pas du bocal.
                            if (level.getBrightness(LightLayer.SKY, next) == 0 && level.getBrightness(LightLayer.BLOCK, next) == 0) {
                                continue;
                            }
                            visible = true;
                        }
                        if (!visible) {
                            continue;
                        }
                        Integer paletteId = paletteIndex.get(state);
                        if (paletteId == null) {
                            if (palette.size() >= 65535) {
                                continue;
                            }
                            paletteId = palette.size();
                            palette.add(net.minecraft.world.level.block.Block.getId(state));
                            paletteIndex.put(state, paletteId);
                        }
                        blocks.add(local(x, y, z));
                        info.add(paletteId | solidMask << 16 | waterMask << 22);
                        // Lumiere de la case du bloc (blocs pas pleins : escaliers, dalles, algues...).
                        if (lightSeen.add(BlockPos.asLong(x, y, z))) {
                            lightPos.add(local(x, y, z));
                            lightValue.add((byte) (level.getBrightness(LightLayer.SKY, pos) << 4
                                    | level.getBrightness(LightLayer.BLOCK, pos)));
                        }
                        // Lumiere des cases autour qui laissent passer la vue (faces et ombrage des coins).
                        for (int dx = -1; dx <= 1; dx++) {
                            for (int dy = -1; dy <= 1; dy++) {
                                for (int dz = -1; dz <= 1; dz++) {
                                    if (dx == 0 && dy == 0 && dz == 0) continue;
                                    int nx = x + dx, ny = y + dy, nz = z + dz;
                                    if (!inBox(nx, ny, nz)) continue;
                                    long key = BlockPos.asLong(nx, ny, nz);
                                    if (!lightSeen.add(key)) continue;
                                    next.set(nx, ny, nz);
                                    BlockState around = stateAt(level, chunk, next);
                                    if (around == null || around.isSolidRender(level, next)) continue;
                                    lightPos.add(local(nx, ny, nz));
                                    lightValue.add((byte) (level.getBrightness(LightLayer.SKY, next) << 4
                                            | level.getBrightness(LightLayer.BLOCK, next)));
                                }
                            }
                        }
                    }
                }
            }
            // Biomes de surface, un par colonne de 4 x 4 blocs (couleurs d'herbe, de feuilles et d'eau).
            for (int qx = (x0 - minX) >> 2; qx <= (x1 - minX) >> 2; qx++) {
                for (int qz = (z0 - minZ) >> 2; qz <= (z1 - minZ) >> 2; qz++) {
                    int x = Math.max(x0, Math.min(x1, minX + qx * 4 + 2));
                    int z = Math.max(z0, Math.min(z1, minZ + qz * 4 + 2));
                    int y = Math.max(minY, Math.min(maxY, chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15)));
                    ResourceLocation id = level.getBiome(pos.set(x, y, z)).unwrapKey()
                            .map(ResourceKey::location).orElse(ResourceLocation.withDefaultNamespace("plains"));
                    Integer index = biomeIndex.get(id);
                    if (index == null) {
                        index = biomePalette.size() < 255 ? biomePalette.size() : 0;
                        if (index == biomePalette.size()) {
                            biomePalette.add(id);
                            biomeIndex.put(id, index);
                        }
                    }
                    biomes[qx * quarts + qz] = (byte) (int) index;
                }
            }
        }

        FarRingPayload.Data result() {
            return new FarRingPayload.Data(palette.toIntArray(), blocks.toIntArray(), info.toIntArray(),
                    lightPos.toIntArray(), lightValue.toByteArray(), List.copyOf(biomePalette), biomes, quarts);
        }
    }
}

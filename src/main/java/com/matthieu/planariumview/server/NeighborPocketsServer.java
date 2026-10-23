package com.matthieu.planariumview.server;

import com.hollingsworth.arsnouveau.common.block.tile.PlanariumTile;
import com.matthieu.planariumview.DetailShape;
import com.matthieu.planariumview.network.FarRingPayload;
import com.matthieu.planariumview.network.NeighborPocketsPayload;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Interieur des Planariums voisins : purement visuel. A l'entree dans le bocal, on cherche les autres Planariums dans
 * le cylindre du decor detaille (voir DetailShape), on prend les {@link NeighborPocketsPayload#MAX} plus proches, et on
 * envoie une photo figee de leur poche (blocs visibles et lumiere, murs de verre compris). Le client en dessine autant
 * que son option le demande, a l'echelle 1:1, a la place qu'ils occupent dans le decor geant.
 *
 * Les Planariums sont cherches autour de la vraie position du bloc : sur une structure Sable, ce sont donc ceux de la
 * meme structure (elles ont leur propre zone du monde) ; au sol, ceux du sol.
 */
public final class NeighborPocketsServer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final TicketType<UUID> TICKET = TicketType.create("planariumview_neighbors", UUID::compareTo, 600);
    /** Temps max d'attente des chunks d'une poche (les poches sans chunk charge sont laissees de cote). */
    private static final int LOAD_WAIT_TICKS = 200;
    /** Boite capturee dans chaque poche : les murs de verre (-1 et 32) compris. */
    private static final int MIN = -1, MAX = 32, MIN_Y = 0, MAX_Y = 32;

    private record Key(ResourceKey<Level> source, BlockPos planarium, int radius) {
    }

    private record Neighbor(BlockPos offset, ResourceKey<Level> pocket) {
    }

    private static final class State {
        Key sent;
        boolean sentAny;
        Job job;
    }

    private static final Map<UUID, State> STATES = new HashMap<>();
    /** Photos des poches deja prises, oubliees quand un bloc change dans la poche. */
    private static final Map<ResourceKey<Level>, FarRingPayload.Data> CACHE = new HashMap<>();

    private NeighborPocketsServer() {
    }

    /**
     * Chaque tick, pour un joueur suivi dans un bocal. {@code planarium} : vraie position du bloc du Planarium ;
     * {@code radius} : rayon du decor detaille ; {@code ready} : le client a deja recu le decor.
     */
    static void tick(ServerPlayer player, ServerLevel source, ResourceKey<Level> pocket, BlockPos planarium, int radius,
                     boolean ready) {
        if (!ready || radius <= 0 || !player.connection.hasChannel(NeighborPocketsPayload.TYPE)) {
            return;
        }
        UUID id = player.getUUID();
        State state = STATES.computeIfAbsent(id, ignored -> new State());
        Key want = new Key(source.dimension(), planarium, radius);
        if (state.job != null && !state.job.key.equals(want)) {
            state.job.release(player.server);
            state.job = null;
        }
        if (state.job == null) {
            if (want.equals(state.sent)) {
                return;
            }
            List<Neighbor> neighbors = find(source, planarium, radius);
            if (neighbors.isEmpty()) {
                if (state.sentAny) {
                    PacketDistributor.sendToPlayer(player, new NeighborPocketsPayload(pocket.location(), List.of()));
                }
                state.sent = want;
                state.sentAny = false;
                return;
            }
            state.job = new Job(want, id, neighbors, player.server.getTickCount());
        }
        if (!state.job.step(player.server)) {
            return;
        }
        Job done = state.job;
        state.job = null;
        List<NeighborPocketsPayload.Entry> entries = new ArrayList<>();
        for (int i = 0; i < done.neighbors.size(); i++) {
            FarRingPayload.Data data = done.results.get(i);
            if (data != null) entries.add(new NeighborPocketsPayload.Entry(done.neighbors.get(i).offset(), data));
        }
        PacketDistributor.sendToPlayer(player, new NeighborPocketsPayload(pocket.location(), entries));
        state.sent = want;
        state.sentAny = !entries.isEmpty();
        LOGGER.info("[Planarium Panorama] Planariums voisins envoyes : {} sur {} trouves", entries.size(), done.neighbors.size());
    }

    /**
     * Planariums du cylindre du decor detaille autour de {@code center}, du plus proche au plus loin : distance, puis le
     * meme etage avant le dessus et le dessous, puis une position fixe (jamais de hasard).
     */
    private static List<Neighbor> find(ServerLevel level, BlockPos center, int radius) {
        List<Neighbor> found = new ArrayList<>();
        for (int cx = (center.getX() - radius) >> 4; cx <= (center.getX() + radius) >> 4; cx++) {
            for (int cz = (center.getZ() - radius) >> 4; cz <= (center.getZ() + radius) >> 4; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                for (BlockEntity entity : chunk.getBlockEntities().values()) {
                    if (!(entity instanceof PlanariumTile tile) || tile.key == null) continue;
                    BlockPos offset = tile.getBlockPos().subtract(center);
                    if (offset.equals(BlockPos.ZERO) || Math.abs(offset.getY()) > radius
                            || !DetailShape.horizontal(offset.getX(), offset.getZ(), radius)) continue;
                    found.add(new Neighbor(offset, tile.key));
                }
            }
        }
        found.sort(Comparator.<Neighbor>comparingLong(n -> (long) n.offset().getX() * n.offset().getX()
                        + (long) n.offset().getY() * n.offset().getY() + (long) n.offset().getZ() * n.offset().getZ())
                .thenComparingInt(n -> Math.abs(n.offset().getY()))
                .thenComparingInt(n -> n.offset().getZ())
                .thenComparingInt(n -> n.offset().getX())
                .thenComparingInt(n -> n.offset().getY()));
        return found.size() > NeighborPocketsPayload.MAX ? new ArrayList<>(found.subList(0, NeighborPocketsPayload.MAX)) : found;
    }

    /** Fermeture du monde. */
    static void clearAll() {
        STATES.clear();
        CACHE.clear();
    }

    /** Le joueur n'est plus suivi (sorti du bocal, deconnecte, vue OFF) : la prochaine entree reprend une photo. */
    static void forget(MinecraftServer server, UUID id) {
        State state = STATES.remove(id);
        if (state != null && state.job != null) {
            state.job.release(server);
        }
    }

    /** Un bloc a change : la photo de cette poche (si c'en est une) n'est plus reutilisee. */
    static void blockChanged(ResourceKey<Level> level) {
        if (!CACHE.isEmpty()) CACHE.remove(level);
    }

    /** Captures des poches voisines, une poche a la fois, avec le budget commun (voir CaptureBudget). */
    private static final class Job {
        final Key key;
        final UUID owner;
        final long startedAt;
        final List<Neighbor> neighbors;
        final List<FarRingPayload.Data> results = new ArrayList<>();
        int index;
        boolean ticketsAdded;

        Job(Key key, UUID owner, List<Neighbor> neighbors, long now) {
            this.key = key;
            this.owner = owner;
            this.neighbors = neighbors;
            this.startedAt = now;
        }

        private static List<ChunkPos> chunks() {
            List<ChunkPos> chunks = new ArrayList<>();
            for (int cx = (MIN - 1) >> 4; cx <= (MAX + 1) >> 4; cx++) {
                for (int cz = (MIN - 1) >> 4; cz <= (MAX + 1) >> 4; cz++) {
                    chunks.add(new ChunkPos(cx, cz));
                }
            }
            return chunks;
        }

        /** Vrai quand toutes les poches sont faites. */
        boolean step(MinecraftServer server) {
            if (!ticketsAdded) {
                for (Neighbor neighbor : neighbors) {
                    if (CACHE.containsKey(neighbor.pocket())) continue;
                    ServerLevel level = server.getLevel(neighbor.pocket());
                    if (level == null) continue;
                    for (ChunkPos chunk : chunks()) {
                        level.getChunkSource().addRegionTicket(TICKET, chunk, 0, owner);
                    }
                }
                ticketsAdded = true;
                return false;
            }
            long now = server.getTickCount();
            while (index < neighbors.size()) {
                Neighbor neighbor = neighbors.get(index);
                FarRingPayload.Data cached = CACHE.get(neighbor.pocket());
                if (cached != null) {
                    results.add(cached);
                    index++;
                    continue;
                }
                ServerLevel level = server.getLevel(neighbor.pocket());
                if (level == null) {
                    results.add(null);
                    index++;
                    continue;
                }
                List<LevelChunk> loaded = new ArrayList<>();
                for (ChunkPos pos : chunks()) {
                    LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
                    if (chunk != null) loaded.add(chunk);
                }
                if (loaded.size() < chunks().size() && now - startedAt < LOAD_WAIT_TICKS) {
                    return false;
                }
                if (!CaptureBudget.available()) {
                    return false;
                }
                long start = System.nanoTime();
                FarRingPayload.Data data = capture(level, loaded);
                CaptureBudget.spend(System.nanoTime() - start);
                CACHE.put(neighbor.pocket(), data);
                results.add(data);
                index++;
            }
            release(server);
            return true;
        }

        void release(MinecraftServer server) {
            if (!ticketsAdded) return;
            for (Neighbor neighbor : neighbors) {
                ServerLevel level = server.getLevel(neighbor.pocket());
                if (level == null) continue;
                for (ChunkPos chunk : chunks()) {
                    level.getChunkSource().removeRegionTicket(TICKET, chunk, 0, owner);
                }
            }
            ticketsAdded = false;
        }
    }

    /** Blocs visibles d'une poche, murs compris, avec une case d'air autour pour que le verre se voie de dehors. */
    private static FarRingPayload.Data capture(ServerLevel level, List<LevelChunk> chunks) {
        BlockPos origin = NeighborPocketsPayload.ORIGIN;
        int quarts = ((MAX + 1 - origin.getX()) >> 2) + 1;
        VisibleCapture visible = new VisibleCapture(origin.getX(), origin.getY(), origin.getZ(), origin.getX(), origin.getZ(), quarts);
        VisibleCapture.Shape shape = new VisibleCapture.Shape() {
            @Override
            public boolean contains(int x, int y, int z) {
                return x >= MIN - 1 && x <= MAX + 1 && z >= MIN - 1 && z <= MAX + 1 && y >= MIN_Y - 1 && y <= MAX_Y + 1;
            }

            @Override
            public boolean darkVisible(int x, int y, int z) {
                // Une petite poche : tout ce qui touche de l'air se voit (le dehors des murs n'a souvent pas de lumiere).
                return true;
            }
        };
        for (LevelChunk chunk : chunks) {
            visible.capture(level, chunk, MIN, MAX, MIN_Y, MAX_Y, MIN, MAX, shape);
        }
        return visible.result();
    }
}

package com.matthieu.planariumview.server;

import com.matthieu.planariumview.network.FarRingPayload;
import com.matthieu.planariumview.network.FlightPayload;
import com.matthieu.planariumview.config.Config;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Decor en vol : pendant qu'un Planarium bouge (sur un vaisseau Sable), le decor jusqu'a la distance de vue n'est pas
 * recapture en entier tous les quelques blocs. Il est fait de sections de 16 blocs rangees par position dans le monde : seules
 * celles qui entrent dans le rayon (plus une section d'avance) sont capturees et envoyees, celles qui en sortent sont
 * oubliees. Le client les garde telles quelles pendant le deplacement (voir FlightView).
 *
 * Format compact de l'anneau lointain : des blocs seulement. Les blocs animes, particules et ambiances du decor
 * reviennent a l'arret : apres quelques secondes immobile, une photo normale est prise a la position actuelle, puis le
 * vol se termine.
 */
public final class FlightServer {
    /** Immobile ce temps : fin du vol. */
    private static final int STILL_TICKS = 100;
    /** Loaded at level 33, without simulation outside the detail radius. Renewed while flying. */
    private static final TicketType<UUID> TICKET = TicketType.create("planariumview_flight", UUID::compareTo, 600);

    private static final class State {
        ServerLevel level;
        UUID owner;
        ResourceKey<Level> pocket;
        ResourceKey<Level> source;
        BlockPos stillAt;
        long movedAt = Long.MIN_VALUE / 2;
        boolean active;
        boolean readySent;
        final LongOpenHashSet sent = new LongOpenHashSet();
        final LongOpenHashSet dirty = new LongOpenHashSet();
        final LongOpenHashSet tickets = new LongOpenHashSet();
        ChunkPos ticketCenter;
        int ticketRadius = -1;
        long renewTicketsAt;
        BlockPos viewCenter;
        int viewRadius = -1;
    }

    private static final Map<UUID, State> STATES = new HashMap<>();

    private FlightServer() {
    }

    /** Vrai pendant le vol : pas de capture du rayon detaille normal (sauf celle de la fin, voir wantsNear). */
    static boolean active(UUID player) {
        State state = STATES.get(player);
        return state != null && state.active;
    }

    /** Vrai quand le vol se termine : une photo normale a la position actuelle est attendue. */
    static boolean wantsNear(UUID player, long now) {
        State state = STATES.get(player);
        return state != null && state.active && now - state.movedAt >= STILL_TICKS;
    }

    static void forget(UUID player) {
        State state = STATES.remove(player);
        if (state != null) release(state);
    }

    /** Fermeture du monde : rien ne doit passer au monde suivant (meme dimension, memes coordonnees). */
    static void clearAll() {
        STATES.values().forEach(FlightServer::release);
        STATES.clear();
    }

    /**
     * Chaque tick, pour un joueur suivi dans un bocal. {@code live} : position actuelle du Planarium ;
     * {@code captured} : centre de la derniere photo normale ; {@code hasFull} : le client a une photo normale.
     */
    static void tick(ServerPlayer player, ServerLevel source, ResourceKey<Level> pocket, BlockPos live, BlockPos captured,
                     int radius, boolean hasFull) {
        if (!player.connection.hasChannel(FlightPayload.TYPE)) {
            return;
        }
        UUID id = player.getUUID();
        State state = STATES.get(id);
        if (state == null || !pocket.equals(state.pocket) || !source.dimension().equals(state.source)) {
            if (state != null) release(state);
            state = new State();
            state.level = source;
            state.owner = id;
            state.pocket = pocket;
            state.source = source.dimension();
            STATES.put(id, state);
        }
        long now = player.server.getTickCount();
        if (state.stillAt == null) {
            state.stillAt = live;
            return;
        }
        // Un vaisseau a l'arret peut trembler d'un bloc : ce n'est pas un deplacement.
        if (state.stillAt.distSqr(live) > 2) {
            state.stillAt = live;
            state.movedAt = now;
            if (!state.active && hasFull) {
                state.active = true;
                state.readySent = false;
                state.sent.clear();
                state.dirty.clear();
                state.viewCenter = null;
                PacketDistributor.sendToPlayer(player, FlightPayload.signal(pocket.location(), FlightPayload.START));
            }
        }
        if (!state.active) {
            return;
        }
        // Arrete depuis quelques secondes, et la photo normale de cette position est envoyee : fin du vol.
        if (now - state.movedAt >= STILL_TICKS && captured.equals(live) && hasFull) {
            state.active = false;
            state.sent.clear();
            state.dirty.clear();
            release(state);
            PacketDistributor.sendToPlayer(player, FlightPayload.signal(pocket.location(), FlightPayload.STOP));
            return;
        }
        stream(player, source, pocket, state, live, Math.max(radius, Config.FAR_RADIUS.get()));
    }

    /** Sections du rayon (plus une d'avance) : oublie celles qui sortent, envoie celles qui entrent ou ont change. */
    private static void stream(ServerPlayer player, ServerLevel level, ResourceKey<Level> pocket, State state,
                               BlockPos live, int radius) {
        int reach = radius + 8 + 16;
        int keep = reach + 16;
        keepLoaded(state, live, reach + 16, player.server.getTickCount());
        if (!live.equals(state.viewCenter) || state.viewRadius != radius) {
            PacketDistributor.sendToPlayer(player, FlightPayload.view(pocket.location(), live, radius,
                    level.getMinSection(), level.getMaxSection() - 1));
            state.viewCenter = live;
            state.viewRadius = radius;
        }
        LongArrayList dropped = new LongArrayList();
        var it = state.sent.iterator();
        while (it.hasNext()) {
            long key = it.nextLong();
            if (!near(key, live, keep)) {
                it.remove();
                state.dirty.remove(key);
                dropped.add(key);
            }
        }
        if (!dropped.isEmpty()) {
            PacketDistributor.sendToPlayer(player, FlightPayload.drop(pocket.location(), dropped.toLongArray()));
        }
        LongArrayList wanted = new LongArrayList();
        int minSy = Math.max(level.getMinSection(), (live.getY() - reach) >> 4);
        int maxSy = Math.min(level.getMaxSection() - 1, (live.getY() + reach) >> 4);
        for (int sx = (live.getX() - reach) >> 4; sx <= (live.getX() + reach) >> 4; sx++) {
            for (int sz = (live.getZ() - reach) >> 4; sz <= (live.getZ() + reach) >> 4; sz++) {
                for (int sy = minSy; sy <= maxSy; sy++) {
                    long key = SectionPos.asLong(sx, sy, sz);
                    if (near(key, live, reach) && (!state.sent.contains(key) || state.dirty.contains(key))) {
                        wanted.add(key);
                    }
                }
            }
        }
        // Les plus proches d'abord.
        wanted.sort((it.unimi.dsi.fastutil.longs.LongComparator) (a, b) -> Long.compare(distance(a, live), distance(b, live)));
        int done = 0;
        LongArrayList empty = new LongArrayList();
        for (int i = 0; i < wanted.size(); i++) {
            long key = wanted.getLong(i);
            if (!CaptureBudget.available()) {
                break;
            }
            long start = System.nanoTime();
            int sx = SectionPos.x(key), sy = SectionPos.y(key), sz = SectionPos.z(key);
            LevelChunk chunk = level.getChunkSource().getChunkNow(sx, sz);
            if (chunk == null || !chunk.isLightCorrect()) {
                continue;
            }
            if (chunk.getSection(level.getSectionIndexFromSectionY(sy)).hasOnlyAir()) {
                state.dirty.remove(key);
                state.sent.add(key);
                empty.add(key);
                done++;
                CaptureBudget.spend(System.nanoTime() - start);
                continue;
            }
            // Wait for the halo as well: an absent neighbor would otherwise become a permanent solid border.
            if (!neighborsReady(level, sx, sz)) continue;
            int x0 = sx << 4, y0 = sy << 4, z0 = sz << 4;
            // Eight blocks cover the client's maximum seven-block biome blend, including section boundaries.
            VisibleCapture capture = new VisibleCapture(x0 - 1, y0 - 1, z0 - 1, x0 - 8, z0 - 8, 8).withNeighbors();
            // Les voisins hors de la section sont lus dans le vrai monde (faces et lumiere aux bords).
            capture.capture(level, chunk, x0, x0 + 15, y0, y0 + 15, z0, z0 + 15, (x, y, z) -> true);
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                LevelChunk neighbor = level.getChunkSource().getChunkNow(sx + dx, sz + dz);
                if (neighbor != null) capture.captureBiomes(level, neighbor,
                        x0 - 8, x0 + 23, y0, y0 + 15, z0 - 8, z0 + 23);
            }
            CaptureBudget.spend(System.nanoTime() - start);
            state.dirty.remove(key);
            state.sent.add(key);
            done++;
            if (!capture.blocks.isEmpty()) {
                FarRingPayload.Data data = capture.result();
                PacketDistributor.sendToPlayer(player, FlightPayload.section(pocket.location(), key, data));
            } else {
                empty.add(key);
            }
        }
        if (!empty.isEmpty()) PacketDistributor.sendToPlayer(player, FlightPayload.empty(pocket.location(), empty.toLongArray()));
        if (!state.readySent && done == wanted.size()) {
            state.readySent = true;
            PacketDistributor.sendToPlayer(player, FlightPayload.signal(pocket.location(), FlightPayload.READY));
        }
    }

    private static boolean neighborsReady(ServerLevel level, int x, int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                LevelChunk neighbor = level.getChunkSource().getChunkNow(x + dx, z + dz);
                if (neighbor == null || !neighbor.isLightCorrect()) return false;
            }
        }
        return true;
    }

    private static void keepLoaded(State state, BlockPos live, int radius, long now) {
        ChunkPos center = new ChunkPos(live);
        boolean renew = now >= state.renewTicketsAt;
        if (center.equals(state.ticketCenter) && radius == state.ticketRadius && !renew) return;
        LongOpenHashSet wanted = new LongOpenHashSet();
        for (int x = (live.getX() - radius) >> 4; x <= (live.getX() + radius) >> 4; x++) {
            for (int z = (live.getZ() - radius) >> 4; z <= (live.getZ() + radius) >> 4; z++) {
                long key = ChunkPos.asLong(x, z);
                wanted.add(key);
                if (!state.tickets.contains(key) || renew) {
                    state.level.getChunkSource().addRegionTicket(TICKET, new ChunkPos(key), 0, state.owner);
                }
            }
        }
        for (long key : state.tickets) {
            if (!wanted.contains(key)) state.level.getChunkSource().removeRegionTicket(TICKET, new ChunkPos(key), 0, state.owner);
        }
        state.tickets.clear();
        state.tickets.addAll(wanted);
        state.ticketCenter = center;
        state.ticketRadius = radius;
        if (renew) state.renewTicketsAt = now + 100;
    }

    private static void release(State state) {
        for (long key : state.tickets) {
            state.level.getChunkSource().removeRegionTicket(TICKET, new ChunkPos(key), 0, state.owner);
        }
        state.tickets.clear();
        state.ticketCenter = null;
    }

    private static boolean near(long section, BlockPos live, int reach) {
        return Math.abs((SectionPos.x(section) << 4) + 8 - live.getX()) <= reach
                && Math.abs((SectionPos.y(section) << 4) + 8 - live.getY()) <= reach
                && Math.abs((SectionPos.z(section) << 4) + 8 - live.getZ()) <= reach;
    }

    private static long distance(long section, BlockPos live) {
        long dx = (SectionPos.x(section) << 4) + 8 - live.getX();
        long dy = (SectionPos.y(section) << 4) + 8 - live.getY();
        long dz = (SectionPos.z(section) << 4) + 8 - live.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    /** Un bloc a change : ses sections deja envoyees (et les voisines s'il est au bord) seront renvoyees. */
    static void blockChanged(ResourceKey<Level> source, BlockPos pos) {
        if (STATES.isEmpty()) {
            return;
        }
        for (State state : STATES.values()) {
            if (!state.active || !source.equals(state.source)) {
                continue;
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        long key = SectionPos.asLong((pos.getX() + dx) >> 4, (pos.getY() + dy) >> 4, (pos.getZ() + dz) >> 4);
                        if (state.sent.contains(key)) {
                            state.dirty.add(key);
                        }
                    }
                }
            }
        }
    }
}

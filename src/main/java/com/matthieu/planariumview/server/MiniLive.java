package com.matthieu.planariumview.server;

import com.hollingsworth.arsnouveau.common.block.tile.PlanariumTile;
import com.matthieu.planariumview.config.Config;
import com.matthieu.planariumview.network.entities.MiniLivePayload;
import com.matthieu.planariumview.network.entities.MiniWatchPayload;
import com.matthieu.planariumview.server.entities.OutsideMobsServer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Miniatures vivantes (option du monde "Faire vivre les miniatures") : le bocal que des joueurs regardent de pres tourne
 * pour de vrai, et ses mobs sont envoyes quelques fois par seconde a ces joueurs seulement. Ailleurs, les miniatures
 * gardent l'instantane anime (MiniatureSync).
 *
 * <p>Les 4 chunks de l'interieur sont au niveau 31, les 12 voisins au niveau 32 (blocs), puis 20 au niveau 33
 * (charges sans simulation). Les dependances de generation s'etendent encore au-dela. Sans joueur dans la dimension,
 * Minecraft arreterait ses entites apres 15 secondes : elle est donc
 * gardee occupee, comme le fait ViewTickets pour le decor. Le nombre de bocaux vivants est limite sur tout le serveur.
 */
public final class MiniLive {
    /** Pas d'expiration : le ticket est retire des que plus personne ne regarde (voir release). */
    private static final TicketType<ChunkPos> LIVE_TICKET =
            TicketType.create("planariumview_mini_live", Comparator.comparingLong(ChunkPos::toLong));
    /** addRegionTicket part du niveau 33 : distance 2 = niveau 31 sur le chunk vise (ses entites tournent). */
    private static final int TICKET_DISTANCE = 2;
    /** Un envoi tous les 4 ticks (5 fois par seconde) : le client interpole entre deux envois. */
    private static final int SEND_TICKS = 4;
    /** Le client renvoie sa miniature toutes les 2 secondes : sans nouvelles depuis 3 secondes, elle n'est plus regardee. */
    private static final int WATCH_TIMEOUT = 60;
    /** Le client regarde a 8 blocs au plus ; un peu de marge pour un joueur qui bouge. */
    private static final double MAX_DISTANCE_SQUARED = 12.0 * 12.0;

    private record Watch(ResourceKey<Level> source, BlockPos pos, ResourceKey<Level> pocket, long seen,
                         int mobLimit, boolean vehicles) {
    }

    private static final Map<UUID, Watch> WATCHES = new HashMap<>();
    /** Bocaux qui ont leurs tickets en ce moment, dans l'ordre ou ils ont commence. */
    private static final Set<ResourceKey<Level>> ACTIVE = new LinkedHashSet<>();

    private MiniLive() {
    }

    /** Vrai si ce bocal tourne pour sa miniature (MiniatureSync relit alors ses mobs plus souvent). */
    static boolean active(ResourceKey<Level> pocket) {
        return ACTIVE.contains(pocket);
    }

    /**
     * Miniature regardee par ce joueur. Le serveur ne fait pas confiance au client : il faut un vrai Planarium a cette
     * position, dans le monde du joueur, assez pres de lui (sa position dans le monde, s'il est sur une structure Sable).
     */
    public static void watch(ServerPlayer player, MiniWatchPayload payload) {
        UUID id = player.getUUID();
        if (!payload.watching() || !Config.miniatureLive() || payload.mobLimit() == 0) {
            WATCHES.remove(id);
            return;
        }
        BlockPos pos = payload.pos();
        Level level = player.level();
        if (!level.isLoaded(pos)) {
            WATCHES.remove(id);
            return;
        }
        Vec3 where = com.matthieu.planariumview.compat.sable.SubLevelCompat.worldPosition(level, Vec3.atCenterOf(pos));
        if (where.distanceToSqr(player.position()) > MAX_DISTANCE_SQUARED
                || !(level.getBlockEntity(pos) instanceof PlanariumTile tile) || tile.key == null) {
            WATCHES.remove(id);
            return;
        }
        if (!player.connection.hasChannel(MiniLivePayload.TYPE)
                || !player.connection.hasChannel(com.matthieu.planariumview.network.entities.MiniEntitiesPayload.TYPE)) {
            WATCHES.remove(id);
            return;
        }
        Watch previous = WATCHES.put(id, new Watch(level.dimension(), pos.immutable(), tile.key, player.server.getTickCount(),
                payload.mobLimit(), payload.vehicles()));
        if (previous == null || !previous.pocket().equals(tile.key)) {
            MiniatureSync.sendToWatcher(player, tile.key);
        }
    }

    private static boolean valid(ServerPlayer player, Watch watch) {
        if (player == null || !player.level().dimension().equals(watch.source())) return false;
        Level level = player.level();
        if (!level.isLoaded(watch.pos())) return false;
        Vec3 where = com.matthieu.planariumview.compat.sable.SubLevelCompat.worldPosition(level, watch.pos().getCenter());
        return where.distanceToSqr(player.position()) <= MAX_DISTANCE_SQUARED
                && level.getBlockEntity(watch.pos()) instanceof PlanariumTile tile && watch.pocket().equals(tile.key);
    }

    /** Chaque tick du serveur. */
    public static void tick(MinecraftServer server) {
        long now = server.getTickCount();
        boolean allowed = Config.miniatureLive();
        WATCHES.entrySet().removeIf(entry -> !allowed || now - entry.getValue().seen() > WATCH_TIMEOUT
                || !valid(server.getPlayerList().getPlayer(entry.getKey()), entry.getValue()));
        // Bocaux demandes : ceux qui tournent deja d'abord (pas de va-et-vient a la limite), puis les autres.
        Set<ResourceKey<Level>> requested = new LinkedHashSet<>();
        for (Watch watch : WATCHES.values()) {
            if (server.getLevel(watch.pocket()) != null) requested.add(watch.pocket());
        }
        int max = Config.miniatureLiveMax();
        Set<ResourceKey<Level>> wanted = new LinkedHashSet<>();
        for (ResourceKey<Level> pocket : ACTIVE) {
            if (requested.contains(pocket) && wanted.size() < max) wanted.add(pocket);
        }
        for (ResourceKey<Level> pocket : requested) {
            if (wanted.size() < max) wanted.add(pocket);
        }
        for (Iterator<ResourceKey<Level>> it = ACTIVE.iterator(); it.hasNext(); ) {
            ResourceKey<Level> pocket = it.next();
            if (!wanted.contains(pocket)) {
                it.remove();
                release(server, pocket);
            }
        }
        for (ResourceKey<Level> pocket : wanted) {
            if (ACTIVE.add(pocket)) {
                hold(server, pocket);
                MiniatureSync.liveStarted(pocket);
            }
        }
        if (ACTIVE.isEmpty()) {
            return;
        }
        boolean send = now % SEND_TICKS == 0;
        for (ResourceKey<Level> key : ACTIVE) {
            ServerLevel pocket = server.getLevel(key);
            if (pocket == null) {
                continue;
            }
            if (pocket.players().isEmpty()) {
                pocket.resetEmptyTime();
            }
            if (send) {
                send(server, pocket);
            }
        }
    }

    private static void send(MinecraftServer server, ServerLevel pocket) {
        List<Entity> mobs = MiniatureSync.trackedEntities(pocket);
        // Sans joueur pres d'eux, les mobs cesseraient de se promener apres 5 secondes (RandomStrollGoalMixin).
        OutsideMobsServer.observeMiniature(mobs, server.getTickCount());
        Map<UUID, MiniLivePayload.Entry> poses = new HashMap<>();
        for (Entity mob : mobs) {
            int flags = (mob.onGround() ? MiniLivePayload.Entry.ON_GROUND : 0)
                    | (mob.isInWater() ? MiniLivePayload.Entry.IN_WATER : 0)
                    | (mob.isUnderWater() ? MiniLivePayload.Entry.UNDERWATER : 0);
            float body = mob instanceof LivingEntity living ? living.yBodyRot : mob.getYRot();
            poses.put(mob.getUUID(), new MiniLivePayload.Entry(mob.getUUID(), (float) mob.getX(), (float) mob.getY(), (float) mob.getZ(),
                    body, mob.getYHeadRot(), mob.getXRot(), flags));
        }
        for (Map.Entry<UUID, Watch> watch : WATCHES.entrySet()) {
            if (!watch.getValue().pocket().equals(pocket.dimension())) continue;
            ServerPlayer player = server.getPlayerList().getPlayer(watch.getKey());
            if (player != null && player.connection.hasChannel(MiniLivePayload.TYPE)) {
                List<MiniLivePayload.Entry> entries = MiniatureSync.selectEntities(mobs, watch.getValue().mobLimit(), watch.getValue().vehicles())
                        .stream().map(entity -> poses.get(entity.getUUID())).toList();
                MiniLivePayload payload = new MiniLivePayload(pocket.dimension().location(), entries);
                PacketDistributor.sendToPlayer(player, payload);
            }
        }
    }

    private static void hold(MinecraftServer server, ResourceKey<Level> key) {
        ServerLevel pocket = server.getLevel(key);
        if (pocket == null) return;
        for (int cx = 0; cx <= 1; cx++) {
            for (int cz = 0; cz <= 1; cz++) {
                ChunkPos chunk = new ChunkPos(cx, cz);
                pocket.getChunkSource().addRegionTicket(LIVE_TICKET, chunk, TICKET_DISTANCE, chunk);
            }
        }
    }

    private static void release(MinecraftServer server, ResourceKey<Level> key) {
        ServerLevel pocket = server.getLevel(key);
        if (pocket == null) return;
        for (int cx = 0; cx <= 1; cx++) {
            for (int cz = 0; cz <= 1; cz++) {
                ChunkPos chunk = new ChunkPos(cx, cz);
                pocket.getChunkSource().removeRegionTicket(LIVE_TICKET, chunk, TICKET_DISTANCE, chunk);
            }
        }
        MiniatureSync.liveStopped(pocket);
    }

    public static void logout(UUID player) {
        WATCHES.remove(player);
    }

    /** Arret du serveur : les tickets disparaissent avec lui. */
    static void clear() {
        WATCHES.clear();
        ACTIVE.clear();
    }
}

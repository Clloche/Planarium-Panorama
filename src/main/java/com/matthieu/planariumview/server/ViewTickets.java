package com.matthieu.planariumview.server;

import com.matthieu.planariumview.server.ServerView.Track;
import com.matthieu.planariumview.server.entities.OutsideMobsServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.matthieu.planariumview.server.OutsideSounds.prefsOf;

/** Chargement des chunks du decor et maintien en activite des dimensions visitees depuis un bocal. */
final class ViewTickets {
    /** Ticket de chargement : garde la zone autour du Planarium chargee tant qu'un joueur est dans le bocal. */
    private static final TicketType<UUID> VIEW_TICKET =
            TicketType.create("planariumview", UUID::compareTo);

    private ViewTickets() {
    }

    /**
     * Sans joueur a proximite, les chunks autour du Planarium se dechargent et les rafraichissements
     * ne peuvent plus lire les blocs. On les garde charges (sans les faire "tourner") pendant la visite.
     */
    /**
     * Garde la zone du decor chargee. Option "Sons du monde exterieur" ON : un seul ticket qui fait aussi tourner la zone
     * (redstone, machines, mobs), comme si le joueur y etait. OFF : un ticket par chunk, au niveau juste suffisant pour que
     * le chunk soit charge et lisible, sans rien y faire tourner (comme quand un joueur s'en va).
     */
    static void keepLoaded(MinecraftServer server, UUID player, Track track, int radius) {
        // Zone active pour les sons du monde exterieur, ou pour que les mobs affiches bougent, seulement si le monde le
        // permet (option du serveur "Faire vivre le dehors"). Sinon : chargee et lisible, sans rien y faire tourner.
        boolean live = com.matthieu.planariumview.config.Config.outsideLive();
        boolean mobs = live && OutsideMobsServer.wants(player);
        boolean active = live && (prefsOf(player).world() || mobs);
        int chunks = active
                ? activeTicketDistance(track.center, radius + (mobs ? OutsideMobsServer.SIMULATION_MARGIN : 0))
                : Math.floorDiv(radius + 15, 16) + 1;
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

    /**
     * addRegionTicket part du niveau FULL (33). Il faut deux niveaux de plus pour que tous les chunks vises tournent
     * aussi leurs entites (niveau 31), en tenant compte du decalage du Planarium dans son chunk.
     */
    private static int activeTicketDistance(BlockPos pos, int radius) {
        ChunkPos center = new ChunkPos(pos);
        int x = Math.max(center.x - Math.floorDiv(pos.getX() - radius, 16),
                Math.floorDiv(pos.getX() + radius, 16) - center.x);
        int z = Math.max(center.z - Math.floorDiv(pos.getZ() - radius, 16),
                Math.floorDiv(pos.getZ() + radius, 16) - center.z);
        return Math.max(x, z) + 2;
    }

    /** Retire les tickets de chargement de ce suivi, selon le mode avec lequel ils ont ete poses. */
    static void releaseTickets(ServerLevel source, Track track) {
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

    /**
     * Option "Sons du monde exterieur" ON.
     * Minecraft arrete de faire tourner les entites et les blocs speciaux d'une dimension 15 s apres que le dernier
     * joueur l'a quittee (pistons figes en plein mouvement, fours, entonnoirs et mobs arretes). Un joueur qui regarde
     * cette dimension depuis un bocal la garde donc "occupee", comme s'il y etait. Les chunks qui tournent restent ceux
     * que le Planarium garde charges, plus ceux que Minecraft garde deja.
     */
    static void keepSourcesAwake(MinecraftServer server, Map<UUID, Track> tracks, Set<UUID> disabled) {
        for (Map.Entry<UUID, Track> entry : tracks.entrySet()) {
            // Seulement pour les joueurs qui veulent les sons du monde exterieur ou les mobs (zone active, voir keepLoaded).
            if (disabled.contains(entry.getKey()) || !entry.getValue().ticketActive) {
                continue;
            }
            ServerLevel source = server.getLevel(entry.getValue().source.dimension());
            if (source != null && source.players().isEmpty()) {
                source.resetEmptyTime();
            }
        }
    }
}

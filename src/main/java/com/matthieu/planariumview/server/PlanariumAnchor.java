package com.matthieu.planariumview.server;

import com.hollingsworth.arsnouveau.common.block.tile.PlanariumTile;
import com.hollingsworth.arsnouveau.common.world.saved_data.JarDimData;
import com.matthieu.planariumview.server.ServerView.Track;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/** Recherche du Planarium et suivi de sa position, sur le terrain ou sur un vaisseau. */
final class PlanariumAnchor {
    /** Rayon de recherche du Planarium autour de l'endroit d'ou le joueur est entre. */
    private static final int SEARCH_RADIUS = 8;

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(ServerView.class);

    private PlanariumAnchor() {
    }

    /** Nom donne au Planarium (renomme dans une enclume), ou vide. Sert au titre de Traveler's Titles. */
    static String planariumName(ServerLevel source, BlockPos pos) {
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

    /**
     * Retrouve le Planarium par lequel le joueur est entre, a partir de la position
     * memorisee par Ars Nouveau (JarDimData.enteredFrom).
     */
    static GlobalPos findPlanarium(ServerPlayer player, ServerLevel pocket) {
        var entry = com.matthieu.planariumview.compat.ars_sable.ArsSableCompat.entry(pocket, player.getUUID());
        if (entry != null && entry.body() != null) {
            ServerLevel source = player.server.getLevel(entry.position().dimension());
            if (source == null) return null;
            BlockPos found = com.matthieu.planariumview.compat.sable.SubLevelCompat.findPlanarium(source,
                    pocket.dimension(), null, entry.body(), 0);
            // A known vessel may still be loading. Never substitute another Planarium near the stale world position.
            return found == null ? null : GlobalPos.of(source.dimension(), found);
        }
        JarDimData.RotPos from = JarDimData.from(pocket).getEnteredFrom(player.getUUID());
        if (from == null) {
            return null;
        }
        ServerLevel sourceLevel = player.server.getLevel(from.pos().dimension());
        if (sourceLevel == null) {
            return null;
        }
        if (entry != null) {
            sourceLevel = player.server.getLevel(entry.position().dimension());
            if (sourceLevel == null) return null;
        }
        BlockPos center = entry == null ? from.pos().pos() : entry.position().pos();
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
            best = com.matthieu.planariumview.compat.sable.SubLevelCompat.findPlanarium(sourceLevel, pocket.dimension(),
                    Vec3.atLowerCornerOf(center), SEARCH_RADIUS);
        }
        return best == null ? null : GlobalPos.of(sourceLevel.dimension(), best);
    }

    static Vec3 relative(Track track, Vec3 world) {
        org.joml.Vector3d relative = new org.joml.Matrix4d(track.anchor).invert()
                .transformPosition(new org.joml.Vector3d(world.x, world.y, world.z));
        return new Vec3(relative.x, relative.y, relative.z);
    }

    static boolean updateAnchor(Track track, ServerLevel source) {
        boolean here = source.hasChunkAt(track.source.pos())
                && source.getBlockEntity(track.source.pos()) instanceof PlanariumTile tile && track.pocket.equals(tile.key);
        UUID body = track.body;
        org.joml.Matrix4d anchor = null;
        // Planarium embarque : son plot doit toujours appartenir a son vaisseau. Sinon (vaisseau recharge sous un autre
        // plot, ou retire), la position du plot etait prise pour une position dans le monde et la vue ne suivait plus.
        if (here) {
            if (body == null) body = com.matthieu.planariumview.compat.sable.SubLevelCompat.ownerId(source, track.source.pos());
            anchor = trackedAnchor(source, track.source.pos(), body);
            here = anchor != null;
        }
        if (!here) {
            if (!track.lost) {
                track.lost = true;
                LOGGER.debug("[Planarium Panorama] Planarium perdu en {} (vaisseau {}) : recherche", track.source.pos(),
                        track.body);
            }
            if (track.body != null && !com.matthieu.planariumview.compat.sable.SubLevelCompat.available()) return false;
            // Planarium casse ou introuvable : au plus une recherche par seconde (sinon 17^3 blocs toutes les 2 ticks).
            // La vue garde sa derniere position pendant ce temps.
            long tick = source.getServer().getTickCount();
            if (tick < track.nextSearch) return false;
            track.nextSearch = tick + 20;
            BlockPos found = null;
            if (track.body != null) {
                // Par le vaisseau, n'importe ou sur lui : son nouveau plot peut etre loin de l'ancien.
                found = com.matthieu.planariumview.compat.sable.SubLevelCompat.findPlanarium(source, track.pocket, null,
                        track.body, 0);
            }
            if (found == null) found = com.matthieu.planariumview.compat.sable.SubLevelCompat.findPlanarium(source, track.pocket,
                    new Vec3(track.anchor.m30(), track.anchor.m31(), track.anchor.m32()), SEARCH_RADIUS);
            if (found == null) {
                for (BlockPos at : BlockPos.betweenClosed(track.center.offset(-SEARCH_RADIUS, -SEARCH_RADIUS, -SEARCH_RADIUS),
                        track.center.offset(SEARCH_RADIUS, SEARCH_RADIUS, SEARCH_RADIUS))) {
                    if (source.hasChunkAt(at) && source.getBlockEntity(at) instanceof PlanariumTile candidate
                            && track.pocket.equals(candidate.key)
                            && !com.matthieu.planariumview.compat.sable.SubLevelCompat.isPlotSpace(source, at)) {
                        found = at.immutable();
                        break;
                    }
                }
            }
            if (found == null) return false;
            body = com.matthieu.planariumview.compat.sable.SubLevelCompat.ownerId(source, found);
            anchor = trackedAnchor(source, found, body);
            if (anchor == null || track.body != null && !com.matthieu.planariumview.compat.sable.SubLevelCompat.available()) return false;
            track.source = GlobalPos.of(source.dimension(), found);
        }
        track.body = body;
        if (track.lost) {
            track.lost = false;
            LOGGER.debug("[Planarium Panorama] Planarium retrouve en {} (vaisseau {})", track.source.pos(), track.body);
        }
        track.anchorBody = com.matthieu.planariumview.compat.sable.SubLevelCompat.holdAnchor(source, track.source.pos(),
                track.owner, track.anchorBody);
        BlockPos center = BlockPos.containing(anchor.m30(), anchor.m31(), anchor.m32());
        // Pas de capture complete a chaque bloc parcouru : le client recale l'ancienne photo autour du bocal
        // (SubLevelView.terrain), et update() refait une capture quand le centre s'est assez eloigne de celui capture.
        track.anchor = anchor;
        track.center = center;
        return true;
    }

    private static org.joml.Matrix4d trackedAnchor(ServerLevel source, BlockPos pos, UUID body) {
        if (body != null) return com.matthieu.planariumview.compat.sable.SubLevelCompat.anchorOnBody(source, pos, body);
        return com.matthieu.planariumview.compat.sable.SubLevelCompat.isPlotSpace(source, pos) ? null
                : com.matthieu.planariumview.compat.sable.SubLevelCompat.anchor(source, pos);
    }
}

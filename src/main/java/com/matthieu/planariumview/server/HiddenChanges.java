package com.matthieu.planariumview.server;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.HashMap;
import java.util.Map;

/**
 * Changements de blocs qui semblent enfermes sous terre (mineshaft en feu, ferme dans une grotte fermee...) : aucune case
 * qui voit le ciel a moins de 15 blocs en passant par l'air. D'un Planarium a ciel ouvert, on ne les voit en general pas.
 * Une grande grotte sombre peut se voir de loin par une large ouverture : ce n'est qu'une estimation.
 * Les changements de sources lumineuses limitent donc la reutilisation du cache de l'anneau (voir DELAY_TICKS).
 *
 * Un Planarium sans ciel (grotte, piece fermee) voit justement ce genre d'endroit : pour lui, rien n'est ralenti.
 */
final class HiddenChanges {
    /** Delai de reutilisation du cache de l'anneau apres un changement de source lumineuse cachee (60 s). */
    static final int DELAY_TICKS = 60 * 20;
    /** La lumiere d'un bloc porte a 15 blocs au plus. */
    private static final int REACH = 15;
    /** Cases lues au plus par recherche (grande caverne) : au-dela, le changement compte comme visible. */
    private static final int MAX_CELLS = 8000;
    /** Un resultat "cache" est reutilise ce temps pour les changements voisins (un feu change des blocs sans arret). */
    private static final int KEEP_TICKS = 40;
    private static final Direction[] DIRECTIONS = Direction.values();
    /** Par dimension : cube de 4 blocs -> tick jusqu'auquel ses changements sont caches. */
    private static final Map<ResourceKey<Level>, Long2LongOpenHashMap> HIDDEN = new HashMap<>();

    private HiddenChanges() {
    }

    /** Resultat calcule au plus une fois par changement, et seulement si un decor le contient. */
    static final class Check {
        private final ServerLevel level;
        private final BlockPos pos;
        private int hidden = -1;

        Check(ServerLevel level, BlockPos pos) {
            this.level = level;
            this.pos = pos;
        }

        /** Vrai si ce changement ne se voit probablement pas depuis un Planarium a {@code center} (file lente). */
        boolean slowFor(BlockPos center) {
            if (center == null || !openSky(level, center)) {
                return false;
            }
            if (hidden < 0) {
                hidden = hidden(level, pos) ? 1 : 0;
            }
            return hidden == 1;
        }
    }

    static void clear() {
        HIDDEN.clear();
    }

    /** Le Planarium voit le ciel (pas dans une grotte ou une piece fermee). */
    private static boolean openSky(ServerLevel level, BlockPos center) {
        return chunk(level, center) != null && level.getBrightness(LightLayer.SKY, center.above()) > 0;
    }

    private static LevelChunk chunk(ServerLevel level, BlockPos pos) {
        return level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
    }

    private static boolean hidden(ServerLevel level, BlockPos pos) {
        // Juste a cote d'une case qui voit le ciel (on vient de creuser jusqu'a la surface...) : toujours visible,
        // sans regarder le resultat garde.
        BlockPos.MutableBlockPos next = new BlockPos.MutableBlockPos();
        if (level.getBrightness(LightLayer.SKY, pos) > 0) return false;
        for (Direction direction : DIRECTIONS) {
            next.setWithOffset(pos, direction);
            if (chunk(level, next) == null || level.getBrightness(LightLayer.SKY, next) > 0) return false;
        }
        long now = level.getServer().getTickCount();
        Long2LongOpenHashMap kept = HIDDEN.computeIfAbsent(level.dimension(), key -> {
            Long2LongOpenHashMap map = new Long2LongOpenHashMap();
            map.defaultReturnValue(Long.MIN_VALUE);
            return map;
        });
        long cell = BlockPos.asLong(pos.getX() >> 2, pos.getY() >> 2, pos.getZ() >> 2);
        if (kept.get(cell) >= now) return true;
        boolean hidden = enclosed(level, pos);
        if (hidden) {
            if (kept.size() > 4096) kept.clear();
            kept.put(cell, now + KEEP_TICKS);
        } else {
            kept.remove(cell);
        }
        return hidden;
    }

    /**
     * Parcourt l'air (tout ce qui n'est pas un bloc plein opaque) autour du changement, jusqu'a 15 blocs : vrai si aucune
     * case ne voit le ciel. Distance en ligne droite (pas le long du chemin) : en cas de doute, on cherche plus loin.
     */
    private static boolean enclosed(ServerLevel level, BlockPos start) {
        LongOpenHashSet seen = new LongOpenHashSet();
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        seen.add(start.asLong());
        queue.enqueue(start.asLong());
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos next = new BlockPos.MutableBlockPos();
        int budget = MAX_CELLS;
        while (!queue.isEmpty()) {
            at.set(queue.dequeueLong());
            for (Direction direction : DIRECTIONS) {
                next.setWithOffset(at, direction);
                if (next.distManhattan(start) > REACH || !seen.add(next.asLong())) continue;
                LevelChunk chunk = chunk(level, next);
                // Chunk absent : on ne sait pas, donc visible.
                if (chunk == null) return false;
                if (level.getBrightness(LightLayer.SKY, next) > 0) return false;
                BlockState state = chunk.getBlockState(next);
                if (state.isSolidRender(level, next)) continue;
                if (--budget <= 0) return false;
                queue.enqueue(next.asLong());
            }
        }
        return true;
    }
}

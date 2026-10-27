package com.matthieu.planariumview.server;

import com.hollingsworth.arsnouveau.common.block.tile.PlanariumTile;
import com.matthieu.planariumview.client.scenery.entities.GiantEntities;
import com.matthieu.planariumview.config.Config;
import com.matthieu.planariumview.network.MiniLightPayload;
import com.matthieu.planariumview.network.entities.MiniEntitiesPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static com.matthieu.planariumview.server.SceneryCapture.captureLight;
import static com.matthieu.planariumview.server.PocketLighting.relightPocket;

/** Lumiere et entites des miniatures : captures partagees et synchronisation des joueurs. */
final class MiniatureSync {
    /** Lumiere des bocaux deja envoyee pour les miniatures, et la version du gabarit d'Ars correspondante. */
    private static final Map<ResourceKey<Level>, byte[]> MINI_LIGHT = new HashMap<>();
    private static final Map<ResourceKey<Level>, Long> MINI_VERSION = new HashMap<>();
    /** Porte-armures, tableaux et cadres de chaque bocal, deja envoyes (voir updateMiniEntities). */
    private static final Map<ResourceKey<Level>, net.minecraft.nbt.CompoundTag> MINI_ENTITIES = new HashMap<>();
    /** Bocaux dont Ars vient de refaire le gabarit : leurs entites sont relues aussi. */
    private static final Set<ResourceKey<Level>> MINI_ENTITIES_DUE = new HashSet<>();
    /** Prochain rafraichissement de secours des entites de chaque bocal (tick du serveur). */
    private static final Map<ResourceKey<Level>, Long> MINI_ENTITIES_NEXT = new HashMap<>();

    private MiniatureSync() {
    }

    /** Renvoie au joueur qui se connecte les dernieres donnees de chaque miniature. */
    static void sendInitialState(ServerPlayer player) {
        for (Map.Entry<ResourceKey<Level>, byte[]> entry : MINI_LIGHT.entrySet()) {
            PacketDistributor.sendToPlayer(player, new MiniLightPayload(entry.getKey().location(), entry.getValue()));
        }
        if (player.connection.hasChannel(MiniEntitiesPayload.TYPE)) {
            for (Map.Entry<ResourceKey<Level>, net.minecraft.nbt.CompoundTag> entry : MINI_ENTITIES.entrySet()) {
                PacketDistributor.sendToPlayer(player, new MiniEntitiesPayload(entry.getKey().location(), entry.getValue()));
            }
        }
    }

    /** La prochaine mise a jour relira la lumiere du bocal une fois son eclairage recalcule. */
    static void invalidateLight(ResourceKey<Level> pocket) {
        MINI_VERSION.remove(pocket);
    }

    /**
     * Quand Ars met a jour le gabarit d'un bocal (blocs changes), on lit aussi la lumiere
     * de l'interieur du bocal et on l'envoie aux clients pour la miniature.
     */
    static void updateMiniatures(MinecraftServer server) {
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
    static void updateMiniEntities(MinecraftServer server) {
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

}

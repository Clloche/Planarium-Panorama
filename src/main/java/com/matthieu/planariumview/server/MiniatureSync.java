package com.matthieu.planariumview.server;

import com.hollingsworth.arsnouveau.common.block.tile.PlanariumTile;
import com.matthieu.planariumview.client.scenery.entities.GiantEntities;
import com.matthieu.planariumview.config.Config;
import com.matthieu.planariumview.network.MiniLightPayload;
import com.matthieu.planariumview.network.entities.MiniEntitiesPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.UUID;
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
    /** Mobs de chaque bocal pour la miniature (UUID -> sauvegarde), gardes apres le dechargement de la poche. */
    private static final Map<ResourceKey<Level>, Map<UUID, CompoundTag>> MINI_MOBS = new HashMap<>();
    /** Bocaux dont les mobs ont change depuis le dernier envoi. */
    private static final Set<ResourceKey<Level>> MINI_MOBS_DIRTY = new HashSet<>();
    /** Prochaine relecture des mobs d'une poche chargee (tick du serveur). */
    private static final Map<ResourceKey<Level>, Long> MINI_MOBS_NEXT = new HashMap<>();
    /** Une capture par entite au dechargement n'est pas encore un instantane complet des quatre chunks. */
    private static final Set<ResourceKey<Level>> MOBS_READY = new HashSet<>();
    /** Chargements en lecture en cours et prochaine tentative en cas de delai depasse. */
    private static final Map<ResourceKey<Level>, Long> READ_PENDING = new HashMap<>();
    private static final Map<ResourceKey<Level>, Long> READ_RETRY_AFTER = new HashMap<>();
    /** Bocaux dont une liste a deja ete envoyee (une liste vide doit alors partir aussi). */
    private static final Set<ResourceKey<Level>> SENT = new HashSet<>();
    private static final int MOB_READ_TICKS = 100;
    private static final int MAX_MOBS = 64;
    private static final int READ_TIMEOUT = 200;
    private static final int READ_RETRY_TICKS = 1200;
    private static final AABB INTERIOR = new AABB(0, 0, 0, 32, 34, 32);
    /** Lecture seule des entites d'une poche : expire seul apres 10 secondes. */
    private static final TicketType<ChunkPos> READ_TICKET =
            TicketType.create("planariumview_mini_read", Comparator.comparingLong(ChunkPos::toLong), READ_TIMEOUT);

    private MiniatureSync() {
    }

    /** Renvoie au joueur qui se connecte les dernieres donnees de chaque miniature. */
    static void sendInitialState(ServerPlayer player) {
        for (Map.Entry<ResourceKey<Level>, byte[]> entry : MINI_LIGHT.entrySet()) {
            PacketDistributor.sendToPlayer(player, new MiniLightPayload(entry.getKey().location(), entry.getValue()));
        }
        if (player.connection.hasChannel(MiniEntitiesPayload.TYPE)) {
            for (ResourceKey<Level> key : SENT) {
                PacketDistributor.sendToPlayer(player, new MiniEntitiesPayload(key.location(), payload(key)));
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
            // Charge les chunks du bocal (comme Ars le fait pour son gabarit) avant de lire la lumiere.
            for (int cx = -1; cx <= 2; cx++) {
                for (int cz = -1; cz <= 2; cz++) {
                    pocket.getChunk(cx, cz);
                }
            }
            // Le recalcul a besoin de ces chunks aussi. Ne pas memoriser la version si leur promotion
            // en chunks complets n'est pas encore terminee : on reessaiera au prochain passage.
            if (!relightPocket(pocket)) {
                continue;
            }
            MINI_VERSION.put(key, dimEntry.lastUpdated());
            MINI_ENTITIES_DUE.add(key);
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
     * Entites de la miniature de chaque bocal : porte-armures, tableaux et cadres (comme pour le decor geant), et les mobs.
     * Les decorations sont relues quand le gabarit du bocal change (blocs) et au rafraichissement de secours. Les mobs sont
     * relus toutes les 5 secondes tant que la poche est chargee, et chacun est enregistre au moment ou son chunk se
     * decharge : la miniature les montre a leur position finale, meme si la poche ne tourne plus. Envoyes seulement
     * s'ils ont change ; un bocal decharge garde la derniere liste envoyee.
     */
    static void updateMiniEntities(MinecraftServer server) {
        updateMiniEntities(server, PlanariumTile.dimManager.entries.keySet(), true);
    }

    private static void updateMiniEntities(MinecraftServer server, Set<ResourceKey<Level>> pockets, boolean allowRead) {
        long now = server.getTickCount();
        // Au plus une lecture a la fois. Un ticket n'est jamais prolonge en attendant une capture.
        for (ResourceKey<Level> key : Set.copyOf(READ_PENDING.keySet())) {
            if (now >= READ_PENDING.get(key) || server.getPlayerList().getPlayers().isEmpty()) {
                ServerLevel pocket = server.getLevel(key);
                if (pocket != null) releaseRead(pocket);
                READ_PENDING.remove(key);
                READ_RETRY_AFTER.put(key, now + READ_RETRY_TICKS);
            }
        }
        int seconds = Config.REFRESH_SECONDS.get();
        long refresh = seconds > 0 ? Math.max(60, seconds) * 20L : 0L;
        boolean readRequested = false;
        for (ResourceKey<Level> key : pockets) {
            ServerLevel pocket = server.getLevel(key);
            if (pocket == null) {
                continue;
            }
            boolean loaded = entitiesLoaded(pocket);
            boolean forced = MINI_ENTITIES_DUE.contains(key);
            boolean changed = false;
            boolean due = !MINI_ENTITIES.containsKey(key) || forced
                    || (refresh > 0 && now >= MINI_ENTITIES_NEXT.getOrDefault(key, 0L));
            if (due && loaded) {
                MINI_ENTITIES_DUE.remove(key);
                MINI_ENTITIES_NEXT.put(key, now + refresh);
                CompoundTag tag = new CompoundTag();
                tag.put("entities", GiantEntities.list(pocket, INTERIOR));
                changed = !tag.equals(MINI_ENTITIES.put(key, tag));
            }
            if (loaded && (forced || !MOBS_READY.contains(key) || now >= MINI_MOBS_NEXT.getOrDefault(key, 0L))) {
                MINI_MOBS_NEXT.put(key, now + MOB_READ_TICKS);
                readMobs(pocket);
                MOBS_READY.add(key);
                if (READ_PENDING.remove(key) != null) releaseRead(pocket);
                READ_RETRY_AFTER.remove(key);
            }
            if (MINI_MOBS_DIRTY.remove(key)) {
                changed = true;
            }
            // Pas encore d'instantane cette session (apres un redemarrage) : un seul chargement en lecture, une poche
            // par passage, et seulement si quelqu'un est connecte.
            if (allowRead && !loaded && !readRequested && !MOBS_READY.contains(key) && READ_PENDING.isEmpty()
                    && !server.getPlayerList().getPlayers().isEmpty() && now >= READ_RETRY_AFTER.getOrDefault(key, 0L)) {
                readRequested = true;
                READ_PENDING.put(key, now + READ_TIMEOUT);
                requestRead(pocket);
            }
            if (changed) {
                send(server, key);
            }
        }
    }

    /** Un joueur sort de ce bocal : relire ses entites tout de suite, pendant qu'elles sont encore chargees. */
    static void captureOnExit(ServerLevel pocket) {
        MINI_ENTITIES_DUE.add(pocket.dimension());
        updateMiniEntities(pocket.getServer(), Set.of(pocket.dimension()), false);
    }

    /**
     * Fin du suivi (ServerView.onEntityLeave). Au dechargement, le chunk devient HIDDEN avant setRemoved : la raison est
     * encore null et l'evenement ne se repete pas ensuite. Sa position est deja finale car ses ticks sont arretes.
     * Tuee, retiree ou changeant de dimension : l'entite disparait de cette miniature.
     */
    static void entityLeft(ServerLevel pocket, Entity entity) {
        if (!miniMob(entity)) {
            return;
        }
        ResourceKey<Level> key = pocket.dimension();
        Map<UUID, CompoundTag> mobs = MINI_MOBS.computeIfAbsent(key, ignored -> new LinkedHashMap<>());
        Entity.RemovalReason reason = entity.getRemovalReason();
        if ((reason == null || reason == Entity.RemovalReason.UNLOADED_TO_CHUNK)
                && !((LivingEntity) entity).isDeadOrDying() && entity.getBoundingBox().intersects(INTERIOR)) {
            if (mobs.containsKey(entity.getUUID()) || mobs.size() < MAX_MOBS) {
                CompoundTag tag = saveMob(entity);
                if (tag != null && !tag.equals(mobs.put(entity.getUUID(), tag))) {
                    MINI_MOBS_DIRTY.add(key);
                }
            }
        } else if (reason != Entity.RemovalReason.UNLOADED_WITH_PLAYER) {
            if (mobs.remove(entity.getUUID()) != null) {
                MINI_MOBS_DIRTY.add(key);
            }
        }
    }

    static void clear() {
        MINI_LIGHT.clear();
        MINI_VERSION.clear();
        MINI_ENTITIES.clear();
        MINI_ENTITIES_DUE.clear();
        MINI_ENTITIES_NEXT.clear();
        MINI_MOBS.clear();
        MINI_MOBS_DIRTY.clear();
        MINI_MOBS_NEXT.clear();
        MOBS_READY.clear();
        READ_PENDING.clear();
        READ_RETRY_AFTER.clear();
        SENT.clear();
    }

    /** Les 4 chunks de l'interieur du bocal ont leurs entites chargees (simulees ou non). */
    private static boolean entitiesLoaded(ServerLevel pocket) {
        for (int cx = 0; cx <= 1; cx++) {
            for (int cz = 0; cz <= 1; cz++) {
                // areEntitiesLoaded peut encore etre vrai apres la fin du suivi, jusqu'a la sauvegarde effective.
                var chunk = pocket.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null || !chunk.getFullStatus().isOrAfter(FullChunkStatus.FULL)
                        || !pocket.areEntitiesLoaded(ChunkPos.asLong(cx, cz))) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Lecture seule : niveau 33 (FULL) sur les 4 chunks, avec les voisins necessaires au chargement. Ces tickets ne
     * demandent ni les ticks d'entites (31), ni ceux des blocs (32), ni le maintien de la dimension active. Une autre
     * source de tickets peut naturellement simuler la poche. Retires apres lecture, sinon expiration (READ_TICKET).
     */
    private static void requestRead(ServerLevel pocket) {
        for (int cx = 0; cx <= 1; cx++) {
            for (int cz = 0; cz <= 1; cz++) {
                ChunkPos chunk = new ChunkPos(cx, cz);
                pocket.getChunkSource().addRegionTicket(READ_TICKET, chunk, 0, chunk);
            }
        }
    }

    private static void releaseRead(ServerLevel pocket) {
        for (int cx = 0; cx <= 1; cx++) {
            for (int cz = 0; cz <= 1; cz++) {
                ChunkPos chunk = new ChunkPos(cx, cz);
                pocket.getChunkSource().removeRegionTicket(READ_TICKET, chunk, 0, chunk);
            }
        }
    }

    /** Relit tous les mobs d'une poche chargee (au plus MAX_MOBS). */
    private static void readMobs(ServerLevel pocket) {
        Map<UUID, CompoundTag> mobs = new LinkedHashMap<>();
        for (LivingEntity mob : pocket.getEntitiesOfClass(LivingEntity.class, INTERIOR,
                entity -> miniMob(entity) && !entity.isRemoved() && !entity.isDeadOrDying())) {
            if (mobs.size() >= MAX_MOBS) {
                break;
            }
            CompoundTag tag = saveMob(mob);
            if (tag != null) {
                mobs.put(mob.getUUID(), tag);
            }
        }
        if (!mobs.equals(MINI_MOBS.put(pocket.dimension(), mobs))) {
            MINI_MOBS_DIRTY.add(pocket.dimension());
        }
    }

    /** Mob de la miniature : pas les joueurs, ni les porte-armures (avec les decorations). */
    private static boolean miniMob(Entity entity) {
        return entity instanceof LivingEntity && !(entity instanceof Player) && !(entity instanceof ArmorStand);
    }

    /**
     * Sauvegarde d'un mob pour la miniature. saveAsPassenger : aussi pour un mob monte (save refuse les passagers). On
     * garde l'UUID (le client reutilise sa copie et ses animations), OnGround (ailes) et Health (queue des loups).
     */
    private static CompoundTag saveMob(Entity mob) {
        CompoundTag tag = new CompoundTag();
        try {
            if (!mob.saveAsPassenger(tag)) {
                return null;
            }
        } catch (Exception e) {
            return null;
        }
        for (String key : MOB_VOLATILE) {
            tag.remove(key);
        }
        // saveWithoutId emploie X/Z du vehicule pour les passagers ; la miniature doit garder leur vraie position.
        ListTag position = new ListTag();
        position.add(DoubleTag.valueOf(mob.getX()));
        position.add(DoubleTag.valueOf(mob.getY()));
        position.add(DoubleTag.valueOf(mob.getZ()));
        tag.put("Pos", position);
        return tag;
    }

    /** Donnees du serveur seulement, ou qui changent toutes seules, ou lourdes (echanges des villageois...). */
    private static final String[] MOB_VOLATILE = {"Motion", "FallDistance", "Fire", "Air", "PortalCooldown", "HurtTime",
            "HurtByTimestamp", "DeathTime", "AbsorptionAmount", "Brain", "FallFlying", "Passengers", "Offers", "Gossips",
            "Inventory", "ArmorDropChances", "HandDropChances", "LastGossipDecay", "LastRestock", "RestocksToday", "Xp"};

    /** Decorations et mobs d'un bocal, dans un seul paquet (cles "entities" et "mobs"). */
    private static CompoundTag payload(ResourceKey<Level> key) {
        CompoundTag decorations = MINI_ENTITIES.get(key);
        CompoundTag tag = decorations != null ? decorations.copy() : new CompoundTag();
        if (!tag.contains("entities", Tag.TAG_LIST)) {
            tag.put("entities", new ListTag());
        }
        ListTag mobs = new ListTag();
        Map<UUID, CompoundTag> known = MINI_MOBS.get(key);
        if (known != null) {
            mobs.addAll(known.values());
        }
        tag.put("mobs", mobs);
        return tag;
    }

    private static boolean empty(CompoundTag tag) {
        return tag.getList("entities", Tag.TAG_COMPOUND).isEmpty() && tag.getList("mobs", Tag.TAG_COMPOUND).isEmpty();
    }

    private static void send(MinecraftServer server, ResourceKey<Level> key) {
        CompoundTag tag = payload(key);
        // Rien a montrer et rien d'envoye avant : inutile d'envoyer une liste vide.
        if (empty(tag) && !SENT.contains(key)) {
            return;
        }
        SENT.add(key);
        MiniEntitiesPayload payload = new MiniEntitiesPayload(key.location(), tag);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.connection.hasChannel(MiniEntitiesPayload.TYPE)) {
                PacketDistributor.sendToPlayer(player, payload);
            }
        }
    }

}

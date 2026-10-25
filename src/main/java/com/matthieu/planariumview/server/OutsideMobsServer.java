package com.matthieu.planariumview.server;

import com.matthieu.planariumview.network.OutsideMobsPayload;
import com.matthieu.planariumview.network.ViewPrefsPayload;
import com.matthieu.planariumview.mixin.SynchedEntityDataAccessor;

import com.mojang.logging.LogUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mobs du rayon detaille, envoyes aux joueurs qui ont l'option "Mobs du dehors" (voir OutsideMobs cote client).
 *
 * Un flux a part des blocs : leger (positions et rotations) et frequent (10 fois par seconde), interpole par le client.
 * L'apparence (donnees synchronisees, equipement) n'est envoyee qu'a la premiere apparition puis quand elle change,
 * d'apres nos propres copies de ce qui a ete envoye : on ne touche jamais aux changements que Minecraft garde pour
 * les vrais clients (pas de packDirty).
 */
public final class OutsideMobsServer {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Un envoi tous les 2 ticks (10 par seconde). */
    private static final int INTERVAL = OutsideMobsPayload.UPDATE_INTERVAL;
    /** Un envoi complet (tous les mobs suivis) toutes les 2 secondes, par securite. */
    private static final int FULL_INTERVAL = 40;
    /** Apparence verifiee au plus toutes les 10 ticks par mob. */
    private static final int APPEARANCE_INTERVAL = 10;
    /** Les plus proches seulement (fermes de mobs). */
    private static final int MAX_MOBS = 64;
    /** Marge hors champ : les mobs terminent leurs trajets avant de quitter la zone simulee. */
    public static final int SIMULATION_MARGIN = 16;
    /** Champs retires du NBT de creation : inutiles a l'affichage, souvent gros, ou qui changent sans arret. */
    private static final String[] STRIPPED = {"UUID", "Motion", "FallDistance", "Fire", "Air", "OnGround", "PortalCooldown",
            "HurtTime", "HurtByTimestamp", "DeathTime", "AbsorptionAmount", "Brain", "FallFlying", "Health", "Passengers",
            "Leash", "Offers", "Gossips", "Inventory", "Attributes", "active_effects", "Pos", "Rotation"};

    private static final Map<UUID, ViewPrefsPayload> PREFS = new ConcurrentHashMap<>();
    private static final Map<UUID, State> STATES = new HashMap<>();

    /** Ce qui a ete envoye a un joueur, par mob. */
    private static final class State {
        ResourceKey<Level> pocket;
        ResourceKey<Level> source;
        BlockPos origin;
        final Map<Integer, Sent> sent = new HashMap<>();
        int lastFull = Integer.MIN_VALUE / 2;
    }

    private static final class Sent {
        float x, y, z;
        float yRot, xRot, headRot, bodyRot;
        byte[] data = new byte[0];
        byte[] equipment = new byte[0];
        int checkedAt = Integer.MIN_VALUE / 2;
    }

    private OutsideMobsServer() {
    }

    /** Le joueur veut-il les mobs du dehors ? (sans message : non, comme une ancienne version) */
    public static boolean wants(UUID player) {
        ViewPrefsPayload prefs = PREFS.get(player);
        return prefs != null && prefs.mobs();
    }

    /** Nouvelles options du client. Vrai si l'affichage des mobs a change (la zone active doit etre revue). */
    public static boolean setPrefs(UUID player, ViewPrefsPayload prefs) {
        ViewPrefsPayload previous = PREFS.put(player, prefs);
        boolean mobsChanged = previous == null ? prefs.mobs() : previous.mobs() != prefs.mobs();
        if (previous == null || mobsChanged || previous.resync() != prefs.resync()) {
            // Tout renvoyer, avec de quoi recreer chaque mob.
            STATES.remove(player);
        }
        return mobsChanged;
    }

    /** Le joueur n'est plus suivi (sorti du bocal, deconnecte, vue OFF). */
    public static void forget(UUID player) {
        STATES.remove(player);
    }

    public static void logout(UUID player) {
        STATES.remove(player);
        PREFS.remove(player);
    }

    public static void clearAll() {
        STATES.clear();
        PREFS.clear();
    }

    /**
     * Chaque tick, pour un joueur suivi dans son bocal. {@code capturedCenter} et {@code radius} : centre et rayon de la
     * derniere photo envoyee (les positions sont relatives a son origine, comme ses blocs).
     */
    static void tick(ServerPlayer player, ServerLevel source, ResourceKey<Level> pocket, BlockPos capturedCenter,
                     int radius, boolean ready, boolean flying) {
        UUID id = player.getUUID();
        if (!wants(id) || !ready || radius < 0 || flying || !player.connection.hasChannel(OutsideMobsPayload.TYPE)) {
            STATES.remove(id);
            return;
        }
        int now = player.server.getTickCount();
        if ((now + (id.hashCode() & 3)) % INTERVAL != 0) return;
        State state = STATES.computeIfAbsent(id, ignored -> new State());
        BlockPos origin = capturedCenter.offset(-radius, -radius, -radius);
        if (!pocket.equals(state.pocket) || !source.dimension().equals(state.source) || !origin.equals(state.origin)) {
            state.sent.clear();
            state.lastFull = Integer.MIN_VALUE / 2;
        }
        state.pocket = pocket;
        state.source = source.dimension();
        state.origin = origin;
        int size = radius * 2 + 1;
        AABB box = new AABB(origin.getX(), origin.getY(), origin.getZ(),
                origin.getX() + size, origin.getY() + size, origin.getZ() + size);
        List<LivingEntity> mobs = source.getEntitiesOfClass(LivingEntity.class, box.inflate(SIMULATION_MARGIN),
                mob -> mob.isAlive() && !(mob instanceof Player) && !(mob instanceof ArmorStand));
        keepAwake(mobs);
        // La marge sert a la simulation. Seuls les mobs qui touchent la photo sont envoyes et dessines.
        mobs.removeIf(mob -> !shown(mob) || !mob.getBoundingBox().intersects(box));
        if (mobs.size() > MAX_MOBS) {
            double cx = capturedCenter.getX() + 0.5, cy = capturedCenter.getY() + 0.5, cz = capturedCenter.getZ() + 0.5;
            mobs.sort((a, b) -> Double.compare(a.distanceToSqr(cx, cy, cz), b.distanceToSqr(cx, cy, cz)));
            mobs = mobs.subList(0, MAX_MOBS);
        }
        boolean full = now - state.lastFull >= FULL_INTERVAL;
        if (full) state.lastFull = now;
        List<OutsideMobsPayload.Entry> entries = new ArrayList<>();
        Set<Integer> present = new HashSet<>();
        for (LivingEntity mob : mobs) {
            try {
                OutsideMobsPayload.Entry entry = entry(mob, origin, state, now, full);
                present.add(mob.getId());
                if (entry != null) entries.add(entry);
            } catch (RuntimeException | LinkageError e) {
                LOGGER.debug("[Planarium Panorama] Mob du dehors ignore : {}", EntityType.getKey(mob.getType()), e);
            }
        }
        List<Integer> gone = new ArrayList<>();
        state.sent.keySet().removeIf(mobId -> {
            if (present.contains(mobId)) return false;
            gone.add(mobId);
            return true;
        });
        if (!full && entries.isEmpty() && gone.isEmpty()) return;
        int[] removed = new int[gone.size()];
        for (int i = 0; i < removed.length; i++) removed[i] = gone.get(i);
        PacketDistributor.sendToPlayer(player, new OutsideMobsPayload(pocket.location(), PREFS.get(id).resync(), origin, now, full, entries,
                removed));
    }

    /** Mobs montres : vivants, visibles, ni joueurs (phase 2) ni porte-armures (deja dans les decorations). */
    private static boolean shown(LivingEntity entity) {
        return entity.isAlive() && !entity.isInvisible() && !entity.isSpectator()
                && !(entity instanceof Player) && !(entity instanceof ArmorStand);
    }

    /**
     * Un ticket fait tourner les entites, mais sans joueur proche le compteur d'inactivite monte quand meme :
     * RandomStrollGoal refuse alors toute nouvelle promenade apres 100 ticks. Les mobs observes restent actifs,
     * y compris ceux au-dela de la limite de rendu, sans changer leur persistance ni ajouter de faux joueur.
     */
    private static void keepAwake(List<? extends LivingEntity> mobs) {
        for (LivingEntity mob : mobs) mob.setNoActionTime(0);
    }

    /** Ce qu'il faut envoyer pour ce mob, ou null si rien n'a change (et que ce n'est pas un envoi complet). */
    private static OutsideMobsPayload.Entry entry(LivingEntity mob, BlockPos origin, State state, int now, boolean full) {
        Sent sent = state.sent.get(mob.getId());
        CompoundTag create = null;
        if (sent == null) {
            create = createTag(mob);
            if (create == null) return null;
            sent = new Sent();
            state.sent.put(mob.getId(), sent);
        }
        float x = (float) (mob.getX() - origin.getX());
        float y = (float) (mob.getY() - origin.getY());
        float z = (float) (mob.getZ() - origin.getZ());
        float yRot = mob.getYRot();
        float xRot = mob.getXRot();
        float headRot = mob.getYHeadRot();
        float bodyRot = mob.yBodyRot;
        byte[] data = new byte[0];
        byte[] equipment = new byte[0];
        if (create != null || now - sent.checkedAt >= APPEARANCE_INTERVAL) {
            sent.checkedAt = now;
            byte[] currentData = encodeData(mob);
            if (!Arrays.equals(currentData, sent.data)) {
                data = currentData;
                sent.data = currentData;
            }
            byte[] currentEquipment = encodeEquipment(mob);
            if (!Arrays.equals(currentEquipment, sent.equipment)) {
                equipment = currentEquipment;
                sent.equipment = currentEquipment;
            }
        }
        if (full) {
            data = sent.data;
            equipment = sent.equipment;
        }
        boolean moved = Math.abs(x - sent.x) > 1.0F / 64 || Math.abs(y - sent.y) > 1.0F / 64 || Math.abs(z - sent.z) > 1.0F / 64
                || yRot != sent.yRot || xRot != sent.xRot || headRot != sent.headRot || bodyRot != sent.bodyRot;
        if (!full && create == null && !moved && data.length == 0 && equipment.length == 0) return null;
        sent.x = x;
        sent.y = y;
        sent.z = z;
        sent.yRot = yRot;
        sent.xRot = xRot;
        sent.headRot = headRot;
        sent.bodyRot = bodyRot;
        return new OutsideMobsPayload.Entry(mob.getId(), x, y, z, yRot, xRot, headRot, bodyRot, create, data, equipment);
    }

    /** Type et NBT allege pour recreer le mob chez le client, ou null si ce type ne s'enregistre pas. */
    private static CompoundTag createTag(LivingEntity mob) {
        ResourceLocation type = EntityType.getKey(mob.getType());
        if (type == null || !mob.getType().canSerialize()) return null;
        CompoundTag tag = new CompoundTag();
        mob.saveWithoutId(tag);
        for (String key : STRIPPED) tag.remove(key);
        tag.putString("id", type.toString());
        return tag;
    }

    /** Toutes les valeurs, y compris les retours au defaut, sans modifier les marqueurs dirty de Minecraft. */
    private static byte[] encodeData(LivingEntity mob) {
        return encodeData(mob.getEntityData(), mob.registryAccess());
    }

    private static byte[] encodeData(SynchedEntityData data, net.minecraft.core.RegistryAccess registry) {
        SynchedEntityData.DataItem<?>[] values = ((SynchedEntityDataAccessor) data).planariumview$getItems();
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), registry);
        try {
            for (SynchedEntityData.DataItem<?> value : values) {
                if (value != null) value.value().write(buf);
            }
            buf.writeByte(255);
            return bytes(buf);
        } finally {
            buf.release();
        }
    }

    /** Tout l'equipement (mains, armure, armure du corps), dans l'ordre d'EquipmentSlot. */
    private static byte[] encodeEquipment(LivingEntity mob) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), mob.registryAccess());
        try {
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, mob.getItemBySlot(slot));
            }
            return bytes(buf);
        } finally {
            buf.release();
        }
    }

    private static byte[] bytes(ByteBuf buf) {
        byte[] out = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), out);
        return out;
    }
}

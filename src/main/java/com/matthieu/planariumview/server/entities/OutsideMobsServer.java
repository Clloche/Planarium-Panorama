package com.matthieu.planariumview.server.entities;

import com.matthieu.planariumview.network.entities.OutsideMobsPayload;
import com.matthieu.planariumview.network.entities.MobAnimations;
import com.matthieu.planariumview.network.entities.MobEnvironment;
import com.matthieu.planariumview.network.entities.ViewPrefsPayload;
import com.matthieu.planariumview.mixin.entities.SynchedEntityDataAccessor;
import com.matthieu.planariumview.mixin.entities.BoatAccessor;

import com.mojang.logging.LogUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Squid;
import net.minecraft.world.entity.animal.WaterAnimal;
import net.minecraft.world.entity.animal.Turtle;
import net.minecraft.world.entity.animal.axolotl.Axolotl;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.entity.vehicle.MinecartTNT;
import net.minecraft.world.entity.vehicle.VehicleEntity;
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
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mobs et vehicules du rayon detaille : options independantes, meme flux de positions (voir OutsideMobs cote client).
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
    private static long eventSequence;

    /** Ce qui a ete envoye a un joueur, par mob. */
    private static final class State {
        ResourceKey<Level> pocket;
        ResourceKey<Level> source;
        BlockPos origin;
        final Map<Integer, Sent> sent = new HashMap<>();
        final MobEventQueue events = new MobEventQueue();
        int lastFull = Integer.MIN_VALUE / 2;
    }

    private static final class Sent {
        float x, y, z;
        float yRot, xRot, headRot, bodyRot;
        int vehicleId = -1;
        int seat;
        OutsideMobsPayload.VehicleState visual;
        MobEnvironment environment;
        boolean dying;
        byte[] data = new byte[0];
        byte[] equipment = new byte[0];
        int checkedAt = Integer.MIN_VALUE / 2;
    }

    private OutsideMobsServer() {
    }

    /** Le joueur veut-il des mobs ou des vehicules ? Leur zone doit alors rester active. */
    public static boolean wants(UUID player) {
        ViewPrefsPayload prefs = PREFS.get(player);
        return prefs != null && (prefs.mobs() || prefs.vehicles());
    }

    /** Nouvelles options du client. Vrai si l'une des options a change (la zone active doit etre revue). */
    public static boolean setPrefs(UUID player, ViewPrefsPayload prefs) {
        ViewPrefsPayload previous = PREFS.put(player, prefs);
        boolean changed = previous == null ? prefs.mobs() || prefs.vehicles()
                : previous.mobLimit() != prefs.mobLimit() || previous.vehicles() != prefs.vehicles();
        if (previous == null || changed || previous.resync() != prefs.resync()) {
            // Tout renvoyer, avec de quoi recreer chaque mob.
            STATES.remove(player);
        }
        return changed;
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
        OBSERVED.clear();
        eventSequence = 0;
    }

    /** Observe accepted vanilla broadcasts, even when no real player is tracking the source mob. */
    public static void capture(Entity entity, Packet<?> packet) {
        if (STATES.isEmpty() || !(entity instanceof LivingEntity living) || entity instanceof Player
                || entity instanceof ArmorStand || !(entity.level() instanceof ServerLevel source)) return;
        MobAnimations.Kind kind;
        boolean offHand = false;
        int duration = 0;
        if (packet instanceof ClientboundDamageEventPacket) {
            kind = MobAnimations.Kind.HURT;
            duration = 10;
        } else if (packet instanceof ClientboundAnimatePacket animation && (animation.getAction() == 0 || animation.getAction() == 3)) {
            kind = MobAnimations.Kind.SWING;
            offHand = animation.getAction() == 3;
            duration = Math.clamp(living.getCurrentSwingDuration(), 1, MobAnimations.MAX_DURATION);
        } else if (packet instanceof ClientboundEntityEventPacket event && (event.getEventId() == 3 || event.getEventId() == 60)) {
            kind = event.getEventId() == 3 ? MobAnimations.Kind.DEATH : MobAnimations.Kind.POOF;
        } else return;
        emit(entity, source, kind, offHand, duration);
    }

    public static void captureInk(Squid squid) {
        if (!STATES.isEmpty() && squid.level() instanceof ServerLevel source) {
            emit(squid, source, MobAnimations.Kind.INK, false, 0);
        }
    }

    private static void emit(Entity entity, ServerLevel source, MobAnimations.Kind kind, boolean offHand, int duration) {
        MobAnimations.Event event = new MobAnimations.Event(++eventSequence, entity.getId(), source.getServer().getTickCount(),
                kind, offHand, duration);
        for (State state : STATES.values()) {
            if (source.dimension().equals(state.source) && state.sent.containsKey(entity.getId())) state.events.add(event);
        }
    }

    /**
     * Chaque tick, pour un joueur suivi dans son bocal. {@code capturedCenter} et {@code radius} : centre et rayon de la
     * derniere photo envoyee (les positions sont relatives a son origine, comme ses blocs).
     */
    public static void tick(ServerPlayer player, ServerLevel source, ResourceKey<Level> pocket, BlockPos capturedCenter,
                     int radius, boolean ready, boolean flying) {
        UUID id = player.getUUID();
        // Le monde ne laisse pas le Planarium faire vivre le dehors : pas de mobs ni de vehicules (ils seraient figes).
        if (!wants(id) || !com.matthieu.planariumview.config.Config.outsideLive() || !ready || radius < 0 || flying
                || !player.connection.hasChannel(OutsideMobsPayload.TYPE)) {
            STATES.remove(id);
            return;
        }
        int now = player.server.getTickCount();
        if ((now + (id.hashCode() & 3)) % INTERVAL != 0) return;
        State state = STATES.computeIfAbsent(id, ignored -> new State());
        BlockPos origin = capturedCenter.offset(-radius, -radius, -radius);
        if (!pocket.equals(state.pocket) || !source.dimension().equals(state.source)) state.events.clear();
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
        ViewPrefsPayload prefs = PREFS.get(id);
        AABB simulated = box.inflate(SIMULATION_MARGIN);
        List<Entity> candidates = new ArrayList<>();
        // Les index par classe evitent de parcourir aussi les objets au sol et les projectiles a chaque envoi.
        if (prefs.mobs()) {
            candidates.addAll(source.getEntitiesOfClass(LivingEntity.class, simulated,
                    entity -> !entity.isRemoved() && (entity.isAlive() || entity.isDeadOrDying()) && enabled(entity, prefs)));
            observe(candidates, now);
        }
        if (prefs.vehicles()) {
            candidates.addAll(source.getEntitiesOfClass(VehicleEntity.class, simulated, entity -> entity.isAlive() && enabled(entity, prefs)));
        }
        candidates.removeIf(entity -> entity.isInvisible() || entity.isSpectator());
        List<Entity> mobs = select(candidates, box, capturedCenter, prefs.mobLimit());
        Set<Integer> selected = new HashSet<>();
        for (Entity entity : mobs) selected.add(entity.getId());
        boolean resetAnimations = state.events.resetRequired();
        boolean full = resetAnimations || now - state.lastFull >= FULL_INTERVAL;
        if (full) state.lastFull = now;
        List<OutsideMobsPayload.Entry> entries = new ArrayList<>();
        Set<Integer> present = new HashSet<>();
        for (Entity mob : mobs) {
            try {
                OutsideMobsPayload.Entry entry = entry(mob, origin, state, now, full, selected);
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
        // Empty updates are also a clock/pose heartbeat: an immobile mob still warms up and animates.
        int[] removed = new int[gone.size()];
        for (int i = 0; i < removed.length; i++) removed[i] = gone.get(i);
        PacketDistributor.sendToPlayer(player, new OutsideMobsPayload(pocket.location(), PREFS.get(id).resync(), origin, now, full, entries,
                removed, state.events.drain(), resetAnimations));
    }

    /** Choix independants : les joueurs et les decorations ne font pas partie de ce flux. */
    private static boolean enabled(Entity entity, ViewPrefsPayload prefs) {
        if (entity instanceof Boat || entity instanceof AbstractMinecart) return prefs.vehicles();
        return prefs.mobs() && entity instanceof LivingEntity && !(entity instanceof Player) && !(entity instanceof ArmorStand)
                && (prefs.vehicles() || !(entity.getVehicle() instanceof VehicleEntity));
    }

    /** Garde les groupes monture/passagers ensemble, dans la limite de mobs du joueur et de 64 entites au total. */
    private static List<Entity> select(List<Entity> candidates, AABB box, BlockPos center, int mobLimit) {
        Set<Entity> available = new HashSet<>(candidates);
        Map<Entity, List<Entity>> groups = new HashMap<>();
        for (Entity entity : candidates) {
            Entity root = entity;
            while (root.getVehicle() != null && available.contains(root.getVehicle())) root = root.getVehicle();
            groups.computeIfAbsent(root, ignored -> new ArrayList<>()).add(entity);
        }
        List<Entity> roots = new ArrayList<>(groups.keySet());
        double cx = center.getX() + 0.5, cy = center.getY() + 0.5, cz = center.getZ() + 0.5;
        roots.sort(java.util.Comparator.comparingDouble((Entity entity) -> entity.distanceToSqr(cx, cy, cz))
                .thenComparingInt(Entity::getId));
        List<Entity> selected = new ArrayList<>();
        int selectedMobs = 0;
        for (Entity root : roots) {
            List<Entity> group = groups.get(root);
            if (selected.size() + group.size() > MAX_MOBS || group.stream().noneMatch(entity -> entity.getBoundingBox().intersects(box))) continue;
            int groupMobs = (int) group.stream().filter(entity -> entity instanceof LivingEntity).count();
            if (selectedMobs + groupMobs > mobLimit) continue;
            selected.addAll(group);
            selectedMobs += groupMobs;
        }
        return selected;
    }

    /**
     * Mobs observes depuis un bocal (zone simulee, marge comprise) et tick de la derniere observation. Un ticket fait
     * tourner les entites, mais sans joueur proche le compteur d'inactivite monte quand meme : RandomStrollGoal refuse
     * alors toute nouvelle promenade apres 100 ticks. Pour ces mobs, seul ce refus est leve (RandomStrollGoalMixin) :
     * le compteur lui-meme ne change pas, et le despawn aleatoire (qui s'en sert aussi) continue normalement.
     * Remettre le compteur a zero empechait les monstres de disparaitre quand un autre joueur etait dans ce monde.
     */
    private static final Map<Entity, Integer> OBSERVED = new WeakHashMap<>();
    /** Un mob reste "observe" ce temps apres le dernier passage (plusieurs envois par seconde). */
    private static final int OBSERVED_TICKS = 40;

    private static void observe(List<? extends Entity> mobs, int now) {
        for (Entity mob : mobs) if (mob instanceof LivingEntity) OBSERVED.put(mob, now);
    }

    /** Vrai si ce mob est regarde depuis un bocal en ce moment (voir RandomStrollGoalMixin). Fil du serveur. */
    public static boolean observed(Entity mob) {
        MinecraftServer server = mob.getServer();
        if (server == null || OBSERVED.isEmpty()) return false;
        Integer at = OBSERVED.get(mob);
        if (at == null) return false;
        int age = server.getTickCount() - at;
        if (age >= 0 && age <= OBSERVED_TICKS) return true;
        OBSERVED.remove(mob);
        return false;
    }

    /** Ce qu'il faut envoyer pour ce mob, ou null si rien n'a change (et que ce n'est pas un envoi complet). */
    private static OutsideMobsPayload.Entry entry(Entity mob, BlockPos origin, State state, int now, boolean full, Set<Integer> selected) {
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
        float headRot = mob instanceof LivingEntity living ? living.getYHeadRot() : yRot;
        float bodyRot = mob instanceof LivingEntity living ? living.yBodyRot : yRot;
        Entity vehicle = mob.getVehicle();
        int vehicleId = vehicle != null && selected.contains(vehicle.getId()) ? vehicle.getId() : -1;
        int seat = vehicleId >= 0 ? vehicle.getPassengers().indexOf(mob) : 0;
        OutsideMobsPayload.VehicleState visual = visual(mob);
        MobEnvironment environment = environment(mob);
        boolean dying = mob instanceof LivingEntity living && living.isDeadOrDying();
        MobAnimations.Snapshot animation = mob instanceof LivingEntity living && (create != null || full || dying != sent.dying)
                ? snapshot(living) : null;
        byte[] data = new byte[0];
        byte[] equipment = new byte[0];
        // Puffing fish, playing-dead axolotls and darkened glow squids can change between appearance samples.
        boolean quickAppearance = mob instanceof VehicleEntity || mob instanceof WaterAnimal || mob instanceof Turtle || mob instanceof Axolotl;
        if (create != null || now - sent.checkedAt >= (quickAppearance ? INTERVAL : APPEARANCE_INTERVAL)) {
            sent.checkedAt = now;
            byte[] currentData = encodeData(mob);
            if (!Arrays.equals(currentData, sent.data)) {
                data = currentData;
                sent.data = currentData;
            }
            byte[] currentEquipment = mob instanceof LivingEntity living ? encodeEquipment(living) : new byte[0];
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
        boolean changed = vehicleId != sent.vehicleId || seat != sent.seat || !Objects.equals(visual, sent.visual)
                || !Objects.equals(environment, sent.environment);
        if (!full && create == null && !moved && !changed && data.length == 0 && equipment.length == 0 && animation == null) return null;
        sent.x = x;
        sent.y = y;
        sent.z = z;
        sent.yRot = yRot;
        sent.xRot = xRot;
        sent.headRot = headRot;
        sent.bodyRot = bodyRot;
        sent.vehicleId = vehicleId;
        sent.seat = seat;
        sent.visual = visual;
        sent.environment = environment;
        sent.dying = dying;
        return new OutsideMobsPayload.Entry(mob.getId(), x, y, z, yRot, xRot, headRot, bodyRot,
                vehicleId, seat, visual, create, data, equipment, animation, environment);
    }

    private static MobEnvironment environment(Entity entity) {
        if (!(entity instanceof LivingEntity)) return null;
        var velocity = entity.getDeltaMovement();
        MobEnvironment.SquidPose pose = entity instanceof Squid squid
                ? new MobEnvironment.SquidPose(squid.xBodyRot, squid.zBodyRot, squid.tentacleAngle) : null;
        return new MobEnvironment(entity.isInWater(), entity.isUnderWater(), entity.isInWaterOrBubble(), entity.onGround(),
                (float) velocity.x, (float) velocity.y, (float) velocity.z, pose);
    }

    private static MobAnimations.Snapshot snapshot(LivingEntity entity) {
        return new MobAnimations.Snapshot(Math.clamp(entity.hurtTime, 0, MobAnimations.MAX_DURATION),
                Math.clamp(entity.hurtDuration, 0, MobAnimations.MAX_DURATION), entity.isDeadOrDying(),
                Math.clamp(entity.deathTime, 0, MobAnimations.MAX_DURATION), entity.swinging,
                entity.swingingArm == InteractionHand.OFF_HAND, Math.clamp(entity.swingTime, -1, MobAnimations.MAX_DURATION),
                Math.clamp(entity.getCurrentSwingDuration(), 1, MobAnimations.MAX_DURATION));
    }

    private static OutsideMobsPayload.VehicleState visual(Entity entity) {
        if (entity instanceof Boat boat) {
            return new OutsideMobsPayload.VehicleState(boat.getRowingTime(0, 1), boat.getRowingTime(1, 1),
                    ((BoatAccessor) boat).planariumview$getBubbleTime(), boat.isUnderWater(), -1);
        }
        if (entity instanceof MinecartTNT tnt) return new OutsideMobsPayload.VehicleState(0, 0, 0, false, tnt.getFuse());
        return null;
    }

    /** Type et NBT allege pour recreer le mob chez le client, ou null si ce type ne s'enregistre pas. */
    private static CompoundTag createTag(Entity mob) {
        ResourceLocation type = EntityType.getKey(mob.getType());
        if (type == null || !mob.getType().canSerialize()) return null;
        CompoundTag tag = new CompoundTag();
        mob.saveWithoutId(tag);
        for (String key : STRIPPED) tag.remove(key);
        if (mob instanceof VehicleEntity) {
            for (String key : List.of("Items", "LootTable", "LootTableSeed", "Command", "LastOutput", "LastExecution",
                    "SuccessCount", "TrackOutput", "SpawnPotentials")) tag.remove(key);
        }
        tag.putString("id", type.toString());
        return tag;
    }

    /** Toutes les valeurs, y compris les retours au defaut, sans modifier les marqueurs dirty de Minecraft. */
    private static byte[] encodeData(Entity mob) {
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

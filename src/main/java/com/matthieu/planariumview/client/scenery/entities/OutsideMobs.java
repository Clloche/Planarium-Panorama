package com.matthieu.planariumview.client.scenery.entities;

import com.matthieu.planariumview.client.scenery.ClientView;
import com.matthieu.planariumview.client.scenery.FlightView;
import com.matthieu.planariumview.client.scenery.SubLevelView;
import com.matthieu.planariumview.client.sound.entities.OutsideMinecartSound;
import com.matthieu.planariumview.config.Config;
import com.matthieu.planariumview.network.entities.OutsideMobsPayload;
import com.matthieu.planariumview.network.entities.MobAnimations;
import com.matthieu.planariumview.network.entities.MobEnvironment;
import com.matthieu.planariumview.mixin.entities.SceneryEntityAccessor;

import com.google.common.collect.ImmutableList;
import com.mojang.logging.LogUtils;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Vector3d;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Mobs et vehicules du dehors (options independantes, flux commun) : des copies jamais ajoutees a un monde, sans IA,
 * placees chaque image entre les deux derniers etats recus, environ 150 ms en arriere (le serveur envoie 10 fois par
 * seconde). La marche est animee chaque tick d'apres le deplacement interpole, comme Minecraft le fait pour les
 * entites lointaines.
 *
 * Les etats sont gardes en coordonnees du monde d'origine : une nouvelle photo du decor (autre origine) ne fait pas
 * sauter les mobs, ils sont ramenes a l'origine de la photo affichee au moment du dessin.
 */
public final class OutsideMobs {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Retard du dessin sur le dernier etat recu, en ticks : un intervalle d'envoi et un de marge. */
    private static final double DELAY = OutsideMobsPayload.UPDATE_INTERVAL + 1.0;
    /** Au-dela de ce saut entre deux etats (en blocs), le mob est place directement (teleportation). */
    private static final double TELEPORT = 4.0;
    /** Etats gardes par mob. */
    private static final int MAX_STATES = 6;
    /** Mob sans nouvelle depuis ce temps (ticks) : retire, par securite. */
    private static final int STALE_TICKS = 100;

    private record State(int tick, double x, double y, double z, float yRot, float xRot, float headRot, float bodyRot,
                         int vehicleId, int seat, OutsideMobsPayload.VehicleState visual, MobEnvironment environment) {
    }

    private static final class Copy {
        final Entity entity;
        final ArrayDeque<State> states = new ArrayDeque<>();
        int lastUpdate;
        int removeAt = Integer.MAX_VALUE;
        final MobAnimationTimeline livingAnimation = new MobAnimationTimeline();
        /** Position au dernier tick (monde d'origine), pour l'animation de marche. */
        double tickX, tickY, tickZ;
        boolean placed;
        /** Montre seulement quand ses etats encadrent l'instant dessine (sinon il apparait puis s'arrete). */
        boolean ready;
        boolean linked;
        State rendered;
        final OutsideVehicles.Animation animation = new OutsideVehicles.Animation();
        final OutsideWings wings = new OutsideWings();
        double soundSpeed;
        OutsideMinecartSound sound;
        int soundStarted;

        Copy(Entity entity) {
            this.entity = entity;
        }

        void stopSound() {
            if (sound != null) {
                sound.end();
                sound = null;
            }
        }
    }

    private static final Map<Integer, Copy> COPIES = new HashMap<>();
    /** Types de mobs dont la creation ou le dessin a echoue : plus essayes. */
    private static final Set<ResourceLocation> BROKEN = new HashSet<>();
    /** Copies volontairement ignorees : leurs deltas ne doivent pas provoquer des resynchronisations en boucle. */
    private static final Set<Integer> IGNORED = new HashSet<>();
    private static Level level;
    private static ResourceLocation pocket;
    private static int clientTick;
    private static int lastPacketTick = Integer.MIN_VALUE;
    /** Tick du serveur moins tick du client, estime d'apres les arrivees (voir receive). */
    private static double offset = Double.NaN;
    /** Recalage vise et precedent : l'horloge avance doucement, jamais par saut a l'arrivee d'un paquet. */
    private static double targetOffset = Double.NaN;
    private static double previousOffset = Double.NaN;
    private static final double MAX_CLOCK_CORRECTION = 0.1;
    /** Augmente quand on a besoin que le serveur renvoie tout (voir ViewPrefsPayload). */
    private static int resync;
    private static long lastResync = Long.MIN_VALUE / 2;
    private static boolean optionsKnown, mobsEnabled, vehiclesEnabled;

    /**
     * Mise en route a l'entree du bocal : le serveur et le client sont alors charges (photo, anneau, maillages), les
     * envois arrivent par paquets et le client rattrape des ticks d'un coup. Les entites ne sont montrees qu'apres
     * WARMUP_PACKETS envois reguliers d'affilee, sans saccade du client : un petit delai plutot que des saccades.
     * Pendant ce temps rien n'est montre, donc l'horloge se recale directement (sans la limite de MAX_CLOCK_CORRECTION).
     */
    private static final int WARMUP_PACKETS = 25; // Environ 2,5 secondes a 10 envois/s.
    private static boolean warmedUp;
    private static int regularPackets;
    /** Tick client trop rapproche (rattrapage) ou trop espace (image bloquee) depuis le dernier envoi. */
    private static boolean tickHitch;
    private static long lastTickNanos;

    private OutsideMobs() {
    }

    /** Compteur envoye au serveur : quand il change, le serveur renvoie tous les mobs avec de quoi les recreer. */
    public static int resyncCounter() {
        syncOptions();
        return resync;
    }

    private static void syncOptions() {
        boolean mobs = Config.outsideMobs(), vehicles = Config.outsideVehicles();
        if (optionsKnown && (mobs != mobsEnabled || vehicles != vehiclesEnabled)) clear();
        optionsKnown = true;
        mobsEnabled = mobs;
        vehiclesEnabled = vehicles;
    }

    private static void requestResync() {
        long now = System.nanoTime();
        if (now - lastResync < 1_000_000_000L) return;
        lastResync = now;
        resync++;
    }

    public static void receive(OutsideMobsPayload payload) {
        syncOptions();
        Minecraft mc = Minecraft.getInstance();
        if (payload.resync() != resync || mc.level == null
                || !mc.level.dimension().location().equals(payload.pocketDim())) return;
        if (!Config.outsideEntities()) {
            clear();
            return;
        }
        if (mc.level != level || !payload.pocketDim().equals(pocket)) {
            boolean had = level != null;
            clear();
            level = mc.level;
            pocket = payload.pocketDim();
            // Le serveur a peut-etre deja envoye ces mobs a l'ancien monde : tout redemander.
            if (had) return; // clear a change le compteur : attendre le nouvel envoi complet.
        }
        if (payload.tick() <= lastPacketTick) return;
        lastPacketTick = payload.tick();
        if (payload.resetAnimations()) COPIES.values().forEach(copy -> copy.livingAnimation.resetPending());
        double arrival = payload.tick() - clientTick;
        // Estimation de la meilleure arrivee recente. Le recalage reel est lisse par tick et par image :
        // changer directement offset ici faisait sauter les rotations lorsqu'un paquet arrivait plus vite.
        targetOffset = Double.isNaN(targetOffset) || arrival > targetOffset
                ? arrival : targetOffset + (arrival - targetOffset) * 0.02;
        if (Double.isNaN(offset)) offset = previousOffset = targetOffset;
        if (!warmedUp) {
            // Envoi en retard sur la meilleure arrivee, ou client qui a saccade : on recommence a compter.
            boolean late = targetOffset - arrival > OutsideMobsPayload.UPDATE_INTERVAL;
            regularPackets = late || tickHitch ? 0 : regularPackets + 1;
            tickHitch = false;
            offset = previousOffset = targetOffset;
            if (regularPackets >= WARMUP_PACKETS) warmedUp = true;
        }
        BlockPos origin = payload.origin();
        Set<Integer> seen = payload.full() ? new HashSet<>() : null;
        Set<Integer> updated = new HashSet<>();
        for (OutsideMobsPayload.Entry entry : payload.entries()) {
            updated.add(entry.id());
            if (seen != null) seen.add(entry.id());
            if (IGNORED.contains(entry.id())) continue;
            Copy copy = COPIES.get(entry.id());
            if (copy == null) {
                if (entry.create() == null) {
                    // Mob inconnu (copie perdue) : on ne peut pas le recreer sans son NBT.
                    requestResync();
                    continue;
                }
                copy = create(entry);
                if (copy == null) {
                    IGNORED.add(entry.id());
                    continue;
                }
                COPIES.put(entry.id(), copy);
            }
            apply(copy, entry);
            copy.removeAt = Integer.MAX_VALUE;
            if (entry.living() != null) copy.livingAnimation.snapshot(payload.tick(), entry.living());
            State state = new State(payload.tick(), origin.getX() + (double) entry.x(), origin.getY() + (double) entry.y(),
                    origin.getZ() + (double) entry.z(), entry.yRot(), entry.xRot(), entry.headRot(), entry.bodyRot(),
                    entry.vehicleId(), entry.seat(), entry.visual(), entry.environment());
            // Dans l'ordre des ticks (un envoi ne revient jamais en arriere, sauf redemarrage du serveur).
            if (!copy.states.isEmpty()) {
                State previous = copy.states.peekLast();
                if (previous.tick() >= state.tick()) {
                    copy.states.clear();
                } else if (state.tick() - previous.tick() > OutsideMobsPayload.UPDATE_INTERVAL) {
                    // Sans delta, le mob etait immobile jusqu'au precedent passage du serveur.
                    copy.states.addLast(new State(state.tick() - OutsideMobsPayload.UPDATE_INTERVAL, previous.x(), previous.y(), previous.z(),
                            previous.yRot(), previous.xRot(), previous.headRot(), previous.bodyRot(),
                            previous.vehicleId(), previous.seat(), previous.visual(), previous.environment()));
                }
            }
            copy.states.addLast(state);
            while (copy.states.size() > MAX_STATES) copy.states.pollFirst();
            copy.lastUpdate = clientTick;
        }
        for (MobAnimations.Event event : payload.events()) {
            Copy copy = COPIES.get(event.entityId());
            if (copy != null) copy.livingAnimation.event(event);
        }
        for (int id : payload.removed()) {
            scheduleRemoval(id, payload.tick());
            IGNORED.remove(id);
        }
        if (seen != null) {
            // Envoi complet : les copies gardees sont mises a jour, celles qui manquent sont retirees.
            for (int id : COPIES.keySet()) if (!seen.contains(id)) scheduleRemoval(id, payload.tick());
            IGNORED.removeIf(id -> !seen.contains(id));
        }
        // An omitted delta confirms the old pose at this tick. Keep two states even for stationary mobs.
        for (Map.Entry<Integer, Copy> item : COPIES.entrySet()) {
            Copy copy = item.getValue();
            if (updated.contains(item.getKey()) || copy.removeAt != Integer.MAX_VALUE || copy.states.isEmpty()) continue;
            State last = copy.states.peekLast();
            copy.states.addLast(new State(payload.tick(), last.x(), last.y(), last.z(), last.yRot(), last.xRot(),
                    last.headRot(), last.bodyRot(), last.vehicleId(), last.seat(), last.visual(), last.environment()));
            while (copy.states.size() > MAX_STATES) copy.states.pollFirst();
            copy.lastUpdate = clientTick;
        }
    }

    private static Copy create(OutsideMobsPayload.Entry entry) {
        ResourceLocation type = ResourceLocation.tryParse(entry.create().getString("id"));
        if (type == null || BROKEN.contains(type)) return null;
        try {
            Entity entity = EntityType.create(entry.create().copy(), level).orElse(null);
            boolean vehicle = entity instanceof Boat || entity instanceof AbstractMinecart;
            if (vehicle ? !Config.outsideVehicles() : !Config.outsideMobs() || !(entity instanceof LivingEntity)
                    || entity instanceof Player || entity instanceof ArmorStand) return null;
            entity.setId(entry.id());
            entity.noPhysics = true;
            // NBT carries a server fire timer. A copy never ticks it down: only the synchronized flag may keep it lit.
            entity.setRemainingFireTicks(0);
            return new Copy(entity);
        } catch (RuntimeException | LinkageError e) {
            BROKEN.add(type);
            LOGGER.debug("[Planarium Panorama] Mob du dehors non recree : {}", entry.create().getString("id"), e);
            return null;
        }
    }

    /** Donnees synchronisees et equipement recus (seulement quand ils ont change). */
    private static void apply(Copy copy, OutsideMobsPayload.Entry entry) {
        if (entry.data().length > 0) {
            RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(entry.data()), level.registryAccess());
            try {
                List<SynchedEntityData.DataValue<?>> values = new ArrayList<>();
                while (buf.isReadable()) {
                    int id = buf.readUnsignedByte();
                    if (id == 255) break;
                    values.add(SynchedEntityData.DataValue.read(buf, id));
                }
                copy.entity.getEntityData().assignValues(values);
            } catch (RuntimeException e) {
                LOGGER.debug("[Planarium Panorama] Donnees d'un mob du dehors ignorees", e);
            } finally {
                buf.release();
            }
        }
        if (entry.equipment().length > 0 && copy.entity instanceof LivingEntity living) {
            RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(entry.equipment()), level.registryAccess());
            try {
                for (EquipmentSlot slot : EquipmentSlot.values()) {
                    if (!buf.isReadable()) break;
                    ItemStack stack = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
                    living.setItemSlot(slot, stack);
                }
            } catch (RuntimeException e) {
                LOGGER.debug("[Planarium Panorama] Equipement d'un mob du dehors ignore", e);
            } finally {
                buf.release();
            }
        }
    }

    /** Chaque tick client : animation de marche d'apres le deplacement interpole, et menage. */
    public static void tick() {
        syncOptions();
        clientTick++;
        long nanos = System.nanoTime();
        if (lastTickNanos != 0L) {
            long gap = nanos - lastTickNanos;
            // Normalement 50 ms. Moins de 25 : Minecraft rattrape des ticks en retard ; plus de 100 : image bloquee.
            if (gap < 25_000_000L || gap > 100_000_000L) tickHitch = true;
        }
        lastTickNanos = nanos;
        Minecraft mc = Minecraft.getInstance();
        if (!Config.outsideEntities() || mc.level == null || mc.level != level) {
            clear();
            return;
        }
        if (COPIES.isEmpty()) return;
        previousOffset = offset;
        if (!Double.isNaN(targetOffset)) {
            offset += Mth.clamp(targetOffset - offset, -MAX_CLOCK_CORRECTION, MAX_CLOCK_CORRECTION);
        }
        double time = clientTick + offset - DELAY;
        Iterator<Copy> iterator = COPIES.values().iterator();
        while (iterator.hasNext()) {
            Copy copy = iterator.next();
            if (!BROKEN.isEmpty() && BROKEN.contains(EntityType.getKey(copy.entity.getType()))) {
                IGNORED.add(copy.entity.getId());
                unlinkAll();
                copy.stopSound();
                iterator.remove();
                continue;
            }
            if (clientTick - copy.lastUpdate > STALE_TICKS) {
                unlinkAll();
                copy.stopSound();
                iterator.remove();
                requestResync();
                continue;
            }
            State state = at(copy, time);
            if (state == null) continue;
            Entity entity = copy.entity;
            try {
                double previousX = copy.placed ? copy.tickX : state.x();
                double previousY = copy.placed ? copy.tickY : state.y();
                double previousZ = copy.placed ? copy.tickZ : state.z();
                if (entity instanceof AbstractMinecart) {
                    // Le rendu replace entity dans la photo : mesurer dans nos etats absolus, jamais sur entity.
                    double dx = state.x() - previousX, dy = state.y() - previousY, dz = state.z() - previousZ;
                    copy.soundSpeed = dx * dx + dy * dy + dz * dz > TELEPORT * TELEPORT
                            ? 0 : Math.sqrt(dx * dx + dz * dz);
                }
                entity.setPos(state.x(), state.y(), state.z());
                entity.xo = previousX;
                entity.yo = previousY;
                entity.zo = previousZ;
                copy.tickX = state.x();
                copy.tickY = state.y();
                copy.tickZ = state.z();
                copy.placed = true;
                OutsideAquatic.apply(entity, state.environment());
                if (entity instanceof LivingEntity living) {
                    copy.livingAnimation.advance((int) Math.floor(time));
                    applyAnimation(living, copy.livingAnimation);
                    if (state.vehicleId() >= 0) living.walkAnimation.update(0, 0.4F);
                    else living.calculateEntityAnimation(state.environment() != null && state.environment().waterOrBubble());
                    if (copy.livingAnimation.takePoof() && warmedUp && copy.ready && copy.rendered != null) {
                        ClientView.onMobDeath(copy.tickX, copy.tickY, copy.tickZ, entity.getBbWidth(), entity.getBbHeight());
                    }
                    int inkBursts = copy.livingAnimation.takeInk(); // Consume even while hidden; no replay after warmup.
                    if (warmedUp && copy.ready && copy.rendered != null && !copy.livingAnimation.ended()
                            && Config.sceneryParticles() && !FlightView.active()) {
                        MobEffects.tick(living);
                        AquaticEffects.tick(living, state.yRot(), state.xRot(), state.bodyRot(), inkBursts);
                    }
                }
                copy.animation.tick(entity, state.visual());
                // Render age drives freezing shakes and the wings of bees, allays, vexes and phantoms.
                entity.tickCount++;
                copy.wings.tick(entity);
                if (time >= copy.removeAt || copy.livingAnimation.ended()) {
                    unlinkAll();
                    copy.stopSound();
                    iterator.remove();
                }
            } catch (RuntimeException e) {
                LOGGER.debug("[Planarium Panorama] Animation d'un mob du dehors ignoree", e);
            }
        }
    }

    /** Only renderer fields: no AI, damage handling, drops, sounds or entity tick. */
    private static void applyAnimation(LivingEntity entity, MobAnimationTimeline animation) {
        if (animation.takeHurt()) entity.animateHurt(0);
        MobAnimations.Snapshot state = animation.state();
        entity.hurtTime = state.hurtTime();
        entity.hurtDuration = state.hurtDuration();
        entity.deathTime = state.deathTime();
        if (state.dying()) entity.setHealth(0);
        else if (entity.getHealth() <= 0) entity.setHealth(1); // Health metadata can arrive before its delayed death event.
        entity.swinging = state.swinging();
        entity.swingingArm = state.offHand() ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        entity.swingTime = state.swingTime();
        entity.oAttackAnim = animation.previousAttack();
        entity.attackAnim = animation.attack();
    }

    /** Boucles creees normalement a l'ajout d'un wagonnet au monde client, et donc absentes de nos copies. */
    public static void tickSounds(BlockPos photoOrigin, int radius) {
        boolean audible = photoOrigin != null && Config.worldSounds() && Config.outsideVehicles();
        Matrix4d frame = audible ? SubLevelView.sourceFrame(photoOrigin, radius) : null;
        var sounds = Minecraft.getInstance().getSoundManager();
        for (Copy copy : COPIES.values()) {
            if (!(copy.entity instanceof AbstractMinecart)) continue;
            if (!audible || !warmedUp || !copy.ready || !copy.placed || copy.entity.isSilent() || copy.entity.isRemoved()) {
                copy.stopSound();
                continue;
            }
            Vector3d at = frame.transformPosition(new Vector3d(copy.tickX - photoOrigin.getX(),
                    copy.tickY - photoOrigin.getY(), copy.tickZ - photoOrigin.getZ()));
            Vec3 relative = new Vec3(at.x, at.y, at.z);
            float volume = OutsideMinecartSound.volume(copy.soundSpeed, relative);
            if (volume <= 0) {
                copy.stopSound();
                continue;
            }
            // Reprendre apres F3+T ou une coupure du moteur audio, sans retenter chaque tick si le son est refuse.
            if (copy.sound != null && !sounds.isActive(copy.sound) && clientTick - copy.soundStarted >= 20) {
                copy.stopSound();
            }
            if (copy.sound == null) {
                copy.sound = new OutsideMinecartSound(relative, volume);
                copy.soundStarted = clientTick;
                sounds.play(copy.sound);
            } else {
                copy.sound.update(relative, volume);
            }
        }
    }

    /**
     * Les mobs a dessiner cette image, places dans le faux monde du decor (positions moins {@code photoOrigin}) avec
     * leur position et leurs rotations interpolees. Rien en vol (le decor normal est cache).
     */
    public static List<Entity> prepare(float partialTick, BlockPos photoOrigin) {
        if (COPIES.isEmpty() || photoOrigin == null || FlightView.active() || !Config.outsideEntities()) return List.of();
        if (!warmedUp) {
            for (Copy copy : COPIES.values()) copy.rendered = null;
            return List.of();
        }
        double frameOffset = Double.isNaN(previousOffset) ? offset : Mth.lerp(partialTick, previousOffset, offset);
        double time = clientTick + partialTick + frameOffset - DELAY;
        List<Entity> result = new ArrayList<>(COPIES.size());
        for (Copy copy : COPIES.values()) {
            State state = at(copy, time);
            copy.rendered = null;
            if (state == null || !BROKEN.isEmpty() && BROKEN.contains(EntityType.getKey(copy.entity.getType()))) continue;
            if (!copy.ready) {
                // Nouveau mob : attendre deux etats dont le dernier est apres l'instant dessine.
                if (copy.states.size() < 2 || copy.states.peekLast().tick() < time) continue;
                copy.ready = true;
            }
            copy.rendered = state;
            Entity entity = copy.entity;
            double x = state.x() - photoOrigin.getX();
            double y = state.y() - photoOrigin.getY();
            double z = state.z() - photoOrigin.getZ();
            // Deja interpole : l'ancienne valeur egale la nouvelle, le rendu n'interpole pas une seconde fois.
            entity.setPos(x, y, z); // Met aussi a jour la boite utilisee par le frustum.
            entity.xo = x;
            entity.yo = y;
            entity.zo = z;
            entity.xOld = x;
            entity.yOld = y;
            entity.zOld = z;
            entity.setYRot(state.yRot());
            entity.yRotO = state.yRot();
            entity.setXRot(state.xRot());
            entity.xRotO = state.xRot();
            if (entity instanceof LivingEntity living) {
                living.yBodyRot = state.bodyRot();
                living.yBodyRotO = state.bodyRot();
                living.yHeadRot = state.headRot();
                living.yHeadRotO = state.headRot();
            }
            copy.animation.prepare(entity, state.visual(), time, partialTick);
            OutsideAquatic.apply(entity, state.environment());
            result.add(entity);
        }
        // Une copie de vehicule refusee par son renderer ne doit pas laisser flotter ses passagers.
        for (Copy copy : COPIES.values()) {
            if (copy.rendered != null && !visibleChain(copy)) {
                result.remove(copy.entity);
                copy.rendered = null;
            }
        }
        linkPassengers();
        return result;
    }

    private static boolean visibleChain(Copy copy) {
        for (int depth = 0; depth < COPIES.size(); depth++) {
            if (copy == null || copy.rendered == null) return false;
            if (copy.rendered.vehicleId() < 0) return true;
            copy = COPIES.get(copy.rendered.vehicleId());
        }
        return false;
    }

    /** Relations exclusivement entre nos copies, au meme instant interpole que leurs positions. */
    private static void linkPassengers() {
        unlinkAll();
        Map<Copy, List<Copy>> groups = new HashMap<>();
        for (Copy passenger : COPIES.values()) {
            if (passenger.rendered == null || passenger.rendered.vehicleId() < 0) continue;
            Copy vehicle = COPIES.get(passenger.rendered.vehicleId());
            if (vehicle == null || vehicle.rendered == null || vehicle == passenger) continue;
            ((SceneryEntityAccessor) passenger.entity).planariumview$setVehicle(vehicle.entity);
            passenger.linked = true;
            groups.computeIfAbsent(vehicle, ignored -> new ArrayList<>()).add(passenger);
        }
        for (Map.Entry<Copy, List<Copy>> group : groups.entrySet()) {
            group.getValue().sort(java.util.Comparator.comparingInt(copy -> copy.rendered.seat()));
            ImmutableList<Entity> passengers = group.getValue().stream().map(copy -> copy.entity).collect(ImmutableList.toImmutableList());
            ((SceneryEntityAccessor) group.getKey().entity).planariumview$setPassengers(passengers);
            group.getKey().linked = true;
        }
    }

    private static void unlinkAll() {
        for (Copy copy : COPIES.values()) {
            if (!copy.linked) continue;
            SceneryEntityAccessor access = (SceneryEntityAccessor) copy.entity;
            access.planariumview$setVehicle(null);
            access.planariumview$setPassengers(ImmutableList.of());
            copy.linked = false;
        }
    }

    private static void scheduleRemoval(int id, int tick) {
        Copy copy = COPIES.get(id);
        if (copy != null) copy.removeAt = Math.min(copy.removeAt, tick);
    }

    /** Le dessin d'un mob a echoue : son type n'est plus dessine. */
    public static void broken(Entity entity) {
        if (BROKEN.add(EntityType.getKey(entity.getType()))) {
            LOGGER.debug("[Planarium Panorama] Mobs du dehors : {} n'est plus dessine", EntityType.getKey(entity.getType()));
        }
    }

    /** Etat interpole a {@code time} (ticks du serveur), ou null si rien n'a encore ete recu. */
    private static State at(Copy copy, double time) {
        if (copy.states.isEmpty()) return null;
        State before = null;
        for (State state : copy.states) {
            if (state.tick() > time) {
                if (before == null) return state;
                double span = state.tick() - before.tick();
                double dx = state.x() - before.x(), dy = state.y() - before.y(), dz = state.z() - before.z();
                if (span <= 0 || dx * dx + dy * dy + dz * dz > TELEPORT * TELEPORT) {
                    return (time - before.tick()) < span * 0.5 ? before : state;
                }
                float f = (float) ((time - before.tick()) / span);
                return new State((int) time, Mth.lerp(f, before.x(), state.x()), Mth.lerp(f, before.y(), state.y()),
                        Mth.lerp(f, before.z(), state.z()), Mth.rotLerp(f, before.yRot(), state.yRot()),
                        Mth.lerp(f, before.xRot(), state.xRot()), Mth.rotLerp(f, before.headRot(), state.headRot()),
                        Mth.rotLerp(f, before.bodyRot(), state.bodyRot()), before.vehicleId(), before.seat(),
                        OutsideVehicles.interpolate(before.visual(), state.visual(), f),
                        OutsideAquatic.interpolate(before.environment(), state.environment(), f));
            }
            before = state;
        }
        // Plus loin que le dernier etat recu (envoi en retard) : on attend sur place.
        return before;
    }

    public static void clear() {
        if (level != null || !COPIES.isEmpty()) resync++;
        unlinkAll();
        COPIES.values().forEach(Copy::stopSound);
        COPIES.clear();
        IGNORED.clear();
        level = null;
        pocket = null;
        offset = Double.NaN;
        targetOffset = Double.NaN;
        previousOffset = Double.NaN;
        warmedUp = false;
        regularPackets = 0;
        tickHitch = false;
        lastPacketTick = Integer.MIN_VALUE;
    }

    public static void logout() {
        clear();
        BROKEN.clear();
        resync = 0;
        clientTick = 0;
        lastResync = Long.MIN_VALUE / 2;
        optionsKnown = false;
    }
}

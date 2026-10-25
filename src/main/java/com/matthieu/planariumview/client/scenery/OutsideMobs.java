package com.matthieu.planariumview.client.scenery;

import com.matthieu.planariumview.config.Config;
import com.matthieu.planariumview.network.OutsideMobsPayload;

import com.mojang.logging.LogUtils;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
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
 * Mobs du dehors dans le decor geant (voir OutsideMobsServer) : des copies jamais ajoutees a un monde, sans IA,
 * placees chaque image entre les deux derniers etats recus, environ 250 ms en arriere (le serveur envoie 5 fois par
 * seconde). La marche est animee chaque tick d'apres le deplacement interpole, comme Minecraft le fait pour les
 * entites lointaines.
 *
 * Les etats sont gardes en coordonnees du monde d'origine : une nouvelle photo du decor (autre origine) ne fait pas
 * sauter les mobs, ils sont ramenes a l'origine de la photo affichee au moment du dessin.
 */
public final class OutsideMobs {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Retard du dessin sur le dernier etat recu, en ticks : un intervalle d'envoi (4) et un de marge. */
    private static final double DELAY = 5.0;
    /** Au-dela de ce saut entre deux etats (en blocs), le mob est place directement (teleportation). */
    private static final double TELEPORT = 4.0;
    /** Etats gardes par mob. */
    private static final int MAX_STATES = 6;
    /** Mob sans nouvelle depuis ce temps (ticks) : retire, par securite. */
    private static final int STALE_TICKS = 100;

    private record State(int tick, double x, double y, double z, float yRot, float xRot, float headRot, float bodyRot) {
    }

    private static final class Copy {
        final Entity entity;
        final ArrayDeque<State> states = new ArrayDeque<>();
        int lastUpdate;
        /** Position au dernier tick (monde d'origine), pour l'animation de marche. */
        double tickX, tickY, tickZ;
        boolean placed;

        Copy(Entity entity) {
            this.entity = entity;
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
    /** Tick du serveur moins tick du client, estime d'apres les arrivees (voir receive). */
    private static double offset = Double.NaN;
    /** Augmente quand on a besoin que le serveur renvoie tout (voir ViewPrefsPayload). */
    private static int resync;
    private static long lastResync = Long.MIN_VALUE / 2;

    private OutsideMobs() {
    }

    /** Compteur envoye au serveur : quand il change, le serveur renvoie tous les mobs avec de quoi les recreer. */
    public static int resyncCounter() {
        return resync;
    }

    private static void requestResync() {
        long now = System.nanoTime();
        if (now - lastResync < 1_000_000_000L) return;
        lastResync = now;
        resync++;
    }

    public static void receive(OutsideMobsPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (payload.resync() != resync || mc.level == null
                || !mc.level.dimension().location().equals(payload.pocketDim())) return;
        if (!Config.outsideMobs()) {
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
        double arrival = payload.tick() - clientTick;
        // Un envoi en avance releve l'estimation tout de suite ; un envoi en retard ne la baisse que doucement.
        offset = Double.isNaN(offset) || arrival > offset ? arrival : offset + (arrival - offset) * 0.02;
        BlockPos origin = payload.origin();
        Set<Integer> seen = payload.full() ? new HashSet<>() : null;
        for (OutsideMobsPayload.Entry entry : payload.entries()) {
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
            State state = new State(payload.tick(), origin.getX() + (double) entry.x(), origin.getY() + (double) entry.y(),
                    origin.getZ() + (double) entry.z(), degrees(entry.yRot()), degrees(entry.xRot()),
                    degrees(entry.headRot()), degrees(entry.bodyRot()));
            // Dans l'ordre des ticks (un envoi ne revient jamais en arriere, sauf redemarrage du serveur).
            if (!copy.states.isEmpty()) {
                State previous = copy.states.peekLast();
                if (previous.tick() >= state.tick()) {
                    copy.states.clear();
                } else if (state.tick() - previous.tick() > 4) {
                    // Sans delta, le mob etait immobile jusqu'au precedent passage du serveur.
                    copy.states.addLast(new State(state.tick() - 4, previous.x(), previous.y(), previous.z(),
                            previous.yRot(), previous.xRot(), previous.headRot(), previous.bodyRot()));
                }
            }
            copy.states.addLast(state);
            while (copy.states.size() > MAX_STATES) copy.states.pollFirst();
            copy.lastUpdate = clientTick;
        }
        for (int id : payload.removed()) {
            COPIES.remove(id);
            IGNORED.remove(id);
        }
        if (seen != null) {
            // Envoi complet : les copies gardees sont mises a jour, celles qui manquent sont retirees.
            COPIES.keySet().removeIf(id -> !seen.contains(id));
            IGNORED.removeIf(id -> !seen.contains(id));
        }
    }

    private static Copy create(OutsideMobsPayload.Entry entry) {
        ResourceLocation type = ResourceLocation.tryParse(entry.create().getString("id"));
        if (type == null || BROKEN.contains(type)) return null;
        try {
            Entity entity = EntityType.create(entry.create().copy(), level).orElse(null);
            if (!(entity instanceof LivingEntity)) return null;
            entity.setId(entry.id());
            entity.noPhysics = true;
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
        clientTick++;
        Minecraft mc = Minecraft.getInstance();
        if (!Config.outsideMobs() || mc.level == null || mc.level != level) {
            clear();
            return;
        }
        if (COPIES.isEmpty()) return;
        double time = clientTick + offset - DELAY;
        Iterator<Copy> iterator = COPIES.values().iterator();
        while (iterator.hasNext()) {
            Copy copy = iterator.next();
            if (!BROKEN.isEmpty() && BROKEN.contains(EntityType.getKey(copy.entity.getType()))) {
                IGNORED.add(copy.entity.getId());
                iterator.remove();
                continue;
            }
            if (clientTick - copy.lastUpdate > STALE_TICKS) {
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
                entity.setPos(state.x(), state.y(), state.z());
                entity.xo = previousX;
                entity.yo = previousY;
                entity.zo = previousZ;
                copy.tickX = state.x();
                copy.tickY = state.y();
                copy.tickZ = state.z();
                copy.placed = true;
                if (entity instanceof LivingEntity living) {
                    living.calculateEntityAnimation(false);
                }
                entity.tickCount++;
            } catch (RuntimeException e) {
                LOGGER.debug("[Planarium Panorama] Animation d'un mob du dehors ignoree", e);
            }
        }
    }

    /**
     * Les mobs a dessiner cette image, places dans le faux monde du decor (positions moins {@code photoOrigin}) avec
     * leur position et leurs rotations interpolees. Rien en vol (le decor normal est cache).
     */
    static List<Entity> prepare(float partialTick, BlockPos photoOrigin) {
        if (COPIES.isEmpty() || photoOrigin == null || FlightView.active() || !Config.outsideMobs()) return List.of();
        double time = clientTick + partialTick + offset - DELAY;
        List<Entity> result = new ArrayList<>(COPIES.size());
        for (Copy copy : COPIES.values()) {
            State state = at(copy, time);
            if (state == null || !BROKEN.isEmpty() && BROKEN.contains(EntityType.getKey(copy.entity.getType()))) continue;
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
            result.add(entity);
        }
        return result;
    }

    /** Angle envoye en octet (256 crans par tour, comme Minecraft). */
    private static float degrees(byte packed) {
        return packed * 360.0F / 256.0F;
    }

    /** Le dessin d'un mob a echoue : son type n'est plus dessine. */
    static void broken(Entity entity) {
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
                        Mth.rotLerp(f, before.bodyRot(), state.bodyRot()));
            }
            before = state;
        }
        // Plus loin que le dernier etat recu (envoi en retard) : on attend sur place.
        return before;
    }

    public static void clear() {
        if (level != null || !COPIES.isEmpty()) resync++;
        COPIES.clear();
        IGNORED.clear();
        level = null;
        pocket = null;
        offset = Double.NaN;
    }

    public static void logout() {
        clear();
        BROKEN.clear();
        resync = 0;
        clientTick = 0;
        lastResync = Long.MIN_VALUE / 2;
    }
}

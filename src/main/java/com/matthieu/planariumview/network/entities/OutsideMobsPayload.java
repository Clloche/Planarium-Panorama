package com.matthieu.planariumview.network.entities;

import com.matthieu.planariumview.PlanariumView;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Serveur -> client : mobs et vehicules du rayon detaille, quelques fois par seconde (voir OutsideMobsServer).
 *
 * Positions relatives a {@code origin}, l'origine de la photo du decor que le serveur a envoyee en dernier : le client
 * les ramene a la sienne (elle peut avoir une photo de retard). {@code tick} : tick du serveur, pour l'interpolation.
 * {@code resync} : compteur du client, pour ignorer les paquets d'une ancienne vue.
 * {@code full} : tous les mobs suivis sont dans cet envoi (un mob absent est retire) ; sinon seulement ce qui a change.
 * Les evenements portent leur tick et leur numero pour etre joues une fois sur l'horloge interpolee. En cas de
 * debordement serveur, {@code resetAnimations} remplace l'historique en attente par les etats visuels courants.
 */
public record OutsideMobsPayload(ResourceLocation pocketDim, int resync, BlockPos origin, int tick, boolean full,
                                 List<Entry> entries, int[] removed, List<MobAnimations.Event> events,
                                 boolean resetAnimations) implements CustomPacketPayload {

    public OutsideMobsPayload(ResourceLocation pocketDim, int resync, BlockPos origin, int tick, boolean full,
                              List<Entry> entries, int[] removed) {
        this(pocketDim, resync, origin, tick, full, entries, removed, List.of(), false);
    }

    /** Un etat toutes les deux ticks : mouvements plus precis, l'apparence garde son propre intervalle. */
    public static final int UPDATE_INTERVAL = 2;

    /**
     * Un mob : identifiant reseau, position, rotations en degres (sans les marches visibles des angles en octets).
     * {@code create} : de quoi le
     * recreer (type et NBT allege), seulement a sa premiere apparition. {@code data} : ses donnees synchronisees
     * (pose, variante, bebe, en feu...), {@code equipment} : son equipement, encodes comme Minecraft les envoie, ou
     * vides s'ils n'ont pas change.
     * {@code vehicleId} : identifiant d'une autre copie, ou -1 ; {@code seat} conserve l'ordre des passagers.
     * {@code visual} : animation d'un bateau ou compte a rebours d'un wagonnet TNT, sinon null.
     * {@code living} : phase des animations communes a la creation, lors d'un envoi complet ou d'un changement de vie.
     * {@code environment} : eau, sol, mouvement et pose des calmars, sur la meme horloge que la position.
     */
    public record Entry(int id, float x, float y, float z, float yRot, float xRot, float headRot, float bodyRot,
                        int vehicleId, int seat, VehicleState visual, CompoundTag create, byte[] data, byte[] equipment,
                        MobAnimations.Snapshot living, MobEnvironment environment) {
        public Entry(int id, float x, float y, float z, float yRot, float xRot, float headRot, float bodyRot,
                     int vehicleId, int seat, VehicleState visual, CompoundTag create, byte[] data, byte[] equipment,
                     MobAnimations.Snapshot living) {
            this(id, x, y, z, yRot, xRot, headRot, bodyRot, vehicleId, seat, visual, create, data, equipment, living, null);
        }

        public Entry(int id, float x, float y, float z, float yRot, float xRot, float headRot, float bodyRot,
                     int vehicleId, int seat, VehicleState visual, CompoundTag create, byte[] data, byte[] equipment) {
            this(id, x, y, z, yRot, xRot, headRot, bodyRot, vehicleId, seat, visual, create, data, equipment, null);
        }
    }

    /** Animations que les donnees synchronisees seules ne font pas avancer sans tick de l'entite. */
    public record VehicleState(float leftPaddle, float rightPaddle, int bubbleTicks, boolean underwater, int fuse) {
    }

    public static final Type<OutsideMobsPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(PlanariumView.MODID, "outside_mobs"));

    /** Limites a la lecture (un envoi normal en est tres loin). */
    private static final int MAX_ENTRIES = 1024;
    private static final int MAX_BYTES = 1 << 20;

    public static final StreamCodec<FriendlyByteBuf, OutsideMobsPayload> STREAM_CODEC =
            StreamCodec.of((buf, payload) -> payload.write(buf), OutsideMobsPayload::read);

    private void write(FriendlyByteBuf buf) {
        buf.writeResourceLocation(pocketDim);
        buf.writeVarInt(resync);
        buf.writeBlockPos(origin);
        buf.writeVarInt(tick);
        buf.writeBoolean(full);
        buf.writeBoolean(resetAnimations);
        buf.writeVarInt(entries.size());
        for (Entry entry : entries) {
            buf.writeVarInt(entry.id());
            buf.writeFloat(entry.x());
            buf.writeFloat(entry.y());
            buf.writeFloat(entry.z());
            buf.writeFloat(entry.yRot());
            buf.writeFloat(entry.xRot());
            buf.writeFloat(entry.headRot());
            buf.writeFloat(entry.bodyRot());
            buf.writeVarInt(entry.vehicleId() + 1);
            if (entry.vehicleId() >= 0) buf.writeVarInt(entry.seat());
            buf.writeBoolean(entry.visual() != null);
            if (entry.visual() != null) {
                VehicleState visual = entry.visual();
                buf.writeFloat(visual.leftPaddle());
                buf.writeFloat(visual.rightPaddle());
                buf.writeVarInt(visual.bubbleTicks());
                buf.writeBoolean(visual.underwater());
                buf.writeVarInt(visual.fuse() + 1);
            }
            buf.writeBoolean(entry.create() != null);
            if (entry.create() != null) buf.writeNbt(entry.create());
            buf.writeByteArray(entry.data());
            buf.writeByteArray(entry.equipment());
            buf.writeBoolean(entry.living() != null);
            if (entry.living() != null) writeLiving(buf, entry.living());
            buf.writeBoolean(entry.environment() != null);
            if (entry.environment() != null) entry.environment().write(buf);
        }
        buf.writeVarIntArray(removed);
        if (events.size() > MobAnimations.MAX_EVENTS) throw new IllegalArgumentException("Too many mob events");
        buf.writeVarInt(events.size());
        for (MobAnimations.Event event : events) {
            buf.writeVarLong(event.sequence());
            buf.writeVarInt(event.entityId());
            buf.writeVarInt(event.tick());
            buf.writeEnum(event.kind());
            buf.writeBoolean(event.offHand());
            buf.writeVarInt(event.duration());
        }
    }

    private static OutsideMobsPayload read(FriendlyByteBuf buf) {
        ResourceLocation pocket = buf.readResourceLocation();
        int resync = buf.readVarInt();
        BlockPos origin = buf.readBlockPos();
        int tick = buf.readVarInt();
        boolean full = buf.readBoolean();
        boolean resetAnimations = buf.readBoolean();
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_ENTRIES) throw new IllegalArgumentException("Trop de mobs : " + count);
        List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int id = buf.readVarInt();
            float x = buf.readFloat(), y = buf.readFloat(), z = buf.readFloat();
            float yRot = buf.readFloat(), xRot = buf.readFloat(), headRot = buf.readFloat(), bodyRot = buf.readFloat();
            int vehicleId = buf.readVarInt() - 1;
            int seat = vehicleId >= 0 ? buf.readVarInt() : 0;
            VehicleState visual = buf.readBoolean()
                    ? new VehicleState(buf.readFloat(), buf.readFloat(), buf.readVarInt(), buf.readBoolean(), buf.readVarInt() - 1) : null;
            CompoundTag create = buf.readBoolean() ? buf.readNbt() : null;
            byte[] data = buf.readByteArray(MAX_BYTES);
            byte[] equipment = buf.readByteArray(MAX_BYTES);
            MobAnimations.Snapshot living = buf.readBoolean() ? readLiving(buf) : null;
            MobEnvironment environment = buf.readBoolean() ? MobEnvironment.read(buf) : null;
            entries.add(new Entry(id, x, y, z, yRot, xRot, headRot, bodyRot, vehicleId, seat, visual, create, data, equipment, living, environment));
        }
        int[] removed = buf.readVarIntArray(MAX_ENTRIES);
        int eventCount = buf.readVarInt();
        if (eventCount < 0 || eventCount > MobAnimations.MAX_EVENTS) throw new IllegalArgumentException("Too many mob events");
        List<MobAnimations.Event> events = new ArrayList<>(eventCount);
        for (int i = 0; i < eventCount; i++) {
            events.add(new MobAnimations.Event(buf.readVarLong(), buf.readVarInt(), buf.readVarInt(),
                    buf.readEnum(MobAnimations.Kind.class), buf.readBoolean(), buf.readVarInt()));
        }
        return new OutsideMobsPayload(pocket, resync, origin, tick, full, entries, removed, events, resetAnimations);
    }

    private static void writeLiving(FriendlyByteBuf buf, MobAnimations.Snapshot state) {
        buf.writeVarInt(state.hurtTime());
        buf.writeVarInt(state.hurtDuration());
        buf.writeBoolean(state.dying());
        buf.writeVarInt(state.deathTime());
        buf.writeBoolean(state.swinging());
        buf.writeBoolean(state.offHand());
        buf.writeVarInt(state.swingTime() + 1);
        buf.writeVarInt(state.swingDuration());
    }

    private static MobAnimations.Snapshot readLiving(FriendlyByteBuf buf) {
        return new MobAnimations.Snapshot(buf.readVarInt(), buf.readVarInt(), buf.readBoolean(), buf.readVarInt(),
                buf.readBoolean(), buf.readBoolean(), buf.readVarInt() - 1, buf.readVarInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

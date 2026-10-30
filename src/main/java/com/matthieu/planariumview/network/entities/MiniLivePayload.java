package com.matthieu.planariumview.network.entities;

import com.matthieu.planariumview.PlanariumView;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Serveur -> client : mobs d'un bocal qui tourne pour sa miniature (voir MiniLive), quelques fois par seconde.
 * L'apparence de depart vient de l'instantane (MiniEntitiesPayload), meme UUID. Ce flux ajoute ce qui bouge et ce que
 * les mobs du dehors recoivent aussi (voir OutsideMobsPayload) : donnees synchronisees et equipement quand ils
 * changent (en feu, effets de potion, pose...), phase des animations, eau et vitesse, et les evenements (coup, attaque,
 * mort, nuage de disparition, encre) sur l'horloge du serveur ({@code tick}).
 */
public record MiniLivePayload(ResourceLocation pocketDim, int tick, boolean resetAnimations, List<Entry> entries,
                              List<MobAnimations.Event> events) implements CustomPacketPayload {
    /** Bornes independantes : 64 mobs et 64 vehicules. */
    private static final int MAX_ENTRIES = MiniEntityTypes.MAX_ENTITIES;
    private static final int MAX_BYTES = 1 << 20;

    public MiniLivePayload(ResourceLocation pocketDim, List<Entry> entries) {
        this(pocketDim, 0, false, entries, List.of());
    }

    /**
     * Position dans le bocal, angles en degres. {@code entityId} : identifiant reseau du mob, pour ses evenements.
     * {@code data} et {@code equipment} : encodes comme pour les mobs du dehors, vides s'ils n'ont pas change.
     * {@code living} : phase des animations (premier envoi, envoi complet, debut de la mort), sinon null.
     */
    public record Entry(UUID id, float x, float y, float z, float bodyYaw, float headYaw, float pitch, int flags,
                        int entityId, byte[] data, byte[] equipment, MobAnimations.Snapshot living,
                        MobEnvironment environment) {
        public static final int ON_GROUND = 1;
        public static final int IN_WATER = 2;
        public static final int UNDERWATER = 4;

        public Entry(UUID id, float x, float y, float z, float bodyYaw, float headYaw, float pitch, int flags) {
            this(id, x, y, z, bodyYaw, headYaw, pitch, flags, -1, new byte[0], new byte[0], null, null);
        }
    }

    public static final Type<MiniLivePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(PlanariumView.MODID, "mini_live"));

    public static final StreamCodec<FriendlyByteBuf, MiniLivePayload> STREAM_CODEC =
            StreamCodec.of((buf, payload) -> payload.write(buf), MiniLivePayload::read);

    private void write(FriendlyByteBuf buf) {
        buf.writeResourceLocation(pocketDim);
        buf.writeVarInt(tick);
        buf.writeBoolean(resetAnimations);
        buf.writeVarInt(entries.size());
        for (Entry entry : entries) {
            buf.writeUUID(entry.id());
            buf.writeFloat(entry.x());
            buf.writeFloat(entry.y());
            buf.writeFloat(entry.z());
            buf.writeByte(Mth.floor(entry.bodyYaw() * 256.0F / 360.0F));
            buf.writeByte(Mth.floor(entry.headYaw() * 256.0F / 360.0F));
            buf.writeByte(Mth.floor(entry.pitch() * 256.0F / 360.0F));
            buf.writeByte(entry.flags());
            buf.writeVarInt(entry.entityId() + 1);
            buf.writeByteArray(entry.data());
            buf.writeByteArray(entry.equipment());
            buf.writeBoolean(entry.living() != null);
            if (entry.living() != null) OutsideMobsPayload.writeLiving(buf, entry.living());
            buf.writeBoolean(entry.environment() != null);
            if (entry.environment() != null) entry.environment().write(buf);
        }
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

    private static MiniLivePayload read(FriendlyByteBuf buf) {
        ResourceLocation pocket = buf.readResourceLocation();
        int tick = buf.readVarInt();
        boolean reset = buf.readBoolean();
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_ENTRIES) {
            throw new IllegalArgumentException("Too many miniature mobs: " + count);
        }
        List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            UUID id = buf.readUUID();
            float x = buf.readFloat(), y = buf.readFloat(), z = buf.readFloat();
            float body = buf.readByte() * 360.0F / 256.0F;
            float head = buf.readByte() * 360.0F / 256.0F;
            float pitch = buf.readByte() * 360.0F / 256.0F;
            int flags = buf.readUnsignedByte();
            int entityId = buf.readVarInt() - 1;
            byte[] data = buf.readByteArray(MAX_BYTES);
            byte[] equipment = buf.readByteArray(MAX_BYTES);
            MobAnimations.Snapshot living = buf.readBoolean() ? OutsideMobsPayload.readLiving(buf) : null;
            MobEnvironment environment = buf.readBoolean() ? MobEnvironment.read(buf) : null;
            entries.add(new Entry(id, x, y, z, body, head, pitch, flags, entityId, data, equipment, living, environment));
        }
        int eventCount = buf.readVarInt();
        if (eventCount < 0 || eventCount > MobAnimations.MAX_EVENTS) throw new IllegalArgumentException("Too many mob events");
        List<MobAnimations.Event> events = new ArrayList<>(eventCount);
        for (int i = 0; i < eventCount; i++) {
            events.add(new MobAnimations.Event(buf.readVarLong(), buf.readVarInt(), buf.readVarInt(),
                    buf.readEnum(MobAnimations.Kind.class), buf.readBoolean(), buf.readVarInt()));
        }
        return new MiniLivePayload(pocket, tick, reset, entries, events);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

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
 * Serveur -> client : positions des mobs d'un bocal qui tourne pour sa miniature (voir MiniLive), quelques fois par
 * seconde. Seulement ce qui bouge : l'apparence vient de l'instantane (MiniEntitiesPayload), meme UUID.
 */
public record MiniLivePayload(ResourceLocation pocketDim, List<Entry> entries) implements CustomPacketPayload {
    /** Bornes independantes : 64 mobs et 64 vehicules. */
    private static final int MAX_ENTRIES = MiniEntityTypes.MAX_ENTITIES;

    /** Position dans le bocal, angles en degres. */
    public record Entry(UUID id, float x, float y, float z, float bodyYaw, float headYaw, float pitch, int flags) {
        public static final int ON_GROUND = 1;
        public static final int IN_WATER = 2;
        public static final int UNDERWATER = 4;
    }

    public static final Type<MiniLivePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(PlanariumView.MODID, "mini_live"));

    public static final StreamCodec<FriendlyByteBuf, MiniLivePayload> STREAM_CODEC =
            StreamCodec.of((buf, payload) -> payload.write(buf), MiniLivePayload::read);

    private void write(FriendlyByteBuf buf) {
        buf.writeResourceLocation(pocketDim);
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
        }
    }

    private static MiniLivePayload read(FriendlyByteBuf buf) {
        ResourceLocation pocket = buf.readResourceLocation();
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_ENTRIES) {
            throw new IllegalArgumentException("Too many miniature mobs: " + count);
        }
        List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entries.add(new Entry(buf.readUUID(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
                    buf.readByte() * 360.0F / 256.0F, buf.readByte() * 360.0F / 256.0F, buf.readByte() * 360.0F / 256.0F,
                    buf.readUnsignedByte()));
        }
        return new MiniLivePayload(pocket, entries);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

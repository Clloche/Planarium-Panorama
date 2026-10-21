package com.matthieu.planariumview.network;

import com.matthieu.planariumview.PlanariumView;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Serveur -> client : le decor en vol (voir FlightServer). Pendant qu'un Planarium bouge sur un vaisseau, le rayon
 * detaille est fait de sections de 16 blocs rangees par position dans le monde : seules les sections qui entrent dans
 * le rayon sont envoyees, au format compact de l'anneau lointain (blocs visibles seulement).
 *
 * {@code kind} : START (debut du vol, tout oublier), SECTION ({@code section} + {@code data}), DROP ({@code sections}
 * a oublier), READY (le premier lot est envoye), STOP (fin du vol : le rayon detaille normal reprend).
 */
public record FlightPayload(ResourceLocation pocketDim, int kind, long section, long[] sections, FarRingPayload.Data data)
        implements CustomPacketPayload {

    public static final int START = 0;
    public static final int SECTION = 1;
    public static final int DROP = 2;
    public static final int READY = 3;
    public static final int STOP = 4;

    public static final Type<FlightPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(PlanariumView.MODID, "flight"));

    public static final StreamCodec<FriendlyByteBuf, FlightPayload> STREAM_CODEC =
            StreamCodec.of((buf, payload) -> payload.write(buf), FlightPayload::read);

    public static FlightPayload signal(ResourceLocation pocket, int kind) {
        return new FlightPayload(pocket, kind, 0L, new long[0], FarRingPayload.Data.EMPTY);
    }

    public static FlightPayload section(ResourceLocation pocket, long section, FarRingPayload.Data data) {
        return new FlightPayload(pocket, SECTION, section, new long[0], data);
    }

    public static FlightPayload drop(ResourceLocation pocket, long[] sections) {
        return new FlightPayload(pocket, DROP, 0L, sections, FarRingPayload.Data.EMPTY);
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeResourceLocation(pocketDim);
        buf.writeVarInt(kind);
        buf.writeLong(section);
        buf.writeLongArray(sections);
        if (kind == SECTION) {
            FarRingPayload.writeData(buf, data);
        }
    }

    private static FlightPayload read(FriendlyByteBuf buf) {
        ResourceLocation pocket = buf.readResourceLocation();
        int kind = buf.readVarInt();
        long section = buf.readLong();
        long[] sections = buf.readLongArray(null, 1 << 16);
        FarRingPayload.Data data = kind == SECTION ? FarRingPayload.readData(buf) : FarRingPayload.Data.EMPTY;
        return new FlightPayload(pocket, kind, section, sections, data);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

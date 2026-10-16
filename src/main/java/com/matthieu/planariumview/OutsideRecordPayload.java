package com.matthieu.planariumview;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Serveur -> client : un jukebox du monde d'origine commence ou arrete un morceau.
 * {@code key} identifie le jukebox (sa position), {@code song} est l'identifiant du morceau ("" = arret),
 * {@code dx, dy, dz} sa position par rapport au Planarium.
 */
public record OutsideRecordPayload(ResourceLocation pocketDim, long key, String song, double dx, double dy, double dz)
        implements CustomPacketPayload {

    public static final Type<OutsideRecordPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(PlanariumView.MODID, "outside_record"));

    public static final StreamCodec<ByteBuf, OutsideRecordPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                ResourceLocation.STREAM_CODEC.encode(buf, payload.pocketDim());
                ByteBufCodecs.VAR_LONG.encode(buf, payload.key());
                ByteBufCodecs.STRING_UTF8.encode(buf, payload.song());
                ByteBufCodecs.DOUBLE.encode(buf, payload.dx());
                ByteBufCodecs.DOUBLE.encode(buf, payload.dy());
                ByteBufCodecs.DOUBLE.encode(buf, payload.dz());
            },
            buf -> new OutsideRecordPayload(
                    ResourceLocation.STREAM_CODEC.decode(buf),
                    ByteBufCodecs.VAR_LONG.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.DOUBLE.decode(buf),
                    ByteBufCodecs.DOUBLE.decode(buf),
                    ByteBufCodecs.DOUBLE.decode(buf)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

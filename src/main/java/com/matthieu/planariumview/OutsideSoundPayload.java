package com.matthieu.planariumview;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server to client: a sound heard at the real Planarium, with its direction and attenuated volume. */
public record OutsideSoundPayload(ResourceLocation pocketDim, ResourceLocation soundId, int source,
                                  float volume, float pitch, double dx, double dy, double dz,
                                  boolean entitySound) implements CustomPacketPayload {
    public static final Type<OutsideSoundPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(PlanariumView.MODID, "outside_sound"));

    public static final StreamCodec<ByteBuf, OutsideSoundPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                ResourceLocation.STREAM_CODEC.encode(buf, payload.pocketDim());
                ResourceLocation.STREAM_CODEC.encode(buf, payload.soundId());
                ByteBufCodecs.VAR_INT.encode(buf, payload.source());
                ByteBufCodecs.FLOAT.encode(buf, payload.volume());
                ByteBufCodecs.FLOAT.encode(buf, payload.pitch());
                ByteBufCodecs.DOUBLE.encode(buf, payload.dx());
                ByteBufCodecs.DOUBLE.encode(buf, payload.dy());
                ByteBufCodecs.DOUBLE.encode(buf, payload.dz());
                ByteBufCodecs.BOOL.encode(buf, payload.entitySound());
            },
            buf -> new OutsideSoundPayload(
                    ResourceLocation.STREAM_CODEC.decode(buf),
                    ResourceLocation.STREAM_CODEC.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.FLOAT.decode(buf),
                    ByteBufCodecs.FLOAT.decode(buf),
                    ByteBufCodecs.DOUBLE.decode(buf),
                    ByteBufCodecs.DOUBLE.decode(buf),
                    ByteBufCodecs.DOUBLE.decode(buf),
                    ByteBufCodecs.BOOL.decode(buf)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

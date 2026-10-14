package com.matthieu.planariumview;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Serveur -> client : la lumiere de l'interieur d'un bocal, pour la miniature affichee dans le Planarium.
 */
public record MiniLightPayload(ResourceLocation pocketDim, byte[] light) implements CustomPacketPayload {

    /** Boite lue dans le bocal : x et z de -1 a 32, y de 0 a 32 (le gabarit d'Ars commence a 0,1,0). */
    public static final int SIZE_X = 34;
    public static final int SIZE_Y = 33;
    public static final int SIZE_Z = 34;

    public static final Type<MiniLightPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(PlanariumView.MODID, "mini_light"));

    public static final StreamCodec<ByteBuf, MiniLightPayload> STREAM_CODEC = StreamCodec.composite(
            ResourceLocation.STREAM_CODEC, MiniLightPayload::pocketDim,
            ByteBufCodecs.BYTE_ARRAY, MiniLightPayload::light,
            MiniLightPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

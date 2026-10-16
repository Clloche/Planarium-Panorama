package com.matthieu.planariumview.network;

import com.matthieu.planariumview.PlanariumView;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Serveur -> client : un eclair est tombe dans le monde d'origine du bocal.
 *
 * @param dx   position de l'eclair par rapport au Planarium (en blocs du monde d'origine)
 * @param seed forme de l'eclair (meme forme que dehors)
 */
public record LightningPayload(ResourceLocation pocketDim, double dx, double dy, double dz, long seed)
        implements CustomPacketPayload {

    public static final Type<LightningPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(PlanariumView.MODID, "lightning"));

    public static final StreamCodec<ByteBuf, LightningPayload> STREAM_CODEC = StreamCodec.composite(
            ResourceLocation.STREAM_CODEC, LightningPayload::pocketDim,
            ByteBufCodecs.DOUBLE, LightningPayload::dx,
            ByteBufCodecs.DOUBLE, LightningPayload::dy,
            ByteBufCodecs.DOUBLE, LightningPayload::dz,
            ByteBufCodecs.VAR_LONG, LightningPayload::seed,
            LightningPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

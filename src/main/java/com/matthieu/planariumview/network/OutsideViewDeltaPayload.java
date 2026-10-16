package com.matthieu.planariumview.network;

import com.matthieu.planariumview.PlanariumView;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Serveur -> client : seulement les blocs du decor qui ont change depuis la derniere photo complete
 * ({@link OutsideViewPayload}), quand ces changements ne touchent ni la lumiere ni la meteo.
 * {@code changes} contient une liste "blocks" : position relative a la photo ("pos"), etat ("state", absent si le
 * bloc a ete retire) et donnees du bloc special ("nbt", facultatif).
 */
public record OutsideViewDeltaPayload(ResourceLocation pocketDim, int radius, BlockPos origin, CompoundTag changes)
        implements CustomPacketPayload {

    public static final Type<OutsideViewDeltaPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(PlanariumView.MODID, "outside_view_delta"));

    public static final StreamCodec<ByteBuf, OutsideViewDeltaPayload> STREAM_CODEC = StreamCodec.composite(
            ResourceLocation.STREAM_CODEC, OutsideViewDeltaPayload::pocketDim,
            ByteBufCodecs.VAR_INT, OutsideViewDeltaPayload::radius,
            BlockPos.STREAM_CODEC, OutsideViewDeltaPayload::origin,
            ByteBufCodecs.COMPOUND_TAG, OutsideViewDeltaPayload::changes,
            OutsideViewDeltaPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

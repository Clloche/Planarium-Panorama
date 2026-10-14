package com.matthieu.planariumview;

import io.netty.buffer.ByteBuf;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Serveur -> client : les evenements lunaires d'Enhanced Celestials du monde d'origine du bocal
 * (le calendrier tel que le mod le sauvegarde), pour les montrer dans le bocal.
 */
public record LunarPayload(ResourceLocation pocketDim, ResourceLocation sourceDim, CompoundTag forecast)
        implements CustomPacketPayload {

    public static final Type<LunarPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(PlanariumView.MODID, "lunar"));

    public static final StreamCodec<ByteBuf, LunarPayload> STREAM_CODEC = StreamCodec.composite(
            ResourceLocation.STREAM_CODEC, LunarPayload::pocketDim,
            ResourceLocation.STREAM_CODEC, LunarPayload::sourceDim,
            ByteBufCodecs.COMPOUND_TAG, LunarPayload::forecast,
            LunarPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

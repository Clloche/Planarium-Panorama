package com.matthieu.planariumview.network.entities;

import com.matthieu.planariumview.PlanariumView;

import io.netty.buffer.ByteBuf;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Serveur -> client : les porte-armures, tableaux et cadres d'un bocal, pour les dessiner dans la miniature du Planarium
 * (Ars ne dessine que les blocs). {@code entities} : la liste sous la cle "entities", positions dans le bocal.
 */
public record MiniEntitiesPayload(ResourceLocation pocketDim, CompoundTag entities) implements CustomPacketPayload {

    public static final Type<MiniEntitiesPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(PlanariumView.MODID, "mini_entities"));

    public static final StreamCodec<ByteBuf, MiniEntitiesPayload> STREAM_CODEC = StreamCodec.composite(
            ResourceLocation.STREAM_CODEC, MiniEntitiesPayload::pocketDim,
            ByteBufCodecs.COMPOUND_TAG, MiniEntitiesPayload::entities,
            MiniEntitiesPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

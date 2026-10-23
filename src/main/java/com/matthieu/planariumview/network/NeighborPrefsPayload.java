package com.matthieu.planariumview.network;

import com.matthieu.planariumview.PlanariumView;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client -> serveur : nombre de Planariums voisins que ce joueur dessine (option, 0 = OFF). Le serveur ne capture et
 * n'envoie que ceux-la. Sans ce message, le serveur en envoie le maximum.
 */
public record NeighborPrefsPayload(int count) implements CustomPacketPayload {
    public static final Type<NeighborPrefsPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(PlanariumView.MODID, "neighbor_prefs"));

    public static final StreamCodec<ByteBuf, NeighborPrefsPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, NeighborPrefsPayload::count, NeighborPrefsPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

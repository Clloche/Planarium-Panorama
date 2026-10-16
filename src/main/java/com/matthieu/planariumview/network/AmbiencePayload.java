package com.matthieu.planariumview.network;

import com.matthieu.planariumview.PlanariumView;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Serveur -> client : l'ambiance a l'endroit du Planarium (biome, abrite ou non, sous terre ou non),
 * dimension, hauteur du Planarium, niveau de la mer et hauteur de cette dimension (pour les sons d'altitude),
 * pour que Dynamic Surroundings joue dans le bocal les sons qu'on entendrait a cote du Planarium.
 */
public record AmbiencePayload(ResourceLocation pocketDim, ResourceLocation biome, boolean sheltered,
                              boolean underground, ResourceLocation sourceDim, int y, int seaLevel,
                              int height) implements CustomPacketPayload {

    public static final Type<AmbiencePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(PlanariumView.MODID, "ambience"));

    public static final StreamCodec<ByteBuf, AmbiencePayload> STREAM_CODEC =
            StreamCodec.ofMember(AmbiencePayload::write, AmbiencePayload::read);

    private void write(ByteBuf buf) {
        ResourceLocation.STREAM_CODEC.encode(buf, pocketDim);
        ResourceLocation.STREAM_CODEC.encode(buf, biome);
        buf.writeBoolean(sheltered);
        buf.writeBoolean(underground);
        ResourceLocation.STREAM_CODEC.encode(buf, sourceDim);
        buf.writeInt(y);
        buf.writeInt(seaLevel);
        buf.writeInt(height);
    }

    private static AmbiencePayload read(ByteBuf buf) {
        return new AmbiencePayload(ResourceLocation.STREAM_CODEC.decode(buf), ResourceLocation.STREAM_CODEC.decode(buf),
                buf.readBoolean(), buf.readBoolean(), ResourceLocation.STREAM_CODEC.decode(buf), buf.readInt(),
                buf.readInt(), buf.readInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

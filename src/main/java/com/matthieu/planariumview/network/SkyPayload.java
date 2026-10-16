package com.matthieu.planariumview.network;

import com.matthieu.planariumview.PlanariumView;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Serveur -> client : l'etat du ciel du monde d'origine (heure, meteo, couleurs du biome, saison de Serene Seasons ou -1),
 * et le nom donne au Planarium (vide s'il n'a pas de nom).
 */
public record SkyPayload(ResourceLocation pocketDim, long dayTime, boolean daylightCycle,
                         float rain, float thunder, int skyColor, int fogColor,
                         int seasonTicks, int outsideSky, int outsideBlock,
                         ResourceLocation effects, ResourceLocation dimensionType,
                         boolean hasSky, String planariumName) implements CustomPacketPayload {

    public static final Type<SkyPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(PlanariumView.MODID, "sky"));

    public static final StreamCodec<ByteBuf, SkyPayload> STREAM_CODEC =
            StreamCodec.ofMember(SkyPayload::write, SkyPayload::read);

    private void write(ByteBuf buf) {
        ResourceLocation.STREAM_CODEC.encode(buf, pocketDim);
        buf.writeLong(dayTime);
        buf.writeBoolean(daylightCycle);
        buf.writeFloat(rain);
        buf.writeFloat(thunder);
        buf.writeInt(skyColor);
        buf.writeInt(fogColor);
        buf.writeInt(seasonTicks);
        buf.writeByte(outsideSky);
        buf.writeByte(outsideBlock);
        ResourceLocation.STREAM_CODEC.encode(buf, effects);
        ResourceLocation.STREAM_CODEC.encode(buf, dimensionType);
        buf.writeBoolean(hasSky);
        net.minecraft.network.codec.ByteBufCodecs.STRING_UTF8.encode(buf, planariumName);
    }

    private static SkyPayload read(ByteBuf buf) {
        return new SkyPayload(ResourceLocation.STREAM_CODEC.decode(buf), buf.readLong(), buf.readBoolean(),
                buf.readFloat(), buf.readFloat(), buf.readInt(), buf.readInt(), buf.readInt(),
                buf.readByte(), buf.readByte(), ResourceLocation.STREAM_CODEC.decode(buf),
                ResourceLocation.STREAM_CODEC.decode(buf), buf.readBoolean(),
                net.minecraft.network.codec.ByteBufCodecs.STRING_UTF8.decode(buf));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

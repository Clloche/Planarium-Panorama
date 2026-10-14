package com.matthieu.planariumview;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Serveur -&gt; client : pour chaque face du bocal, la lumiere qui y entre et le bloc lumineux
 * qui l'eclaire le plus (identifiant d'etat de bloc, ou -1). Sert a colorer les parois avec Contraption Lights.
 */
public record WallPayload(ResourceLocation pocketDim, int[] light, int[] sources) implements CustomPacketPayload {

    public static final Type<WallPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(PlanariumView.MODID, "walls"));

    public static final StreamCodec<ByteBuf, WallPayload> STREAM_CODEC =
            StreamCodec.ofMember(WallPayload::write, WallPayload::read);

    private void write(ByteBuf buf) {
        ResourceLocation.STREAM_CODEC.encode(buf, pocketDim);
        for (int i = 0; i < JarWalls.FACES; i++) {
            buf.writeByte(light[i]);
            ByteBufCodecs.VAR_INT.encode(buf, sources[i] + 1);
        }
    }

    private static WallPayload read(ByteBuf buf) {
        ResourceLocation dim = ResourceLocation.STREAM_CODEC.decode(buf);
        int[] light = new int[JarWalls.FACES];
        int[] sources = new int[JarWalls.FACES];
        for (int i = 0; i < JarWalls.FACES; i++) {
            light[i] = buf.readByte();
            sources[i] = ByteBufCodecs.VAR_INT.decode(buf) - 1;
        }
        return new WallPayload(dim, light, sources);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

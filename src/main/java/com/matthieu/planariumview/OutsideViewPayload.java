package com.matthieu.planariumview;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Serveur -> client : la "photo" des alentours du Planarium (ou active=false pour effacer).
 *
 * @param origin       position (dans le monde d'origine) du coin du gabarit
 * @param biomePalette biomes presents dans la zone
 * @param biomes       grille 3D des biomes (voir BiomeGrid), index dans la palette
 * @param rainTops     pour chaque colonne (x * taille + z), le premier Y libre au-dessus du sol (la pluie s'arrete la)
 * @param precip       pour chaque colonne : 0 = rien, 1 = pluie, 2 = neige
 */
public record OutsideViewPayload(boolean active, ResourceLocation pocketDim, int radius, CompoundTag template,
                                 byte[] light, BlockPos origin, List<ResourceLocation> biomePalette, byte[] biomes,
                                 int[] rainTops, byte[] precip)
        implements CustomPacketPayload {

    public static final Type<OutsideViewPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(PlanariumView.MODID, "outside_view"));

    public static final StreamCodec<ByteBuf, OutsideViewPayload> STREAM_CODEC =
            StreamCodec.ofMember(OutsideViewPayload::write, OutsideViewPayload::read);

    private void write(ByteBuf buf) {
        buf.writeBoolean(active);
        ResourceLocation.STREAM_CODEC.encode(buf, pocketDim);
        ByteBufCodecs.VAR_INT.encode(buf, radius);
        ByteBufCodecs.TRUSTED_COMPOUND_TAG.encode(buf, template);
        ByteBufCodecs.BYTE_ARRAY.encode(buf, light);
        BlockPos.STREAM_CODEC.encode(buf, origin);
        ByteBufCodecs.VAR_INT.encode(buf, biomePalette.size());
        for (ResourceLocation biome : biomePalette) {
            ResourceLocation.STREAM_CODEC.encode(buf, biome);
        }
        ByteBufCodecs.BYTE_ARRAY.encode(buf, biomes);
        ByteBufCodecs.VAR_INT.encode(buf, rainTops.length);
        for (int top : rainTops) {
            buf.writeInt(top);
        }
        ByteBufCodecs.BYTE_ARRAY.encode(buf, precip);
    }

    private static OutsideViewPayload read(ByteBuf buf) {
        boolean active = buf.readBoolean();
        ResourceLocation dim = ResourceLocation.STREAM_CODEC.decode(buf);
        int radius = ByteBufCodecs.VAR_INT.decode(buf);
        CompoundTag template = ByteBufCodecs.TRUSTED_COMPOUND_TAG.decode(buf);
        byte[] light = ByteBufCodecs.BYTE_ARRAY.decode(buf);
        BlockPos origin = BlockPos.STREAM_CODEC.decode(buf);
        int count = ByteBufCodecs.VAR_INT.decode(buf);
        List<ResourceLocation> palette = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            palette.add(ResourceLocation.STREAM_CODEC.decode(buf));
        }
        byte[] biomes = ByteBufCodecs.BYTE_ARRAY.decode(buf);
        int columns = ByteBufCodecs.VAR_INT.decode(buf);
        if (columns < 0 || columns > 65 * 65) {
            throw new IllegalArgumentException("Planarium Panorama: bad weather column count " + columns);
        }
        int[] tops = new int[columns];
        for (int i = 0; i < columns; i++) {
            tops[i] = buf.readInt();
        }
        byte[] precip = ByteBufCodecs.BYTE_ARRAY.decode(buf);
        return new OutsideViewPayload(active, dim, radius, template, light, origin, palette, biomes, tops, precip);
    }

    public static OutsideViewPayload clear(ResourceLocation dim) {
        return new OutsideViewPayload(false, dim, 0, new CompoundTag(), new byte[0], BlockPos.ZERO, List.of(), new byte[0],
                new int[0], new byte[0]);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

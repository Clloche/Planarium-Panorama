package com.matthieu.planariumview.network;

import com.matthieu.planariumview.PlanariumView;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * Serveur -> client : l'anneau lointain du decor, une photo figee au-dela du rayon detaille (voir FarRingServer).
 * Seulement les blocs visibles, sans ceux du rayon detaille. {@code center} : position du Planarium dans le monde ;
 * {@code near} : rayon detaille exclu ; {@code far} : rayon horizontal ; {@code height} : demi-hauteur.
 * {@code far} a 0 = plus d'anneau.
 *
 * Ecrit en tableaux bruts, pas en NBT : un NBT de plus de 2 Mo est refuse par le client (anneau au-dela de ~80 blocs).
 */
public record FarRingPayload(ResourceLocation pocketDim, BlockPos center, int near, int far, int height, Data data)
        implements CustomPacketPayload {

    /**
     * {@code palette} : identifiants d'etats de bloc ; {@code blocks} : positions (voir pack) ; {@code info} : par bloc,
     * index de palette (16 bits) | voisins pleins (6 bits) << 16 | voisins d'eau (6 bits) << 22 ;
     * {@code lightPos}/{@code light} : lumiere des blocs et des cases autour ; {@code biomes} : un biome de surface par colonne de 4 x 4.
     */
    public record Data(int[] palette, int[] blocks, int[] info, int[] lightPos, byte[] light,
                       List<ResourceLocation> biomePalette, byte[] biomes, int quarts) {
        public static final Data EMPTY = new Data(new int[0], new int[0], new int[0], new int[0], new byte[0], List.of(), new byte[0], 0);
    }

    public static final Type<FarRingPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(PlanariumView.MODID, "far_ring"));

    /** Limite de taille de chaque tableau a la lecture (largement au-dessus d'un anneau de 96 blocs). */
    private static final int MAX = 1 << 24;

    public static final StreamCodec<FriendlyByteBuf, FarRingPayload> STREAM_CODEC =
            StreamCodec.of((buf, payload) -> payload.write(buf), FarRingPayload::read);

    private void write(FriendlyByteBuf buf) {
        buf.writeResourceLocation(pocketDim);
        buf.writeBlockPos(center);
        buf.writeVarInt(near);
        buf.writeVarInt(far);
        buf.writeVarInt(height);
        writeData(buf, data);
    }

    /** Ecrit des blocs visibles (aussi pour les sections du decor en vol, voir FlightPayload). */
    public static void writeData(FriendlyByteBuf buf, Data data) {
        buf.writeVarIntArray(data.palette());
        writeInts(buf, data.blocks());
        writeInts(buf, data.info());
        writeInts(buf, data.lightPos());
        buf.writeByteArray(data.light());
        buf.writeCollection(data.biomePalette(), FriendlyByteBuf::writeResourceLocation);
        buf.writeByteArray(data.biomes());
        buf.writeVarInt(data.quarts());
    }

    public static Data readData(FriendlyByteBuf buf) {
        int[] palette = buf.readVarIntArray(MAX);
        int[] blocks = readInts(buf);
        int[] info = readInts(buf);
        int[] lightPos = readInts(buf);
        byte[] light = buf.readByteArray(MAX);
        List<ResourceLocation> biomePalette = buf.readList(FriendlyByteBuf::readResourceLocation);
        byte[] biomes = buf.readByteArray(MAX);
        int quarts = buf.readVarInt();
        return new Data(palette, blocks, info, lightPos, light, biomePalette, biomes, quarts);
    }

    private static FarRingPayload read(FriendlyByteBuf buf) {
        ResourceLocation pocket = buf.readResourceLocation();
        BlockPos center = buf.readBlockPos();
        int near = buf.readVarInt();
        int far = buf.readVarInt();
        int height = buf.readVarInt();
        return new FarRingPayload(pocket, center, near, far, height, readData(buf));
    }

    /** Entiers de taille fixe (les positions et infos sont grandes : un VarInt prendrait 4 ou 5 octets). */
    private static void writeInts(FriendlyByteBuf buf, int[] values) {
        buf.writeVarInt(values.length);
        for (int value : values) {
            buf.writeInt(value);
        }
    }

    private static int[] readInts(FriendlyByteBuf buf) {
        int length = buf.readVarInt();
        if (length < 0 || length > MAX || length > buf.readableBytes() / 4) {
            throw new IllegalArgumentException("Anneau lointain : tableau invalide (" + length + ")");
        }
        int[] values = new int[length];
        for (int i = 0; i < length; i++) {
            values[i] = buf.readInt();
        }
        return values;
    }

    public static FarRingPayload clear(ResourceLocation pocketDim) {
        return new FarRingPayload(pocketDim, BlockPos.ZERO, 0, 0, 0, Data.EMPTY);
    }

    public boolean isClear() {
        return far <= 0 || data.blocks().length == 0;
    }

    // Positions dans la boite de l'anneau (0..2*far, 0..2*height, 0..2*far), 9 bits chacune.
    public static int pack(int x, int y, int z) {
        return x | y << 9 | z << 18;
    }

    public static int unpackX(int packed) {
        return packed & 511;
    }

    public static int unpackY(int packed) {
        return packed >>> 9 & 511;
    }

    public static int unpackZ(int packed) {
        return packed >>> 18 & 511;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

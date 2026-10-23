package com.matthieu.planariumview.network;

import com.matthieu.planariumview.PlanariumView;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Serveur -> client : photo figee de l'interieur des Planariums voisins (voir NeighborPocketsServer), du plus proche au
 * plus loin. {@code pocketDim} : bocal du joueur ; pour chaque voisin, {@code offset} = sa position moins celle du
 * Planarium du joueur (en blocs), et les blocs visibles de sa poche, murs de verre compris. Liste vide = plus de voisins.
 */
public record NeighborPocketsPayload(ResourceLocation pocketDim, List<Entry> entries) implements CustomPacketPayload {

    /** Les positions des blocs sont relatives a cette case de la poche (un bloc avant les murs, voir JarWalls). */
    public static final BlockPos ORIGIN = new BlockPos(-2, -1, -2);
    /** Au plus ce nombre de voisins envoyes (l'option du client en dessine de 0 a 10). */
    public static final int MAX = 10;

    public record Entry(BlockPos offset, FarRingPayload.Data data) {
    }

    public static final Type<NeighborPocketsPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(PlanariumView.MODID, "neighbor_pockets"));

    public static final StreamCodec<FriendlyByteBuf, NeighborPocketsPayload> STREAM_CODEC =
            StreamCodec.of((buf, payload) -> payload.write(buf), NeighborPocketsPayload::read);

    private void write(FriendlyByteBuf buf) {
        buf.writeResourceLocation(pocketDim);
        buf.writeVarInt(entries.size());
        for (Entry entry : entries) {
            buf.writeBlockPos(entry.offset());
            FarRingPayload.writeData(buf, entry.data());
        }
    }

    private static NeighborPocketsPayload read(FriendlyByteBuf buf) {
        ResourceLocation pocket = buf.readResourceLocation();
        int count = buf.readVarInt();
        if (count < 0 || count > MAX) {
            throw new IllegalArgumentException("Planariums voisins : nombre invalide (" + count + ")");
        }
        List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            BlockPos offset = buf.readBlockPos();
            entries.add(new Entry(offset, FarRingPayload.readData(buf)));
        }
        return new NeighborPocketsPayload(pocket, entries);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

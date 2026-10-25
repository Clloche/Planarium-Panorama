package com.matthieu.planariumview.network;

import com.matthieu.planariumview.PlanariumView;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Serveur -> client : les mobs du rayon detaille, quelques fois par seconde (voir OutsideMobsServer).
 *
 * Positions relatives a {@code origin}, l'origine de la photo du decor que le serveur a envoyee en dernier : le client
 * les ramene a la sienne (elle peut avoir une photo de retard). {@code tick} : tick du serveur, pour l'interpolation.
 * {@code resync} : compteur du client, pour ignorer les paquets d'une ancienne vue.
 * {@code full} : tous les mobs suivis sont dans cet envoi (un mob absent est retire) ; sinon seulement ce qui a change.
 */
public record OutsideMobsPayload(ResourceLocation pocketDim, int resync, BlockPos origin, int tick, boolean full,
                                 List<Entry> entries, int[] removed) implements CustomPacketPayload {

    /** Un etat toutes les deux ticks : mouvements plus precis, l'apparence garde son propre intervalle. */
    public static final int UPDATE_INTERVAL = 2;

    /**
     * Un mob : identifiant reseau, position, rotations en degres (sans les marches visibles des angles en octets).
     * {@code create} : de quoi le
     * recreer (type et NBT allege), seulement a sa premiere apparition. {@code data} : ses donnees synchronisees
     * (pose, variante, bebe, en feu...), {@code equipment} : son equipement, encodes comme Minecraft les envoie, ou
     * vides s'ils n'ont pas change.
     */
    public record Entry(int id, float x, float y, float z, float yRot, float xRot, float headRot, float bodyRot,
                        CompoundTag create, byte[] data, byte[] equipment) {
    }

    public static final Type<OutsideMobsPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(PlanariumView.MODID, "outside_mobs"));

    /** Limites a la lecture (un envoi normal en est tres loin). */
    private static final int MAX_ENTRIES = 1024;
    private static final int MAX_BYTES = 1 << 20;

    public static final StreamCodec<FriendlyByteBuf, OutsideMobsPayload> STREAM_CODEC =
            StreamCodec.of((buf, payload) -> payload.write(buf), OutsideMobsPayload::read);

    private void write(FriendlyByteBuf buf) {
        buf.writeResourceLocation(pocketDim);
        buf.writeVarInt(resync);
        buf.writeBlockPos(origin);
        buf.writeVarInt(tick);
        buf.writeBoolean(full);
        buf.writeVarInt(entries.size());
        for (Entry entry : entries) {
            buf.writeVarInt(entry.id());
            buf.writeFloat(entry.x());
            buf.writeFloat(entry.y());
            buf.writeFloat(entry.z());
            buf.writeFloat(entry.yRot());
            buf.writeFloat(entry.xRot());
            buf.writeFloat(entry.headRot());
            buf.writeFloat(entry.bodyRot());
            buf.writeBoolean(entry.create() != null);
            if (entry.create() != null) buf.writeNbt(entry.create());
            buf.writeByteArray(entry.data());
            buf.writeByteArray(entry.equipment());
        }
        buf.writeVarIntArray(removed);
    }

    private static OutsideMobsPayload read(FriendlyByteBuf buf) {
        ResourceLocation pocket = buf.readResourceLocation();
        int resync = buf.readVarInt();
        BlockPos origin = buf.readBlockPos();
        int tick = buf.readVarInt();
        boolean full = buf.readBoolean();
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_ENTRIES) throw new IllegalArgumentException("Trop de mobs : " + count);
        List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int id = buf.readVarInt();
            float x = buf.readFloat(), y = buf.readFloat(), z = buf.readFloat();
            float yRot = buf.readFloat(), xRot = buf.readFloat(), headRot = buf.readFloat(), bodyRot = buf.readFloat();
            CompoundTag create = buf.readBoolean() ? buf.readNbt() : null;
            byte[] data = buf.readByteArray(MAX_BYTES);
            byte[] equipment = buf.readByteArray(MAX_BYTES);
            entries.add(new Entry(id, x, y, z, yRot, xRot, headRot, bodyRot, create, data, equipment));
        }
        int[] removed = buf.readVarIntArray(MAX_ENTRIES);
        return new OutsideMobsPayload(pocket, resync, origin, tick, full, entries, removed);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

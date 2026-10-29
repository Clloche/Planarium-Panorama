package com.matthieu.planariumview.network.entities;

import com.matthieu.planariumview.PlanariumView;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client -> serveur : la miniature que le joueur regarde de pres ({@code pos} : le Planarium, dans sa dimension), ou
 * aucune ({@code watching} faux). Renvoye regulierement tant qu'il la regarde. Le serveur verifie la distance et le
 * Planarium avant de faire tourner son bocal (voir MiniLive).
 */
public record MiniWatchPayload(boolean watching, BlockPos pos, int mobLimit, boolean vehicles) implements CustomPacketPayload {
    public MiniWatchPayload {
        mobLimit = Math.clamp(mobLimit, 0, MiniEntityTypes.MAX_MOBS);
    }

    public static final Type<MiniWatchPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(PlanariumView.MODID, "mini_watch"));

    public static final StreamCodec<ByteBuf, MiniWatchPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, MiniWatchPayload::watching,
            BlockPos.STREAM_CODEC, MiniWatchPayload::pos,
            ByteBufCodecs.VAR_INT, MiniWatchPayload::mobLimit,
            ByteBufCodecs.BOOL, MiniWatchPayload::vehicles,
            MiniWatchPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

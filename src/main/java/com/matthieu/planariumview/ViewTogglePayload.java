package com.matthieu.planariumview;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client -> serveur : l'interrupteur general de Planarium Panorama du joueur. OFF : le serveur ne capture plus rien
 * pour lui (decor, ciel, ambiance, eclairs, lune) et ne garde plus les chunks autour de son Planarium charges.
 */
public record ViewTogglePayload(boolean enabled) implements CustomPacketPayload {

    public static final Type<ViewTogglePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(PlanariumView.MODID, "view_toggle"));

    public static final StreamCodec<ByteBuf, ViewTogglePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, ViewTogglePayload::enabled,
            ViewTogglePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

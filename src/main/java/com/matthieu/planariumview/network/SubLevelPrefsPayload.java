package com.matthieu.planariumview.network;

import com.matthieu.planariumview.PlanariumView;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client -> server: capture moving structures for this viewer. Older clients keep the default ON. */
public record SubLevelPrefsPayload(boolean enabled) implements CustomPacketPayload {
    public static final Type<SubLevelPrefsPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(PlanariumView.MODID, "sub_level_prefs"));

    public static final StreamCodec<ByteBuf, SubLevelPrefsPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, SubLevelPrefsPayload::enabled, SubLevelPrefsPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

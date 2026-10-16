package com.matthieu.planariumview;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client -> serveur : l'option "Sons des entites" du joueur. Sur OFF, le serveur ne lui envoie pas les sons des mobs
 * et des animaux (moins de reseau, et ils ne prennent pas la place des autres sons dans la limite par seconde).
 */
public record SoundPrefsPayload(boolean entitySounds) implements CustomPacketPayload {

    public static final Type<SoundPrefsPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(PlanariumView.MODID, "sound_prefs"));

    public static final StreamCodec<ByteBuf, SoundPrefsPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, SoundPrefsPayload::entitySounds,
            SoundPrefsPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

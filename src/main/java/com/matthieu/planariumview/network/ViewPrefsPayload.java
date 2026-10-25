package com.matthieu.planariumview.network;

import com.matthieu.planariumview.PlanariumView;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client -> serveur : ce que le client veut voir du dehors en plus des blocs. {@code mobs} : les mobs du rayon
 * detaille (voir OutsideMobsPayload) ; la zone autour du Planarium reste alors active, meme sans les sons du monde.
 * {@code resync} change quand le client a perdu ses copies (nouveau monde client, copie inconnue) : le serveur renvoie
 * alors tout, avec de quoi recreer chaque mob.
 */
public record ViewPrefsPayload(boolean mobs, int resync) implements CustomPacketPayload {

    public static final Type<ViewPrefsPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(PlanariumView.MODID, "view_prefs"));

    public static final StreamCodec<ByteBuf, ViewPrefsPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, ViewPrefsPayload::mobs,
            ByteBufCodecs.VAR_INT, ViewPrefsPayload::resync,
            ViewPrefsPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

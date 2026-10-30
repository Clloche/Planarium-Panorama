package com.matthieu.planariumview.network.entities;

import com.matthieu.planariumview.PlanariumView;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client -> serveur : limite des mobs du rayon detaille, et vehicules (affiches des que le monde fait vivre le dehors,
 * voir OutsideMobsPayload). Des mobs ou des vehicules gardent la zone autour du Planarium active, meme sans les sons du monde.
 * {@code resync} change quand le client a perdu ses copies (nouveau monde client, copie inconnue) : le serveur renvoie
 * alors tout, avec de quoi recreer chaque mob.
 */
public record ViewPrefsPayload(int mobLimit, boolean vehicles, int resync) implements CustomPacketPayload {

    public ViewPrefsPayload {
        // Le serveur ne fait jamais confiance a une limite envoyee par le client.
        mobLimit = Math.clamp(mobLimit, 0, 64);
    }

    public boolean mobs() {
        return mobLimit > 0;
    }

    public static final Type<ViewPrefsPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(PlanariumView.MODID, "view_prefs"));

    public static final StreamCodec<ByteBuf, ViewPrefsPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ViewPrefsPayload::mobLimit,
            ByteBufCodecs.BOOL, ViewPrefsPayload::vehicles,
            ByteBufCodecs.VAR_INT, ViewPrefsPayload::resync,
            ViewPrefsPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

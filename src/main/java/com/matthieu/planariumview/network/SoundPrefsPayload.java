package com.matthieu.planariumview.network;

import com.matthieu.planariumview.PlanariumView;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client -> serveur : les options des sons du monde exterieur. {@code world} OFF : la zone autour du Planarium
 * reste en pause tant que personne n'y est (sauf si des mobs sont affiches), et le serveur n'envoie ni les sons des
 * blocs, ni les explosions, ni les jukebox. {@code mobs} suit l'affichage des mobs (limite superieure a zero).
 */
public record SoundPrefsPayload(boolean world, boolean mobs, boolean blocks, boolean explosions)
        implements CustomPacketPayload {

    public static final Type<SoundPrefsPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(PlanariumView.MODID, "sound_prefs"));

    /** Sans message du client (ancienne version) : tout est permis, comme avant. */
    public static final SoundPrefsPayload DEFAULT = new SoundPrefsPayload(true, true, true, true);

    public static final StreamCodec<ByteBuf, SoundPrefsPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, SoundPrefsPayload::world,
            ByteBufCodecs.BOOL, SoundPrefsPayload::mobs,
            ByteBufCodecs.BOOL, SoundPrefsPayload::blocks,
            ByteBufCodecs.BOOL, SoundPrefsPayload::explosions,
            SoundPrefsPayload::new);

    /** Ce joueur veut-il les sons de cette categorie (voir OutsideSoundPayload) ? */
    public boolean allows(int category) {
        // Les sons des mobs suivent leur affichage, independamment des sons du monde exterieur.
        if (category == OutsideSoundPayload.MOBS) {
            return mobs;
        }
        if (!world) {
            return false;
        }
        return category == OutsideSoundPayload.EXPLOSIONS ? explosions : blocks;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

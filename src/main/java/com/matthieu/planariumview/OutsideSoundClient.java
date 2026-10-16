package com.matthieu.planariumview;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/** Plays server-forwarded sounds from the direction of their real-world source. */
final class OutsideSoundClient {
    private OutsideSoundClient() {
    }

    static void receive(OutsideSoundPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || !Config.outsideSounds()
                || !level.dimension().location().equals(payload.pocketDim())
                || (payload.entitySound() && !Config.includeEntitySounds())) {
            return;
        }
        SoundSource[] sources = SoundSource.values();
        if (payload.source() < 0 || payload.source() >= sources.length || payload.volume() <= 0.0F) {
            return;
        }
        SoundEvent sound = BuiltInRegistries.SOUND_EVENT.getOptional(payload.soundId()).orElse(null);
        if (sound == null) {
            return;
        }

        play(sound, sources[payload.source()], payload.volume(), payload.pitch(), payload.dx(), payload.dy(), payload.dz());
    }

    /**
     * Ou jouer un son venant de (dx, dy, dz) par rapport au Planarium : a 8 blocs de la camera, dans la direction
     * de cette position dans le decor geant (meme transformation que le decor).
     */
    static Vec3 playbackPosition(double dx, double dy, double dz) {
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        Vec3 source = new Vec3((dx - ClientView.ARS_OFFSET) * ClientView.GIANT,
                (dy - ClientView.ARS_OFFSET) * ClientView.GIANT - ClientView.Y_SHIFT,
                (dz - ClientView.ARS_OFFSET) * ClientView.GIANT);
        Vec3 direction = source.subtract(camera);
        direction = direction.lengthSqr() < 1.0E-4 ? new Vec3(0.0, 1.0, 0.0) : direction.normalize();
        return camera.add(direction.scale(8.0));
    }

    /** Joue un son deja attenue (volume final) venant de (dx, dy, dz) par rapport au Planarium. */
    static void play(SoundEvent sound, SoundSource source, float volume, float pitch, double dx, double dy, double dz) {
        if (volume <= 0.0F) {
            return;
        }
        Vec3 playback = playbackPosition(dx, dy, dz);
        SimpleSoundInstance instance = new SimpleSoundInstance(sound, source, volume,
                pitch, RandomSource.create(), playback.x, playback.y, playback.z) {
            // The server already applied distance falloff. Keep only positional panning here.
            @Override
            public SoundInstance.Attenuation getAttenuation() {
                return SoundInstance.Attenuation.NONE;
            }
        };
        Minecraft.getInstance().getSoundManager().play(instance);
    }
}

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

        Vec3 camera = minecraft.gameRenderer.getMainCamera().getPosition();
        // Use the same giant-scene transform as the scenery, so the sound comes from its visible direction.
        Vec3 source = new Vec3((payload.dx() - ClientView.ARS_OFFSET) * ClientView.GIANT,
                (payload.dy() - ClientView.ARS_OFFSET) * ClientView.GIANT - ClientView.Y_SHIFT,
                (payload.dz() - ClientView.ARS_OFFSET) * ClientView.GIANT);
        Vec3 direction = source.subtract(camera);
        direction = direction.lengthSqr() < 1.0E-4 ? new Vec3(0.0, 1.0, 0.0) : direction.normalize();
        Vec3 playback = camera.add(direction.scale(8.0));

        SimpleSoundInstance instance = new SimpleSoundInstance(sound, sources[payload.source()], payload.volume(),
                payload.pitch(), RandomSource.create(), playback.x, playback.y, playback.z) {
            // The server already applied distance falloff. Keep only positional panning here.
            @Override
            public SoundInstance.Attenuation getAttenuation() {
                return SoundInstance.Attenuation.NONE;
            }
        };
        minecraft.getSoundManager().play(instance);
    }
}

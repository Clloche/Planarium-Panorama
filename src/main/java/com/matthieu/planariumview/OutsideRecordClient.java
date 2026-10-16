package com.matthieu.planariumview;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.item.JukeboxSong;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * Musique des jukebox du monde d'origine, entendue dans le bocal. Le morceau dure plusieurs minutes : sa direction est
 * recalculee a chaque tick selon la camera, et il s'arrete si on quitte le bocal ou si les sons du dehors sont coupes.
 */
final class OutsideRecordClient {
    /** Portee d'un jukebox (comme Minecraft). */
    private static final double RANGE = 64.0;
    private static final Map<Long, RecordSound> PLAYING = new HashMap<>();

    private OutsideRecordClient() {
    }

    static void receive(OutsideRecordPayload payload) {
        RecordSound previous = PLAYING.remove(payload.key());
        if (previous != null) {
            previous.end();
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (payload.song().isEmpty() || level == null || !Config.outsideSounds()
                || !level.dimension().location().equals(payload.pocketDim())) {
            return;
        }
        ResourceLocation id = ResourceLocation.tryParse(payload.song());
        if (id == null) {
            return;
        }
        var holder = level.registryAccess().registryOrThrow(Registries.JUKEBOX_SONG)
                .getHolder(ResourceKey.create(Registries.JUKEBOX_SONG, id));
        if (holder.isEmpty()) {
            return;
        }
        JukeboxSong song = holder.get().value();
        double distance = Math.sqrt(payload.dx() * payload.dx() + payload.dy() * payload.dy() + payload.dz() * payload.dz());
        float volume = (float) Mth.clamp(1.0 - distance / RANGE, 0.0, 1.0);
        if (volume <= 0.0F) {
            return;
        }
        RecordSound sound = new RecordSound(song.soundEvent().value(), payload.pocketDim(),
                payload.dx(), payload.dy(), payload.dz(), volume);
        PLAYING.put(payload.key(), sound);
        minecraft.getSoundManager().play(sound);
        // Comme Minecraft : "En cours de lecture" au-dessus de la barre d'objets.
        minecraft.gui.setNowPlaying(song.description());
    }

    static void clear() {
        for (RecordSound sound : PLAYING.values()) {
            sound.end();
        }
        PLAYING.clear();
    }

    private static final class RecordSound extends AbstractTickableSoundInstance {
        private final ResourceLocation pocketDim;
        private final double dx;
        private final double dy;
        private final double dz;

        RecordSound(SoundEvent event, ResourceLocation pocketDim, double dx, double dy, double dz, float volume) {
            super(event, SoundSource.RECORDS, SoundInstance.createUnseededRandom());
            this.pocketDim = pocketDim;
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
            this.volume = volume;
            this.attenuation = SoundInstance.Attenuation.NONE;
            this.looping = false;
            this.relative = false;
            place();
        }

        @Override
        public void tick() {
            ClientLevel level = Minecraft.getInstance().level;
            if (level == null || !Config.outsideSounds() || !level.dimension().location().equals(pocketDim)) {
                stop();
                return;
            }
            place();
        }

        void end() {
            stop();
        }

        private void place() {
            Vec3 playback = OutsideSoundClient.playbackPosition(dx, dy, dz);
            this.x = playback.x;
            this.y = playback.y;
            this.z = playback.z;
        }
    }
}

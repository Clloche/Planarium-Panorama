package com.matthieu.planariumview.client.sound;

import com.matthieu.planariumview.client.sound.entities.OutsideMinecartSound;
import com.matthieu.planariumview.client.scenery.ClientView;
import com.matthieu.planariumview.config.Config;
import com.matthieu.planariumview.network.OutsideSoundPayload;

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
public final class OutsideSoundClient {
    /** Les sons d'ambiance du decor sont un peu baisses : on les entend a travers le verre du bocal. */
    public static final float AMBIENCE_VOLUME = 0.6F;
    private static final java.util.Set<SoundInstance> MOB_SOUNDS =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private OutsideSoundClient() {
    }

    public static void receive(OutsideSoundPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || !level.dimension().location().equals(payload.pocketDim()) || !allowed(payload.category())) {
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

        play(sound, sources[payload.source()], payload.volume(), payload.pitch(), payload.dx(), payload.dy(), payload.dz(),
                false, payload.category() == OutsideSoundPayload.MOBS);
        if (payload.category() == OutsideSoundPayload.EXPLOSIONS) {
            ClientView.onOutsideExplosion(payload.dx(), payload.dy(), payload.dz());
        }
    }

    /** Options du client pour chaque categorie de son du monde exterieur (le serveur filtre deja de son cote). */
    private static boolean allowed(int category) {
        return switch (category) {
            case OutsideSoundPayload.MOBS -> Config.mobSounds();
            case OutsideSoundPayload.EXPLOSIONS -> Config.explosionSounds();
            default -> Config.blockSounds();
        };
    }

    /**
     * Ou jouer un son venant de (dx, dy, dz) par rapport au Planarium : a 8 blocs de la camera, dans la direction
     * de cette position dans le decor geant (meme transformation que le decor).
     */
    public static Vec3 playbackPosition(double dx, double dy, double dz) {
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        Vec3 source = new Vec3((dx - ClientView.ARS_OFFSET) * ClientView.GIANT,
                (dy - ClientView.ARS_OFFSET) * ClientView.GIANT - ClientView.Y_SHIFT,
                (dz - ClientView.ARS_OFFSET) * ClientView.GIANT);
        Vec3 direction = source.subtract(camera);
        direction = direction.lengthSqr() < 1.0E-4 ? new Vec3(0.0, 1.0, 0.0) : direction.normalize();
        return camera.add(direction.scale(8.0));
    }

    /** Joue un son deja attenue (volume final) venant de (dx, dy, dz) par rapport au Planarium. */
    public static void play(SoundEvent sound, SoundSource source, float volume, float pitch, double dx, double dy, double dz,
                     boolean scenery) {
        play(sound, source, volume, pitch, dx, dy, dz, scenery, false);
    }

    private static void play(SoundEvent sound, SoundSource source, float volume, float pitch, double dx, double dy, double dz,
                             boolean scenery, boolean mob) {
        if (volume <= 0.0F) {
            return;
        }
        Vec3 playback = playbackPosition(dx, dy, dz);
        SoundInstance instance = new OutsideInstance(sound, source, volume, pitch, playback, scenery, new Vec3(dx, dy, dz));
        Minecraft.getInstance().getSoundManager().play(instance);
        if (mob) MOB_SOUNDS.add(instance);
    }

    /** Arrete aussi les sons deja en cours quand l'option des mobs ou des sons est coupee. */
    public static void tick() {
        if (MOB_SOUNDS.isEmpty()) return;
        if (!Config.mobSounds()) {
            clear();
        } else {
            MOB_SOUNDS.removeIf(sound -> !Minecraft.getInstance().getSoundManager().isActive(sound));
        }
    }

    public static void clear() {
        for (SoundInstance sound : MOB_SOUNDS) Minecraft.getInstance().getSoundManager().stop(sound);
        MOB_SOUNDS.clear();
    }

    /** Vrai pour un son d'ambiance du decor (voir DsSoundBlockMixin). */
    public static boolean isScenery(SoundInstance sound) {
        return sound instanceof OutsideInstance outside && outside.scenery || MachineSounds.isScenery(sound);
    }

    /** Position d'un son du dehors par rapport au Planarium, en blocs du vrai monde (voir SceneryReverb). */
    public static Vec3 outsidePosition(SoundInstance sound) {
        if (sound instanceof OutsideMinecartSound minecart) return minecart.outsidePosition();
        return sound instanceof OutsideInstance outside ? outside.relative : null;
    }

    /** Son venu du dehors : deja attenue (par le serveur ou par ClientView), on ne garde que sa direction. */
    private static final class OutsideInstance extends SimpleSoundInstance {
        private final boolean scenery;
        private final Vec3 relative;

        OutsideInstance(SoundEvent sound, SoundSource source, float volume, float pitch, Vec3 at, boolean scenery,
                        Vec3 relative) {
            super(sound, source, volume, pitch, RandomSource.create(), at.x, at.y, at.z);
            this.scenery = scenery;
            this.relative = relative;
        }

        @Override
        public SoundInstance.Attenuation getAttenuation() {
            return SoundInstance.Attenuation.NONE;
        }
    }
}

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
public final class OutsideSoundClient {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("planariumview");
    /** Les sons d'ambiance du decor sont un peu baisses : on les entend a travers le verre du bocal. */
    static final float AMBIENCE_VOLUME = 0.6F;
    private OutsideSoundClient() {
    }

    static void receive(OutsideSoundPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || !level.dimension().location().equals(payload.pocketDim()) || !allowed(payload.category())) {
            if (payload.category() == OutsideSoundPayload.EXPLOSIONS) {
                LOGGER.info("[Planarium Panorama] Son {} recu mais ignore ({})", payload.soundId(),
                        level == null ? "pas de monde" : !allowed(payload.category()) ? "option sur OFF" : "pas dans ce bocal");
            }
            return;
        }
        if (payload.category() == OutsideSoundPayload.EXPLOSIONS) {
            LOGGER.info("[Planarium Panorama] Son {} recu, joue (volume {})", payload.soundId(), payload.volume());
        }
        SoundSource[] sources = SoundSource.values();
        if (payload.source() < 0 || payload.source() >= sources.length || payload.volume() <= 0.0F) {
            return;
        }
        SoundEvent sound = BuiltInRegistries.SOUND_EVENT.getOptional(payload.soundId()).orElse(null);
        if (sound == null) {
            return;
        }

        play(sound, sources[payload.source()], payload.volume(), payload.pitch(), payload.dx(), payload.dy(), payload.dz(), false);
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
    static void play(SoundEvent sound, SoundSource source, float volume, float pitch, double dx, double dy, double dz,
                     boolean scenery) {
        if (volume <= 0.0F) {
            return;
        }
        Vec3 playback = playbackPosition(dx, dy, dz);
        Minecraft.getInstance().getSoundManager().play(new OutsideInstance(sound, source, volume, pitch,
                playback, scenery));
    }

    /** Vrai pour un son d'ambiance du decor (voir DsSoundBlockMixin). */
    public static boolean isScenery(SoundInstance sound) {
        return sound instanceof OutsideInstance outside && outside.scenery;
    }

    /** Son venu du dehors : deja attenue (par le serveur ou par ClientView), on ne garde que sa direction. */
    private static final class OutsideInstance extends SimpleSoundInstance {
        private final boolean scenery;

        OutsideInstance(SoundEvent sound, SoundSource source, float volume, float pitch, Vec3 at, boolean scenery) {
            super(sound, source, volume, pitch, RandomSource.create(), at.x, at.y, at.z);
            this.scenery = scenery;
        }

        @Override
        public SoundInstance.Attenuation getAttenuation() {
            return SoundInstance.Attenuation.NONE;
        }
    }
}

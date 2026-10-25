package com.matthieu.planariumview.client.sound.entities;

import com.matthieu.planariumview.client.sound.OutsideSoundClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/** Roulement d'une copie de wagonnet : vitesse du monde d'origine, son replace autour du bocal. */
public final class OutsideMinecartSound extends AbstractTickableSoundInstance {
    private volatile Vec3 outsidePosition;

    public OutsideMinecartSound(Vec3 position, float volume) {
        super(SoundEvents.MINECART_RIDING, SoundSource.NEUTRAL, SoundInstance.createUnseededRandom());
        looping = true;
        delay = 0;
        attenuation = Attenuation.NONE;
        update(position, volume);
    }

    /** Meme courbe que MinecartSoundInstance, attenuee en blocs du dehors (avant agrandissement). */
    public static float volume(double speed, Vec3 position) {
        if (!Double.isFinite(speed) || speed < 0.01 || !Double.isFinite(position.lengthSqr())) return 0;
        float base = (float) Math.min(speed, 0.5) * 0.7F;
        double distance = position.distanceTo(new Vec3(0.5, 0.5, 0.5));
        return base * (float) Math.max(0, 1 - distance / SoundEvents.MINECART_RIDING.getRange(base))
                * OutsideSoundClient.AMBIENCE_VOLUME;
    }

    public void update(Vec3 position, float volume) {
        outsidePosition = position;
        this.volume = volume;
        place();
    }

    public Vec3 outsidePosition() {
        return outsidePosition;
    }

    private void place() {
        Vec3 position = outsidePosition;
        Vec3 at = OutsideSoundClient.playbackPosition(position.x, position.y, position.z);
        x = at.x;
        y = at.y;
        z = at.z;
    }

    @Override
    public void tick() {
        place();
    }

    public void end() {
        stop();
        Minecraft.getInstance().getSoundManager().stop(this);
    }
}

package com.matthieu.planariumview.network.entities;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.monster.Ravager;
import net.minecraft.world.entity.monster.Zoglin;
import net.minecraft.world.entity.monster.hoglin.Hoglin;
import net.minecraft.world.entity.monster.warden.Warden;

/** Visual-only state, independent of a client world and safe to share with the server. */
public final class MobAnimations {
    public static final int MAX_EVENTS = 512;
    public static final int MAX_DURATION = 12000;

    private MobAnimations() {}

    /** {@code EVENT} : un evenement d'entite vanilla purement visuel (voir visualEvent), son numero dans {@code duration}. */
    public enum Kind { HURT, SWING, DEATH, POOF, INK, EVENT }

    /**
     * Evenements d'entite qui ne font qu'animer le modele (coup du golem, du ravageur, des hoglins, du warden, fleur
     * offerte, mouton qui broute). Les autres (coeurs, particules dans le monde du client...) ne sont pas rejoues.
     */
    public static boolean visualEvent(Entity entity, int id) {
        if (entity instanceof IronGolem) return id == 4 || id == 11 || id == 34;
        if (entity instanceof Ravager) return id == 4 || id == 39;
        if (entity instanceof Hoglin || entity instanceof Zoglin) return id == 4;
        if (entity instanceof Sheep) return id == 10;
        if (entity instanceof Warden) return id == 4 || id == 62;
        return false;
    }

    public record Event(long sequence, int entityId, int tick, Kind kind, boolean offHand, int duration) {
        public Event {
            if (sequence < 0 || kind == null || duration < 0 || duration > MAX_DURATION) {
                throw new IllegalArgumentException("Invalid mob animation event");
            }
        }
    }

    /** The phase at the packet's server tick; receiving this must not restart an animation. */
    public record Snapshot(int hurtTime, int hurtDuration, boolean dying, int deathTime,
                           boolean swinging, boolean offHand, int swingTime, int swingDuration) {
        public Snapshot {
            if (hurtTime < 0 || hurtTime > MAX_DURATION || hurtDuration < 0 || hurtDuration > MAX_DURATION
                    || deathTime < 0 || deathTime > MAX_DURATION || swingTime < -1 || swingTime > MAX_DURATION
                    || swingDuration < 1 || swingDuration > MAX_DURATION) {
                throw new IllegalArgumentException("Invalid mob animation snapshot");
            }
        }
    }
}

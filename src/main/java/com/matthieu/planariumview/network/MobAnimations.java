package com.matthieu.planariumview.network;

/** Visual-only state, independent of a client world and safe to share with the server. */
public final class MobAnimations {
    public static final int MAX_EVENTS = 512;
    public static final int MAX_DURATION = 12000;

    private MobAnimations() {}

    public enum Kind { HURT, SWING, DEATH, POOF }

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

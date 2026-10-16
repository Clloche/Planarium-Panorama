package com.matthieu.planariumview.server;

/** Tracks sounds emitted synchronously while Minecraft plays a sound caused by a player action. */
public final class PlayerSoundCause {
    private static final ThreadLocal<Integer> PLAYER_DEPTH = ThreadLocal.withInitial(() -> 0);

    private PlayerSoundCause() {
    }

    public static void beginPlayerSound() {
        PLAYER_DEPTH.set(PLAYER_DEPTH.get() + 1);
    }

    public static void endPlayerSound() {
        int depth = PLAYER_DEPTH.get();
        if (depth <= 1) {
            PLAYER_DEPTH.remove();
        } else {
            PLAYER_DEPTH.set(depth - 1);
        }
    }

    public static boolean isPlayerCaused() {
        return PLAYER_DEPTH.get() > 0;
    }

    /** Safety reset if a sound throws before its normal return injection runs. */
    public static void clear() {
        PLAYER_DEPTH.remove();
    }
}

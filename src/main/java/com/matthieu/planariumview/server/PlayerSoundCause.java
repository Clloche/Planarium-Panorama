package com.matthieu.planariumview.server;

/** Tracks sounds emitted synchronously while Minecraft plays a sound caused by a player action. */
public final class PlayerSoundCause {
    private static final ThreadLocal<Boolean> PLAYER_SOUND = new ThreadLocal<>();

    private PlayerSoundCause() {
    }

    public static void play(boolean playerCaused, Runnable sound) {
        if (!playerCaused || isPlayerCaused()) {
            sound.run();
            return;
        }
        PLAYER_SOUND.set(true);
        try {
            sound.run();
        } finally {
            PLAYER_SOUND.remove();
        }
    }

    public static boolean isPlayerCaused() {
        return Boolean.TRUE.equals(PLAYER_SOUND.get());
    }
}

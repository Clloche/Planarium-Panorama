package com.matthieu.planariumview.server;

/**
 * Temps de capture par tick, commun a tous les joueurs et aux deux systemes (anneau lointain, decor en vol). Les
 * decisions (deplacements, debut et fin du vol) se font toujours ; seules les captures attendent leur tour quand le
 * temps du tick est epuise. Une capture commencee (un chunk, une section) va jusqu'au bout.
 */
final class CaptureBudget {
    static final long PER_TICK_NANOS = 6_000_000L;
    private static long used;

    private CaptureBudget() {
    }

    static void reset() {
        used = 0L;
    }

    static boolean available() {
        return used < PER_TICK_NANOS;
    }

    static void spend(long nanos) {
        used += nanos;
    }
}

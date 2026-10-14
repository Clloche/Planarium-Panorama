package com.matthieu.planariumview;

import java.lang.reflect.Method;

/** Better Clouds (optionnel) : vrai s'il est installe et active (ses nuages remplacent les nuages vanilla). */
public final class BetterCloudsCompat {
    private static Method isEnabled;
    private static boolean looked;

    private BetterCloudsCompat() {
    }

    public static boolean installed() {
        lookup();
        return isEnabled != null;
    }

    /** Vrai si Better Clouds dessine ses nuages (mode Better Clouds), faux s'il n'est pas la ou en mode vanilla. */
    public static boolean active() {
        lookup();
        if (isEnabled == null) {
            return false;
        }
        try {
            return (Boolean) isEnabled.invoke(null);
        } catch (Throwable e) {
            return false;
        }
    }

    private static void lookup() {
        if (looked) {
            return;
        }
        looked = true;
        try {
            isEnabled = Class.forName("com.qendolin.betterclouds.BetterClouds").getMethod("isEnabled");
        } catch (Throwable e) {
            isEnabled = null;
        }
    }
}

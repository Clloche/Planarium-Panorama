package com.matthieu.planariumview.compat.simple_clouds;

import net.neoforged.fml.ModList;

/**
 * Simple Clouds et Better Simple Clouds (optionnels) : ils remplacent les nuages vanilla par leurs propres nuages
 * en 3D. Le bocal, lui, dessine des nuages vanilla : la description de l'option Nuages le signale.
 */
public final class SimpleCloudsCompat {
    private static Boolean installed;

    private SimpleCloudsCompat() {
    }

    public static boolean installed() {
        if (installed == null) {
            ModList mods = ModList.get();
            if (mods == null) {
                return false;
            }
            installed = mods.isLoaded("simpleclouds") || mods.isLoaded("bettersimpleclouds");
        }
        return installed;
    }
}

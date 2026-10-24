package com.matthieu.planariumview.compat;

import com.matthieu.planariumview.client.sky.SkySync;
import com.matthieu.planariumview.client.sound.OutsideAmbience;

import net.minecraft.resources.ResourceLocation;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Dynamic Surroundings remplace le biome par des "biomes d'altitude" quand on est tres haut : les nuages (vent)
 * a partir de cloudHeight (depuis la 0.4.6 : la hauteur ou la dimension dessine ses nuages, sauf regle), l'espace (silence) a partir de skyHeight + 32. Ces hauteurs dependent de la dimension
 * (fichier dimensions.json de Dynamic Surroundings et des packs).
 *
 * Dans le bocal, Dynamic Surroundings compare la hauteur du joueur (en bas du bocal) avec les reglages du bocal :
 * un Planarium tres haut (dans l'Aether par exemple) donnait donc les sons de la foret au lieu du vent.
 * On refait ici le meme calcul que Dynamic Surroundings (DimensionInfo), avec la dimension et la hauteur du Planarium.
 */
public final class DsAltitude {
    private static final int SPACE_HEIGHT_OFFSET = 32;

    /** La bibliotheque de dimensions de Dynamic Surroundings (voir DsDimensionLibraryMixin). */
    private static Object library;
    private static boolean failed;
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private static String lastLog;

    private DsAltitude() {
    }

    public static void setLibrary(Object instance) {
        library = instance;
    }

    /** "SPACE", "CLOUDS" ou null : le biome d'altitude que Dynamic Surroundings utiliserait a cote du Planarium. */
    public static String altitudeBiome() {
        ResourceLocation dim = OutsideAmbience.sourceDim();
        if (dim == null || failed) {
            return null;
        }
        int skyHeight = OutsideAmbience.sourceHeight();
        // Dynamic Surroundings 0.4.6+ : sans regle, la hauteur des nuages est celle ou la dimension dessine ses nuages
        // (rien si elle n'en a pas). Avant : le haut de la dimension.
        boolean modern = isModern();
        float vanillaClouds = SkySync.sourceCloudHeight();
        int defaultCloud = modern ? (Float.isNaN(vanillaClouds) ? Integer.MAX_VALUE : net.minecraft.util.Mth.floor(vanillaClouds))
                : skyHeight;
        int cloudHeight = defaultCloud;
        try {
            if (library != null) {
                java.lang.reflect.Field field = library.getClass().getDeclaredField("dimensionRules");
                field.setAccessible(true);
                Object rules = field.get(library);
                if (rules instanceof Iterable<?> iterable) {
                    for (Object rule : iterable) {
                        if (!dim.equals(call(rule, "dimensionId"))) {
                            continue;
                        }
                        Optional<?> sky = (Optional<?>) call(rule, "skyHeight");
                        if (sky.isPresent()) {
                            skyHeight = (Integer) sky.get();
                        }
                        Optional<?> cloud = (Optional<?>) call(rule, "cloudHeight");
                        if (cloud.isPresent()) {
                            cloudHeight = (Integer) cloud.get();
                        } else if (!modern) {
                            cloudHeight = skyHeight / 2;
                        }
                    }
                }
            }
        } catch (Throwable e) {
            failed = true;
            return null;
        }
        int y = OutsideAmbience.y();
        String result = y >= skyHeight + SPACE_HEIGHT_OFFSET ? "SPACE" : y >= cloudHeight ? "CLOUDS" : null;
        if (LOGGER.isDebugEnabled()) {
            String line = dim + " y " + y + " nuages " + (cloudHeight == Integer.MAX_VALUE ? "aucun" : String.valueOf(cloudHeight)) + " espace " + (skyHeight + SPACE_HEIGHT_OFFSET)
                    + " (regles DS " + (library != null ? "lues" : "introuvables") + ") : " + (result == null ? "biome normal" : result);
            if (!line.equals(lastLog)) {
                lastLog = line;
                LOGGER.debug("[Planarium Panorama] Altitude pour Dynamic Surroundings : {}", line);
            }
        }
        return result;
    }

    private static Boolean modernVersion;

    /** Vrai pour Dynamic Surroundings 0.4.6 et plus (hauteur des nuages tiree de la dimension). */
    private static boolean isModern() {
        if (modernVersion == null) {
            try {
                Class.forName("org.orecruncher.dsurround.config.DimensionInfo").getDeclaredField("configuredCloudHeight");
                modernVersion = true;
            } catch (Throwable e) {
                modernVersion = false;
            }
        }
        return modernVersion;
    }

    private static Object call(Object target, String name) throws ReflectiveOperationException {
        Method method = target.getClass().getMethod(name);
        return method.invoke(target);
    }
}

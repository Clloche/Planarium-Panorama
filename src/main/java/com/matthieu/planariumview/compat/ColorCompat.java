package com.matthieu.planariumview.compat;

import com.matthieu.planariumview.config.Config;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.BlockAndTintGetter;
import org.slf4j.Logger;

import java.lang.reflect.Method;

/**
 * Compatibilite avec la lumiere coloree de Contraption Lights (optionnel, par reflexion).
 *
 * Contraption Lights calcule la couleur de la lumiere en lisant les blocs du monde "source",
 * qui est toujours le monde du client. Pendant qu'on construit le rendu d'une photo
 * (decor geant ou miniature), on lui donne notre faux monde comme source, puis on remet le vrai.
 */
public final class ColorCompat {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Method SET_SOURCE = find();
    private static boolean active;

    private ColorCompat() {
    }

    private static Method find() {
        try {
            Class<?> engine = Class.forName("xyz.atmerek.contraptionlights.light.color.ColorLightEngine");
            return engine.getMethod("setSource", BlockAndTintGetter.class);
        } catch (Throwable e) {
            return null;
        }
    }

    /** Vrai pendant la construction du decor geant ou de la miniature (la source n'est pas le vrai monde). */
    public static boolean building() {
        return active;
    }

    public static boolean present() {
        return SET_SOURCE != null;
    }

    public static void begin(BlockAndTintGetter fakeWorld) {
        if (SET_SOURCE == null || fakeWorld == null || !Config.coloredLight()) {
            return;
        }
        // Une vue qui n'est pas un "Level", sinon Contraption Lights lit les chunks du vrai monde.
        BlockAndTintGetter source = fakeWorld instanceof net.minecraft.world.level.Level level ? new SourceView(level) : fakeWorld;
        if (set(source)) {
            active = true;
        }
    }

    public static void end() {
        if (!active) {
            return;
        }
        active = false;
        set(Minecraft.getInstance().level);
    }

    private static boolean set(BlockAndTintGetter level) {
        try {
            SET_SOURCE.invoke(null, level);
            return true;
        } catch (Throwable e) {
            LOGGER.debug("[Planarium View] Contraption Lights : impossible de changer la source", e);
            return false;
        }
    }
}

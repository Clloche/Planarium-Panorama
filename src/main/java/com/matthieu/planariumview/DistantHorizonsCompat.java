package com.matthieu.planariumview;

import com.hollingsworth.arsnouveau.ArsNouveau;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

/**
 * Distant Horizons dans un bocal : il dessinait le vide autour du bocal comme un terrain lointain (une grande bande
 * noire a l'horizon, par-dessus le decor geant). Dans un bocal, on lui demande de ne rien dessiner, par son API
 * (un reglage temporaire, jamais enregistre dans sa config). Il reprend des qu'on sort du bocal.
 */
public final class DistantHorizonsCompat {
    private static final Logger LOGGER = LoggerFactory.getLogger("planariumview");

    private static boolean failed;
    private static Object renderingEnabled;
    private static Method setValue;
    private static Method clearValue;
    /** Vrai quand on a coupe le rendu de Distant Horizons. */
    private static boolean disabled;

    private DistantHorizonsCompat() {
    }

    public static void init() {
        if (net.neoforged.fml.ModList.get() != null && net.neoforged.fml.ModList.get().isLoaded("distanthorizons")) {
            NeoForge.EVENT_BUS.addListener(DistantHorizonsCompat::onClientTick);
        }
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        if (failed) {
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        boolean inJar = level != null && Config.enabled()
                && level.dimensionTypeRegistration().is(ArsNouveau.DIMENSION_TYPE_KEY);
        if (inJar == disabled) {
            return;
        }
        if (!resolve()) {
            return;
        }
        try {
            if (inJar) {
                setValue.invoke(renderingEnabled, Boolean.FALSE);
            } else {
                clearValue.invoke(renderingEnabled);
            }
            disabled = inJar;
            LOGGER.info("[Planarium Panorama] Distant Horizons : rendu {}", inJar ? "coupe dans le bocal" : "rendu normal");
        } catch (Throwable e) {
            failed = true;
            LOGGER.warn("[Planarium Panorama] Impossible de couper Distant Horizons dans le bocal", e);
        }
    }

    /** DhApi.Delayed.configs.graphics().renderingEnabled() : disponible seulement une fois Distant Horizons pret. */
    private static boolean resolve() {
        if (renderingEnabled != null) {
            return true;
        }
        try {
            Class<?> delayed = Class.forName("com.seibel.distanthorizons.api.DhApi$Delayed");
            Object configs = delayed.getField("configs").get(null);
            if (configs == null) {
                return false;
            }
            // Par les interfaces publiques de l'API (les classes qui les implementent ne sont pas forcement publiques).
            Object graphics = Class.forName("com.seibel.distanthorizons.api.interfaces.config.IDhApiConfig")
                    .getMethod("graphics").invoke(configs);
            Object value = Class.forName("com.seibel.distanthorizons.api.interfaces.config.client.IDhApiGraphicsConfig")
                    .getMethod("renderingEnabled").invoke(graphics);
            Class<?> valueType = Class.forName("com.seibel.distanthorizons.api.interfaces.config.IDhApiConfigValue");
            setValue = valueType.getMethod("setValue", Object.class);
            clearValue = valueType.getMethod("clearValue");
            renderingEnabled = value;
            return true;
        } catch (Throwable e) {
            failed = true;
            LOGGER.warn("[Planarium Panorama] API de Distant Horizons introuvable", e);
            return false;
        }
    }
}

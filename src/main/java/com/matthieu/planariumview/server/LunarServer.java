package com.matthieu.planariumview.server;

import com.matthieu.planariumview.network.LunarPayload;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Enhanced Celestials 2 (cote serveur) : envoie au joueur dans un bocal le calendrier lunaire du monde d'origine.
 * Tout passe par reflexion : sans le mod, rien ne se passe.
 */
public final class LunarServer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static Boolean present;
    private static com.matthieu.planariumview.compat.SafeReflect.Call forecastOf;
    private static com.matthieu.planariumview.compat.SafeReflect.Call save;
    private static final Map<UUID, CompoundTag> LAST_SENT = new HashMap<>();

    private LunarServer() {
    }

    private static boolean present() {
        if (present == null) {
            present = false;
            if (ModList.get() != null && ModList.get().isLoaded("enhancedcelestials2core")) {
                try {
                    Class<?> main = Class.forName("dev.corgitaco.enhancedcelestials2core.EnhancedCelestials");
                    forecastOf = com.matthieu.planariumview.compat.SafeReflect.find(main, "lunarForecastWorldData", Level.class);
                    save = com.matthieu.planariumview.compat.SafeReflect.find(Class.forName("dev.corgitaco.enhancedcelestials2core.api.lunarevent.LunarForecast"), "save");
                    present = true;
                } catch (Throwable e) {
                    LOGGER.info("[Planarium Panorama] Enhanced Celestials : version non reconnue, evenements lunaires ignores");
                }
            }
        }
        return present;
    }

    /** Envoie le calendrier lunaire du monde d'origine, seulement s'il a change pour ce joueur. */
    public static void send(ServerPlayer player, ResourceLocation pocketDim, ServerLevel source) {
        if (!present()) {
            return;
        }
        try {
            Optional<?> forecast = (Optional<?>) forecastOf.invoke(null, source);
            if (forecast.isEmpty()) {
                return;
            }
            CompoundTag tag = (CompoundTag) save.invoke(forecast.get());
            if (tag == null || tag.equals(LAST_SENT.get(player.getUUID()))) {
                return;
            }
            LAST_SENT.put(player.getUUID(), tag.copy());
            PacketDistributor.sendToPlayer(player, new LunarPayload(pocketDim, source.dimension().location(), tag));
        } catch (Throwable e) {
            LOGGER.debug("[Planarium Panorama] Evenements lunaires non envoyes", e);
        }
    }

    /** Le joueur est sorti du bocal (ou parti) : on renverra tout la prochaine fois. */
    public static void forget(UUID player) {
        LAST_SENT.remove(player);
    }
}

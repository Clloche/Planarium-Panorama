package com.matthieu.planariumview;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Enhanced Celestials 2 (cote client) : dans un bocal, la lune du bocal suit l'evenement lunaire du monde d'origine
 * (lune de sang, lune bleue, super lune...). On fabrique pour le bocal un calendrier lunaire du mod, rempli avec
 * celui du monde d'origine (LunarPayload) ; il lit l'heure du bocal, qui est celle du monde d'origine.
 * Le mod le trouve ensuite comme celui de n'importe quel monde (voir EcForecastMixin). Tout passe par reflexion.
 */
public final class LunarClient {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static ResourceLocation pocketDim;
    private static ResourceLocation sourceDim;
    private static CompoundTag tag;
    private static boolean dirty;
    /** Calendrier du bocal, et le monde du client pour lequel il a ete fait. */
    private static Object forecast;
    private static ClientLevel forecastLevel;
    private static boolean failed;
    private static Method load;
    private static Method tick;

    private LunarClient() {
    }

    public static void init() {
        NeoForge.EVENT_BUS.addListener(LunarClient::onClientTick);
    }

    public static void receive(LunarPayload payload) {
        if (!payload.sourceDim().equals(sourceDim)) {
            forecast = null;
        }
        pocketDim = payload.pocketDim();
        sourceDim = payload.sourceDim();
        tag = payload.forecast();
        dirty = true;
    }

    public static void clear() {
        pocketDim = null;
        sourceDim = null;
        tag = null;
        forecast = null;
        forecastLevel = null;
    }

    private static boolean applies(Level level) {
        return level instanceof ClientLevel client && Config.lunarEvents() && tag != null && pocketDim != null
                && client.dimension().location().equals(pocketDim) && SkySync.skyActive(client);
    }

    /** Le calendrier lunaire a montrer dans ce monde, ou vide pour laisser Enhanced Celestials faire comme d'habitude. */
    public static Optional<Object> forecastFor(Level level) {
        if (failed || !applies(level)) {
            return Optional.empty();
        }
        ClientLevel client = (ClientLevel) level;
        try {
            if (forecast == null || forecastLevel != client) {
                forecast = create(client);
                forecastLevel = client;
                dirty = true;
            }
            if (dirty) {
                dirty = false;
                load.invoke(forecast, tag);
            }
            return Optional.of(forecast);
        } catch (Throwable e) {
            failed = true;
            forecast = null;
            LOGGER.info("[Planarium Panorama] Enhanced Celestials : evenements lunaires dans le bocal desactives ({})",
                    e.toString());
            return Optional.empty();
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object create(ClientLevel level) throws Exception {
        Class<?> main = Class.forName("dev.corgitaco.enhancedcelestials2core.EnhancedCelestials");
        Class<?> registries = Class.forName("dev.corgitaco.enhancedcelestials2core.api.EnhancedCelestialsRegistry");
        Class<?> forecastClass = Class.forName("dev.corgitaco.enhancedcelestials2core.api.lunarevent.LunarForecast");
        Object key = main.getField("LUNAR_FORECAST_WORLD_DATA").get(null);
        ResourceKey settingsKey = (ResourceKey) registries.getField("LUNAR_DIMENSION_SETTINGS_KEY").get(null);
        Registry settings = (Registry) level.registryAccess().registryOrThrow(settingsKey);
        Optional<Holder> holder = settings.getHolder(sourceDim);
        if (holder.isEmpty()) {
            throw new IllegalStateException("pas de reglages lunaires pour " + sourceDim);
        }
        Constructor<?> constructor = null;
        for (Constructor<?> candidate : forecastClass.getConstructors()) {
            if (candidate.getParameterCount() == 3) {
                constructor = candidate;
            }
        }
        if (constructor == null) {
            throw new NoSuchMethodException("LunarForecast(key, level, settings)");
        }
        load = forecastClass.getMethod("load", CompoundTag.class);
        tick = forecastClass.getMethod("tick");
        return constructor.newInstance(key, level, holder.get());
    }

    /** Enhanced Celestials fait avancer ses calendriers a chaque tick ; le notre aussi (passage d'un evenement a l'autre). */
    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (forecast == null || mc.level == null || mc.isPaused() || forecastLevel != mc.level || !applies(mc.level)) {
            return;
        }
        try {
            tick.invoke(forecast);
        } catch (Throwable e) {
            failed = true;
            forecast = null;
        }
    }
}

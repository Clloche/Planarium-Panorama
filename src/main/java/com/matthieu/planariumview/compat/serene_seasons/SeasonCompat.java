package com.matthieu.planariumview.compat.serene_seasons;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

import java.lang.reflect.Method;

/**
 * Serene Seasons, cote serveur (aucune classe client ici).
 * Tout passe par reflexion : le mod est optionnel et n'est pas necessaire pour compiler.
 */
public final class SeasonCompat {
    private static final Logger LOGGER = LogUtils.getLogger();
    static final TagKey<Biome> BLACKLISTED = tag("blacklisted_biomes");
    static final TagKey<Biome> LESSER_CHANGE = tag("lesser_color_change_biomes");
    static final TagKey<Biome> TROPICAL = tag("tropical_biomes");

    private static Boolean loaded;
    private static Method getSeasonState;
    private static Method getCycleTicks;
    private static Method precipitationAt;
    private static Object seasonsConfig;
    private static Method isWhitelisted;

    private SeasonCompat() {
    }

    private static TagKey<Biome> tag(String path) {
        return TagKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath("sereneseasons", path));
    }

    static boolean loaded() {
        if (loaded == null) {
            loaded = ModList.get().isLoaded("sereneseasons");
            if (loaded) {
                try {
                    Class<?> helper = Class.forName("sereneseasons.api.season.SeasonHelper");
                    getSeasonState = helper.getMethod("getSeasonState", Level.class);
                    getCycleTicks = Class.forName("sereneseasons.api.season.ISeasonState").getMethod("getSeasonCycleTicks");
                    precipitationAt = Class.forName("sereneseasons.season.SeasonHooks")
                            .getMethod("getPrecipitationAtSeasonal", Level.class, Holder.class, BlockPos.class);
                    Class<?> config = Class.forName("sereneseasons.init.ModConfig");
                    seasonsConfig = config.getField("seasons").get(null);
                    isWhitelisted = seasonsConfig.getClass().getMethod("isDimensionWhitelisted", ResourceKey.class);
                } catch (Throwable e) {
                    LOGGER.warn("[Planarium Panorama] Serene Seasons trouve, mais sa version n'est pas compatible", e);
                    loaded = false;
                }
            }
        }
        return loaded;
    }

    static Object seasonsConfig() {
        return seasonsConfig;
    }

    static boolean whitelisted(ResourceKey<Level> dimension) {
        try {
            return (Boolean) isWhitelisted.invoke(seasonsConfig, dimension);
        } catch (Throwable e) {
            return false;
        }
    }

    /** Ticks du cycle des saisons du monde d'origine, ou -1 si ce monde n'a pas de saisons. */
    public static int seasonTicks(ServerLevel level) {
        if (!loaded() || !whitelisted(level.dimension())) {
            return -1;
        }
        try {
            return (Integer) getCycleTicks.invoke(getSeasonState.invoke(null, level));
        } catch (Throwable e) {
            return -1;
        }
    }

    /**
     * Pluie, neige ou rien, en tenant compte de la saison (neige en hiver).
     * En solo, Serene Seasons redirige Biome.getPrecipitationAt vers le monde du joueur (le bocal) :
     * on appelle donc directement sa version qui prend le vrai monde.
     */
    public static Biome.Precipitation precipitation(ServerLevel level, Holder<Biome> biome, BlockPos pos) {
        if (loaded()) {
            try {
                return (Biome.Precipitation) precipitationAt.invoke(null, level, biome, pos);
            } catch (Throwable e) {
                // on retombe sur le calcul vanilla
            }
            if (!biome.value().getModifiedClimateSettings().hasPrecipitation()) {
                return Biome.Precipitation.NONE;
            }
            return biome.value().coldEnoughToSnow(pos) ? Biome.Precipitation.SNOW : Biome.Precipitation.RAIN;
        }
        return biome.value().getPrecipitationAt(pos);
    }
}

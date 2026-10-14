package com.matthieu.planariumview;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * AmbientSounds (optionnel) : dans un bocal, ses sons suivent l'endroit du Planarium dehors, comme pour
 * Dynamic Surroundings. La dimension, la meteo, la hauteur, le terrain et les blocs autour (feuilles, sable, eau...)
 * sont ceux du dehors. Tout passe par reflexion : les champs d'AmbientSounds changent d'une version a l'autre.
 */
public final class AmbientSoundsCompat {
    private static final Map<String, Field> FIELDS = new java.util.concurrent.ConcurrentHashMap<>();
    private static boolean failed;

    private AmbientSoundsCompat() {
    }

    /** Vrai si on est dans un bocal dont on connait le dehors, avec l'option AmbientSounds. */
    public static boolean active() {
        ClientLevel level = Minecraft.getInstance().level;
        return !failed && level != null && Config.ambientSounds() && OutsideAmbience.known() && SkySync.inKnownJar(level);
    }

    public static boolean active(Level level) {
        return level instanceof ClientLevel && active();
    }

    private static Field field(Object target, String name) throws NoSuchFieldException {
        String key = target.getClass().getName() + "#" + name;
        Field field = FIELDS.get(key);
        if (field == null) {
            Class<?> type = target.getClass();
            while (true) {
                try {
                    field = type.getDeclaredField(name);
                    break;
                } catch (NoSuchFieldException e) {
                    type = type.getSuperclass();
                    if (type == null) {
                        throw e;
                    }
                }
            }
            field.setAccessible(true);
            FIELDS.put(key, field);
        }
        return field;
    }

    private static Object get(Object target, String name) throws Exception {
        return field(target, name).get(target);
    }

    private static void set(Object target, String name, Object value) {
        try {
            Field field = field(target, name);
            if (field.getType() == double.class) {
                field.setDouble(target, ((Number) value).doubleValue());
            } else if (field.getType() == int.class) {
                field.setInt(target, ((Number) value).intValue());
            } else if (field.getType() == boolean.class) {
                field.setBoolean(target, (Boolean) value);
            } else {
                field.set(target, value);
            }
        } catch (Exception ignored) {
            // champ absent dans cette version d'AmbientSounds
        }
    }

    // ------------------------------------------------------------- dimension

    /** La dimension d'AmbientSounds pour la dimension du Planarium, ou null pour garder la normale. */
    public static Object dimension(Object engine) {
        if (!active()) {
            return null;
        }
        net.minecraft.resources.ResourceLocation source = OutsideAmbience.sourceDim();
        if (source == null) {
            return null;
        }
        String name = source.toString();
        try {
            List<?> silent = (List<?>) get(engine, "silentDimensions");
            for (Object pattern : silent) {
                if (name.matches(".*" + pattern.toString().toLowerCase().replace("*", ".*").replace("?", "\\?") + ".*")) {
                    return get(engine, "silentDim");
                }
            }
            Map<?, ?> dimensions = (Map<?, ?>) get(engine, "dimensions");
            for (Object dimension : dimensions.values()) {
                if (matches(dimension, name)) {
                    return dimension;
                }
            }
            return get(engine, "silentDim");
        } catch (Exception e) {
            failed = true;
            return null;
        }
    }

    private static boolean matches(Object dimension, String name) throws Exception {
        Pattern[] bad = (Pattern[]) get(dimension, "badDimensionPatterns");
        if (bad != null) {
            for (Pattern pattern : bad) {
                if (pattern.matcher(name).matches()) {
                    return false;
                }
            }
        }
        Pattern[] good = (Pattern[]) get(dimension, "dimensionPatterns");
        if (good != null) {
            for (Pattern pattern : good) {
                if (pattern.matcher(name).matches()) {
                    return true;
                }
            }
        }
        return get(dimension, "dimensionNames") == null;
    }

    // ------------------------------------------------------------- meteo et hauteur

    private static boolean rainingOutside() {
        ClientLevel level = Minecraft.getInstance().level;
        return level != null && SkySync.rainLevel(level) > 0.2F;
    }

    /** Pluie qui tombe au Planarium (pas a l'abri), pour les sons de pluie d'AmbientSounds. */
    public static boolean isRaining() {
        return rainingOutside() && !OutsideAmbience.sheltered();
    }

    /** Apres l'analyse rapide d'AmbientSounds (chaque tick) : meteo et hauteur du dehors. */
    public static void afterFast(Object environment) {
        if (!active()) {
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        int precip = WeatherView.precipitationAtPlanarium();
        boolean open = !OutsideAmbience.sheltered();
        boolean raining = rainingOutside();
        boolean snowing = raining && precip == 2;
        set(environment, "raining", raining && open && precip != 2 && precip != 0);
        set(environment, "snowing", snowing);
        set(environment, "thundering", SkySync.thunderLevel(level) > 0.9F && !snowing);
        try {
            Object terrain = get(environment, "terrain");
            double average = ((Number) get(terrain, "averageHeight")).doubleValue();
            double min = ((Number) get(terrain, "minHeight")).doubleValue();
            double max = ((Number) get(terrain, "maxHeight")).doubleValue();
            double absolute = OutsideAmbience.y() + 1.6;
            set(environment, "absoluteHeight", absolute);
            set(environment, "relativeHeight", absolute - average);
            set(environment, "relativeMinHeight", absolute - max);
            set(environment, "relativeMaxHeight", absolute - min);
        } catch (Exception ignored) {
            // hauteur normale
        }
    }

    /** Hauteur du terrain autour du Planarium (au lieu de celle du bocal). Vrai si elle a ete donnee. */
    public static boolean terrainHeight(Object terrain) {
        if (!active()) {
            return false;
        }
        double[] heights = WeatherView.terrainHeights();
        if (heights == null) {
            return false;
        }
        set(terrain, "averageHeight", heights[0]);
        set(terrain, "minHeight", (int) heights[1]);
        set(terrain, "maxHeight", (int) heights[2]);
        return true;
    }

    // ------------------------------------------------------------- blocs autour

    /** Le monde ou chercher les blocs autour (feuilles, sable, eau...) : la photo du dehors dans un bocal. */
    public static Level scanLevel(Level level) {
        if (!active(level)) {
            return level;
        }
        Level outside = ClientView.outsideWorld();
        return outside != null ? outside : level;
    }

    public static BlockPos scanOrigin(BlockPos pos) {
        if (!active() || ClientView.outsideWorld() == null) {
            return pos;
        }
        return ClientView.outsideAbovePlanarium();
    }
}

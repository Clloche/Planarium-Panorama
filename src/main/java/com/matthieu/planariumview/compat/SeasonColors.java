package com.matthieu.planariumview.compat;

import com.matthieu.planariumview.client.scenery.ClientView;
import com.matthieu.planariumview.client.sky.SkySync;
import com.matthieu.planariumview.config.Config;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Serene Seasons, cote client : dans le bocal, l'herbe et les feuilles prennent la couleur
 * de la saison du monde d'origine (decor geant et interieur du bocal).
 *
 * Serene Seasons colore selon le monde ou se trouve le joueur. Le bocal n'a pas de saisons,
 * donc il laisse la couleur de base. On ajoute par-dessus ses couleurs une etape qui applique
 * la saison du monde d'origine (envoyee par le serveur), avec les memes calculs que Serene Seasons.
 */
public final class SeasonColors {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** [grassOverlay, foliageOverlay] et [grassSat, foliageSat], pour la sous-saison et la saison tropicale. */
    private static volatile Values values;
    /** Sous-saison du monde d'origine (objet Serene Seasons), et son nom et celui de la saison. */
    private static volatile Object subSeason;
    private static volatile String subSeasonName;
    private static volatile String seasonName;
    private static java.lang.reflect.Method temperatureInSeason;
    private static int[] lastKey;
    private static boolean installed;
    private static final Map<Biome, Integer> FLAGS = new ConcurrentHashMap<>();
    private static final int BLACKLISTED = 1;
    private static final int TROPICAL = 2;
    private static final int LESSER = 4;

    private record Values(int subGrass, float subGrassSat, int subFoliage, float subFoliageSat,
                          int tropGrass, float tropGrassSat, int tropFoliage, float tropFoliageSat) {
        public int[] key() {
            return new int[]{subGrass, Float.floatToIntBits(subGrassSat), subFoliage, Float.floatToIntBits(subFoliageSat),
                    tropGrass, Float.floatToIntBits(tropGrassSat), tropFoliage, Float.floatToIntBits(tropFoliageSat)};
        }
    }

    private SeasonColors() {
    }

    public static void init() {
        NeoForge.EVENT_BUS.addListener(SeasonColors::onClientTick);
    }

    /** Ajoute notre etape apres celle de Serene Seasons (une seule fois, quand ses couleurs sont en place). */
    private static void onClientTick(ClientTickEvent.Post event) {
        if (installed || !SeasonCompat.loaded()) {
            return;
        }
        installed = true;
        try {
            ColorResolver grass = BiomeColors.GRASS_COLOR_RESOLVER;
            ColorResolver foliage = BiomeColors.FOLIAGE_COLOR_RESOLVER;
            Field grassField = BiomeColors.class.getField("GRASS_COLOR_RESOLVER");
            Field foliageField = BiomeColors.class.getField("FOLIAGE_COLOR_RESOLVER");
            grassField.set(null, (ColorResolver) (biome, x, z) -> apply(true, biome, grass.getColor(biome, x, z)));
            foliageField.set(null, (ColorResolver) (biome, x, z) -> apply(false, biome, foliage.getColor(biome, x, z)));
        } catch (Throwable e) {
            LOGGER.warn("[Planarium Panorama] Impossible d'ajouter les couleurs de saison de Serene Seasons", e);
        }
    }

    /** Saison du monde d'origine (ticks du cycle), ou -1 s'il n'y en a pas. */
    public static void receive(int ticks) {
        Values next = ticks < 0 || !SeasonCompat.loaded() ? null : compute(ticks);
        if (next == null) {
            subSeason = null;
            subSeasonName = null;
            seasonName = null;
        }
        values = next;
        int[] key = next == null ? null : next.key();
        if (!Arrays.equals(key, lastKey)) {
            lastKey = key;
            // Nouvelle saison (ou entree dans le bocal) : on recolore le bocal et le decor.
            Minecraft mc = Minecraft.getInstance();
            if (SkySync.inKnownJar(mc.level) && Config.biomeColors()) {
                mc.levelRenderer.allChanged();
                ClientView.rebuild();
            }
        }
    }

    private static Values compute(int ticks) {
        try {
            Object time = Class.forName("sereneseasons.season.SeasonTime").getConstructor(int.class).newInstance(ticks);
            Object sub = time.getClass().getMethod("getSubSeason").invoke(time);
            subSeason = sub;
            subSeasonName = ((Enum<?>) sub).name();
            seasonName = ((Enum<?>) sub.getClass().getMethod("getSeason").invoke(sub)).name();
            Object trop = time.getClass().getMethod("getTropicalSeason").invoke(time);
            Object config = SeasonCompat.seasonsConfig();
            boolean changeGrass = config.getClass().getField("changeGrassColor").getBoolean(config);
            boolean changeFoliage = config.getClass().getField("changeFoliageColor").getBoolean(config);
            Object summer = midSummer();
            Object grassSub = changeGrass ? sub : summer;
            Object grassTrop = changeGrass ? trop : summer;
            Object foliageSub = changeFoliage ? sub : summer;
            Object foliageTrop = changeFoliage ? trop : summer;
            return new Values(
                    intCall(grassSub, "getGrassOverlay"), floatCall(grassSub, "getGrassSaturationMultiplier"),
                    intCall(foliageSub, "getFoliageOverlay"), floatCall(foliageSub, "getFoliageSaturationMultiplier"),
                    intCall(grassTrop, "getGrassOverlay"), floatCall(grassTrop, "getGrassSaturationMultiplier"),
                    intCall(foliageTrop, "getFoliageOverlay"), floatCall(foliageTrop, "getFoliageSaturationMultiplier"));
        } catch (Throwable e) {
            LOGGER.debug("[Planarium Panorama] Saison de Serene Seasons illisible", e);
            return null;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object midSummer() throws ClassNotFoundException {
        Class<? extends Enum> sub = (Class<? extends Enum>) Class.forName("sereneseasons.api.season.Season$SubSeason");
        return Enum.valueOf(sub, "MID_SUMMER");
    }

    private static int intCall(Object target, String name) throws ReflectiveOperationException {
        Method m = target.getClass().getMethod(name);
        return (Integer) m.invoke(target);
    }

    private static float floatCall(Object target, String name) throws ReflectiveOperationException {
        Method m = target.getClass().getMethod(name);
        return (Float) m.invoke(target);
    }

    // ------------------------------------------------------------------ Dynamic Surroundings

    /** Saison du monde d'origine pour Dynamic Surroundings (SPRING, SUMMER...), ou null si on ne la connait pas. */
    public static String outsideSeason() {
        return SkySync.inKnownJar(Minecraft.getInstance().level) ? seasonName : null;
    }

    /** Sous-saison du monde d'origine (EARLY_SPRING...), ou null. */
    public static String outsideSubSeason() {
        return SkySync.inKnownJar(Minecraft.getInstance().level) ? subSeasonName : null;
    }

    /** Temperature du biome exterieur selon la saison du monde d'origine (comme Serene Seasons), ou NaN. */
    public static float outsideTemperature(Holder<Biome> biome, net.minecraft.core.BlockPos pos) {
        Object sub = subSeason;
        if (sub == null || biome == null || !SkySync.inKnownJar(Minecraft.getInstance().level)) {
            return Float.NaN;
        }
        try {
            if (temperatureInSeason == null) {
                temperatureInSeason = Class.forName("sereneseasons.season.SeasonHooks").getMethod("getBiomeTemperatureInSeason",
                        sub.getClass(), Holder.class, net.minecraft.core.BlockPos.class);
            }
            return (Float) temperatureInSeason.invoke(null, sub, biome, pos);
        } catch (Throwable e) {
            return Float.NaN;
        }
    }

    // ------------------------------------------------------------------ couleur

    private static int apply(boolean grass, Biome biome, int color) {
        Values v = values;
        if (v == null || !Config.biomeColors()) {
            return color;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (!SkySync.inKnownJar(level) || SeasonCompat.whitelisted(level.dimension())) {
            // Hors du bocal (ou bocal deja gere par Serene Seasons) : on ne touche a rien.
            return color;
        }
        int flags = FLAGS.computeIfAbsent(biome, b -> flags(level, b));
        if ((flags & BLACKLISTED) != 0) {
            return color;
        }
        boolean tropical = (flags & TROPICAL) != 0;
        int overlay = grass ? (tropical ? v.tropGrass() : v.subGrass()) : (tropical ? v.tropFoliage() : v.subFoliage());
        float saturation = grass ? (tropical ? v.tropGrassSat() : v.subGrassSat()) : (tropical ? v.tropFoliageSat() : v.subFoliageSat());
        int result = overlay == 0xFFFFFF ? color : overlayBlend(color, overlay);
        if ((flags & LESSER) != 0) {
            result = mix(result, color, 0.75F);
        }
        return saturation != -1 ? saturate(result, saturation) : result;
    }

    private static int flags(ClientLevel level, Biome biome) {
        Holder<Biome> holder = level.registryAccess().registryOrThrow(Registries.BIOME).wrapAsHolder(biome);
        int flags = 0;
        if (holder.is(SeasonCompat.BLACKLISTED)) {
            flags |= BLACKLISTED;
        }
        if (holder.is(SeasonCompat.TROPICAL)) {
            flags |= TROPICAL;
        }
        if (holder.is(SeasonCompat.LESSER_CHANGE)) {
            flags |= LESSER;
        }
        return flags;
    }

    // Memes calculs que sereneseasons.util.SeasonColorUtil.

    private static int multiply(int a, int b) {
        return (int) ((a / 255.0F) * (b / 255.0F) * 255.0F);
    }

    private static int overlayChannel(int under, int over) {
        if (under < 128) {
            return multiply(2 * under, over);
        }
        return 255 - multiply(2 * (255 - under), 255 - over);
    }

    private static int overlayBlend(int under, int over) {
        int r = overlayChannel((under >> 16) & 255, (over >> 16) & 255);
        int g = overlayChannel((under >> 8) & 255, (over >> 8) & 255);
        int b = overlayChannel(under & 255, over & 255);
        return (r & 255) << 16 | (g & 255) << 8 | (b & 255);
    }

    private static int mix(int a, int b, float ratio) {
        ratio = Math.max(0.0F, Math.min(1.0F, ratio));
        float keep = 1.0F - ratio;
        int alpha = (int) (((a >> 24) & 255) * keep + ((b >> 24) & 255) * ratio);
        int red = (int) (((a >> 16) & 255) * keep + ((b >> 16) & 255) * ratio);
        int green = (int) (((a >> 8) & 255) * keep + ((b >> 8) & 255) * ratio);
        int blue = (int) ((a & 255) * keep + (b & 255) * ratio);
        return alpha << 24 | red << 16 | green << 8 | blue;
    }

    private static int saturate(int color, float multiplier) {
        double r = ((color >> 16) & 255) / 255.0;
        double g = ((color >> 8) & 255) / 255.0;
        double b = (color & 255) / 255.0;
        double max = Math.max(r, Math.max(g, b));
        double min = Math.min(r, Math.min(g, b));
        double delta = max - min;
        double h;
        double s;
        if (delta < 0.00001 || max <= 0.0) {
            h = 0.0;
            s = 0.0;
        } else {
            s = delta / max;
            if (r >= max) {
                h = (g - b) / delta;
            } else if (g >= max) {
                h = 2.0 + (b - r) / delta;
            } else {
                h = 4.0 + (r - g) / delta;
            }
            h *= 60.0;
            if (h < 0.0) {
                h += 360.0;
            }
        }
        s *= multiplier;
        double v = max;
        double outR;
        double outG;
        double outB;
        if (s <= 0.0) {
            outR = v;
            outG = v;
            outB = v;
        } else {
            double hh = h >= 360.0 ? 0.0 : h / 60.0;
            int i = (int) hh;
            double ff = hh - i;
            double p = v * (1.0 - s);
            double q = v * (1.0 - s * ff);
            double t = v * (1.0 - s * (1.0 - ff));
            switch (i) {
                case 0 -> { outR = v; outG = t; outB = p; }
                case 1 -> { outR = q; outG = v; outB = p; }
                case 2 -> { outR = p; outG = v; outB = t; }
                case 3 -> { outR = p; outG = q; outB = v; }
                case 4 -> { outR = t; outG = p; outB = v; }
                default -> { outR = v; outG = p; outB = q; }
            }
        }
        int ir = (int) (outR * 255.0);
        int ig = (int) (outG * 255.0);
        int ib = (int) (outB * 255.0);
        return (ir & 255) << 16 | (ig & 255) << 8 | (ib & 255);
    }
}

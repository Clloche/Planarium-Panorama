package com.matthieu.planariumview;

import com.mojang.logging.LogUtils;
import net.minecraft.util.Mth;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Couleur de la lumiere a l'interieur du bocal, pour Contraption Lights.
 *
 * Contraption Lights colore la lumiere en la propageant depuis les sources (ici les parois du bocal),
 * et cette propagation s'arrete a quelques blocs : le milieu du bocal restait eclaire, mais sans couleur.
 * On donne donc a chaque bloc de l'interieur la couleur des faces qui l'eclairent (melangees selon leur lumiere
 * et leur distance), avec la luminosite calculee par JarLight. Une lumiere placee dans le bocal et plus forte
 * garde sa propre couleur.
 *
 * Les couleurs sont encodees avec les fonctions de Contraption Lights (LightColorMath), par reflexion.
 */
public final class JarColor {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int SIZE = 32;
    private static final int HEIGHT = 33;

    /** Cellule de couleur de chaque bloc de l'interieur (0 = aucune), ou null a recalculer. */
    private static volatile short[] cells;
    private static volatile int version;
    private static boolean failed;
    private static Method srgbToLinear;
    private static Method brightness;
    private static Method toCell;

    /** Sections deja corrigees : l'originale de Contraption Lights et notre copie. */
    private record Patched(short[] original, int version, short[] result) {
    }

    private static final Map<Long, Patched> PATCHED = new ConcurrentHashMap<>();

    private JarColor() {
    }

    public static void invalidate() {
        cells = null;
        version++;
        PATCHED.clear();
    }

    /**
     * Appele par Contraption Lights pour chaque section de couleurs (voir ColorSectionMixin).
     * Ne modifie jamais le tableau recu (il peut etre partage) : on renvoie une copie corrigee, gardee en cache.
     */
    public static short[] apply(short[] original, int sx, int sy, int sz) {
        if (sx < 0 || sx > 1 || sy < 0 || sy > 2 || sz < 0 || sz > 1 || failed || !Config.coloredLight()) {
            return original;
        }
        if (ColorCompat.building()) {
            return applyMini(original, sx, sy, sz);
        }
        short[] interior = cells();
        if (interior == null) {
            return original;
        }
        long key = net.minecraft.core.SectionPos.asLong(sx, sy, sz);
        int currentVersion = version;
        Patched cached = PATCHED.get(key);
        if (cached != null && cached.original() == original && cached.version() == currentVersion) {
            return cached.result();
        }
        short[] result = original != null && original.length >= 4096 ? original.clone() : new short[4096];
        boolean changed = false;
        for (int ly = 0; ly < 16; ly++) {
            int y = (sy << 4) + ly;
            if (y >= HEIGHT) {
                break;
            }
            for (int lz = 0; lz < 16; lz++) {
                int z = (sz << 4) + lz;
                for (int lx = 0; lx < 16; lx++) {
                    int x = (sx << 4) + lx;
                    int ours = interior[(x * HEIGHT + y) * SIZE + z] & 0xFFFF;
                    if (ours == 0) {
                        continue;
                    }
                    int index = ly << 8 | lz << 4 | lx;
                    int existing = result[index] & 0xFFFF;
                    if (((existing >> 12) & 15) > ((ours >> 12) & 15)) {
                        continue; // une lumiere du bocal plus forte garde sa couleur
                    }
                    result[index] = (short) ours;
                    if (result.length > 4096 + index) {
                        result[4096 + index] = 0;
                    }
                    changed = true;
                }
            }
        }
        if (!changed) {
            result = original;
        }
        PATCHED.put(key, new Patched(original, currentVersion, result));
        return result;
    }

    private static short[] cells() {
        short[] current = cells;
        if (current != null) {
            return current.length == 0 ? null : current;
        }
        current = build();
        cells = current;
        return current.length == 0 ? null : current;
    }

    private static short[] build() {
        return buildCells(WallColors.faceLight(), WallColors.faceColors(), null);
    }

    /**
     * Couleur de chaque bloc de l'interieur (coordonnees du bocal : x 0-31, y 0-32, z 0-31), a partir de la lumiere
     * et de la couleur qui entrent par chaque face. {@code levels} : lumiere de chaque bloc (JarLight.computeTable),
     * ou null pour celle du bocal ou se trouve le joueur.
     */
    static short[] buildCells(int[] faces, int[] colors, byte[] levels) {
        if (faces == null || colors == null || !reflect()) {
            return new short[0];
        }
        boolean any = false;
        for (int face : faces) {
            any |= face > 0;
        }
        if (!any) {
            return new short[0];
        }
        try {
            short[] next = new short[SIZE * HEIGHT * SIZE];
            float[] value = new float[3];
            for (int x = 0; x < SIZE; x++) {
                float fx = (x + 0.5F) / SIZE;
                for (int y = 0; y < HEIGHT; y++) {
                    float fy = y / (float) (HEIGHT - 1);
                    boolean low = y < JarWalls.SPLIT_Y;
                    for (int z = 0; z < SIZE; z++) {
                        int level = levels != null ? levels[(x * HEIGHT + y) * SIZE + z] : JarLight.interiorLevel(x, y, z);
                        if (level <= 0) {
                            continue;
                        }
                        float fz = (z + 0.5F) / SIZE;
                        float r = 0;
                        float g = 0;
                        float b = 0;
                        float total = 0;
                        int[] faceIndex = {low ? 0 : 1, low ? 2 : 3, low ? 4 : 5, low ? 6 : 7, 8, 9};
                        float[] near = {1 - fx, fx, 1 - fz, fz, 1 - fy, fy};
                        for (int i = 0; i < 6; i++) {
                            int f = faceIndex[i];
                            float w = faces[f] * near[i] * near[i];
                            if (w <= 0) {
                                continue;
                            }
                            int c = colors[f];
                            r += ((c >> 16) & 255) * w;
                            g += ((c >> 8) & 255) * w;
                            b += (c & 255) * w;
                            total += w;
                        }
                        if (total <= 0) {
                            continue;
                        }
                        float strength = (Float) brightness.invoke(null, (double) level);
                        float lr = (Float) srgbToLinear.invoke(null, Mth.clamp(r / total / 255F, 0F, 1F));
                        float lg = (Float) srgbToLinear.invoke(null, Mth.clamp(g / total / 255F, 0F, 1F));
                        float lb = (Float) srgbToLinear.invoke(null, Mth.clamp(b / total / 255F, 0F, 1F));
                        float max = Math.max(lr, Math.max(lg, lb));
                        if (max <= 0) {
                            continue;
                        }
                        value[0] = lr / max * strength;
                        value[1] = lg / max * strength;
                        value[2] = lb / max * strength;
                        next[(x * HEIGHT + y) * SIZE + z] = (short) (int) (Integer) toCell.invoke(null, value, 0);
                    }
                }
            }
            return next;
        } catch (Throwable e) {
            failed = true;
            LOGGER.info("[Planarium Panorama] Couleur de l'interieur du bocal indisponible avec cette version de Contraption Lights", e);
            return new short[0];
        }
    }

    /** Couleurs de la miniature en construction (voir MiniOutsideLight), ou null. */
    private static volatile short[] miniCells;
    private static final Map<Long, Patched> MINI_PATCHED = new ConcurrentHashMap<>();

    /** Au debut de la construction d'une miniature : la couleur de la lumiere du dehors, comme dans le bocal. */
    static void setMini(short[] cells) {
        MINI_PATCHED.clear();
        miniCells = cells != null && cells.length > 0 ? cells : null;
    }

    public static void clearMini() {
        miniCells = null;
        MINI_PATCHED.clear();
    }

    /**
     * Comme apply, pour la miniature : ses positions sont celles du gabarit d'Ars, qui commence un bloc au-dessus
     * du plancher du bocal (y du gabarit + 1 = y du bocal).
     */
    private static short[] applyMini(short[] original, int sx, int sy, int sz) {
        short[] interior = miniCells;
        if (interior == null) {
            return original;
        }
        long key = net.minecraft.core.SectionPos.asLong(sx, sy, sz);
        Patched cached = MINI_PATCHED.get(key);
        if (cached != null && cached.original() == original) {
            return cached.result();
        }
        short[] result = original != null && original.length >= 4096 ? original.clone() : new short[4096];
        boolean changed = false;
        for (int ly = 0; ly < 16; ly++) {
            int y = (sy << 4) + ly + 1;
            if (y >= HEIGHT) {
                break;
            }
            for (int lz = 0; lz < 16; lz++) {
                int z = (sz << 4) + lz;
                for (int lx = 0; lx < 16; lx++) {
                    int x = (sx << 4) + lx;
                    int ours = interior[(x * HEIGHT + y) * SIZE + z] & 0xFFFF;
                    if (ours == 0) {
                        continue;
                    }
                    int index = ly << 8 | lz << 4 | lx;
                    int existing = result[index] & 0xFFFF;
                    if (((existing >> 12) & 15) > ((ours >> 12) & 15)) {
                        continue;
                    }
                    result[index] = (short) ours;
                    if (result.length > 4096 + index) {
                        result[4096 + index] = 0;
                    }
                    changed = true;
                }
            }
        }
        if (!changed) {
            result = original;
        }
        MINI_PATCHED.put(key, new Patched(original, 0, result));
        return result;
    }

    private static boolean tagFailed;
    private static Method tintMethod;
    private static Method fromLevels;

    /**
     * Lumiere packee avec la couleur {@code rgb} (0xRRGGBB) pour Contraption Lights, comme ColorLightFeature.tag :
     * la teinte est ajoutee aux niveaux de lumiere. Sans Contraption Lights, la lumiere reste sans couleur.
     */
    static int tagColor(int packed, int rgb) {
        if (tagFailed || failed || !reflect()) {
            return packed;
        }
        try {
            if (tintMethod == null) {
                Class<?> math = Class.forName("xyz.atmerek.contraptionlights.light.color.LightColorMath");
                tintMethod = math.getMethod("tint", float.class, float.class, float.class);
                fromLevels = Class.forName("xyz.atmerek.contraptionlights.light.color.ColorLightPayload")
                        .getMethod("fromLevels", int.class);
            }
            float r = (Float) srgbToLinear.invoke(null, ((rgb >> 16) & 255) / 255F);
            float g = (Float) srgbToLinear.invoke(null, ((rgb >> 8) & 255) / 255F);
            float b = (Float) srgbToLinear.invoke(null, (rgb & 255) / 255F);
            int tint = (Integer) tintMethod.invoke(null, r, g, b);
            if (tint == 0) {
                return packed;
            }
            return packed | (Integer) fromLevels.invoke(null, tint);
        } catch (Throwable e) {
            tagFailed = true;
            return packed;
        }
    }

    private static boolean reflect() {
        if (srgbToLinear != null) {
            return true;
        }
        if (failed) {
            return false;
        }
        try {
            Class<?> math = Class.forName("xyz.atmerek.contraptionlights.light.color.LightColorMath");
            srgbToLinear = math.getMethod("srgbToLinear", float.class);
            brightness = math.getMethod("brightness", double.class);
            toCell = math.getMethod("toCell", float[].class, int.class);
            return true;
        } catch (Throwable e) {
            failed = true;
            return false;
        }
    }
}

package com.matthieu.planariumview.compat;

import com.matthieu.planariumview.config.Config;

import com.mojang.logging.LogUtils;
import net.minecraft.world.level.BlockAndTintGetter;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.WeakHashMap;
import java.util.Map;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Compatibilite avec la lumiere coloree de Contraption Lights (optionnel, par reflexion).
 *
 * Contraption Lights calcule la couleur de la lumiere en lisant les blocs du monde "source",
 * qui est toujours le monde du client. Pendant qu'on construit le rendu d'une photo
 * (decor geant ou miniature), il doit lire notre faux monde a la place.
 *
 * Methode principale (ClColorSectionMixin) : seul le thread qui construit voit le faux monde, decale tres loin du
 * vrai. Les autres threads (Sodium qui construit les chunks du bocal en parallele) gardent le vrai monde, et le cache
 * de Contraption Lights, range par position seulement, ne melange pas les deux. Avant, on changeait la source pour
 * tout le monde (setSource) : un chunk du bocal construit au meme moment perdait ses couleurs (torches blanches
 * quelques secondes), et chaque changement vidait tout le cache.
 *
 * Aucun appel a setSource, meme au premier chargement : cette methode reintroduirait le melange entre les threads.
 * Si la signature de colorSection change, la redirection est desactivee plutot que de changer la source globale.
 */
public final class ColorCompat {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean COMPATIBLE = supportsSections();
    private static final Method CLEAR = find("clear");
    private static final Method INVALIDATE = find("invalidate", int.class, int.class, int.class);
    /**
     * Emplacement de chaque faux monde deja vu : toutes les constructions d'une meme photo (50 sections, blocs
     * speciaux, couleurs des particules) partagent le cache de Contraption Lights. Avant, chaque begin() prenait un
     * emplacement neuf : Contraption Lights recherchait les sources de lumiere (scan) de chaque section des dizaines de
     * fois par photo, environ 80 % du temps de construction des sections. Une nouvelle photo cree un nouveau faux monde,
     * donc un emplacement neuf ; les changements faits dans un faux monde existant passent par blocksChanged.
     */
    private static final Map<Object, int[]> WORLD_SLOTS = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Decalages du faux monde : loin au-dela de la bordure du monde (30 M), mais sous la limite des positions de
     * section (2^21 sections, environ 33,5 M blocs). Chaque faux monde a sa case (voir WORLD_SLOTS) : une nouvelle
     * photo n'herite jamais des couleurs d'une autre.
     */
    private static final int BASE = 30_720_000;
    /** Le faux monde fait au plus 65 blocs de cote (rayon 32), plus une section de voisins de chaque cote. */
    private static final int SLOT = 512;
    private static final int SLOTS = (33_500_000 - BASE) / SLOT;

    private static final AtomicLong NEXT_SLOT = new AtomicLong();
    private static final ThreadLocal<Context> CONTEXT = new ThreadLocal<>();

    /** Une construction imbriquee doit restaurer la precedente a son end(), meme si elle est desactivee. */
    private record Context(Context previous, SourceView view, int offsetX, int offsetZ) {
    }

    private ColorCompat() {
    }

    private static boolean supportsSections() {
        try {
            Class<?> engine = Class.forName("xyz.atmerek.contraptionlights.light.color.ColorLightEngine");
            Class<?> scratch = Class.forName("xyz.atmerek.contraptionlights.light.color.ColorLightEngine$Scratch");
            // Les trois ModifyVariable ciblent uniquement les arguments de cette signature.
            engine.getDeclaredMethod("colorSection", scratch, BlockAndTintGetter.class,
                    int.class, int.class, int.class, boolean.class);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        } catch (Throwable e) {
            LOGGER.warn("[Planarium Panorama] Redirection des couleurs du decor indisponible avec cette version de Contraption Lights", e);
            return false;
        }
    }

    private static Method find(String name, Class<?>... parameters) {
        try {
            Class<?> engine = Class.forName("xyz.atmerek.contraptionlights.light.color.ColorLightEngine");
            return engine.getMethod(name, parameters);
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * Vrai pendant la construction du decor geant ou de la miniature (la source n'est pas le vrai monde),
     * seulement pour le thread qui construit.
     */
    public static boolean building() {
        Context context = CONTEXT.get();
        return context != null && context.view() != null;
    }

    public static boolean present() {
        return COMPATIBLE;
    }

    /** Reuse a preview's isolated cache coordinates when its animated parts are rendered. */
    public static final class Scope {
        private final Context context;
        private Scope(Context context) { this.context = context; }
    }

    public static Scope capture() { return new Scope(CONTEXT.get()); }

    public static void use(Scope scope) {
        Context previous = CONTEXT.get();
        Context saved = scope == null ? null : scope.context;
        CONTEXT.set(saved == null || !Config.coloredLight() ? new Context(previous, null, 0, 0)
                : new Context(previous, saved.view(), saved.offsetX(), saved.offsetZ()));
    }

    public static void begin(BlockAndTintGetter fakeWorld) {
        Context previous = CONTEXT.get();
        if (!COMPATIBLE || fakeWorld == null || !Config.coloredLight()) {
            CONTEXT.set(new Context(previous, null, 0, 0));
            return;
        }
        int[] slot = WORLD_SLOTS.computeIfAbsent(fakeWorld, ignored -> allocate());
        CONTEXT.set(new Context(previous, new SourceView(fakeWorld, slot[0], slot[1]), slot[0], slot[1]));
    }

    private static int[] allocate() {
        long next = NEXT_SLOT.getAndIncrement();
        long count = (long) SLOTS * SLOTS;
        if (next > 0 && next % count == 0) {
            // Reutilisation des emplacements apres des millions de photos : aucune vieille teinte ne doit rester.
            clearCache();
        }
        int index = (int) Math.floorMod(next, count);
        return new int[]{BASE + (index % SLOTS) * SLOT, BASE + (index / SLOTS) * SLOT};
    }

    /**
     * Des blocs ont change dans un faux monde deja colore (correction du decor, flaques) : on efface seulement ces
     * sections dans le cache de Contraption Lights, comme il le fait lui-meme pour le vrai monde. Son invalidate
     * efface les sources de lumiere de la section et les couleurs des 26 sections voisines (portee de 15 blocs).
     */
    public static void blocksChanged(Object fakeWorld, Iterable<net.minecraft.core.BlockPos> positions) {
        if (INVALIDATE == null || fakeWorld == null || positions == null) {
            return;
        }
        int[] slot = WORLD_SLOTS.get(fakeWorld);
        if (slot == null) {
            return;
        }
        java.util.Set<Long> sections = new java.util.HashSet<>();
        for (net.minecraft.core.BlockPos pos : positions) {
            int sx = (pos.getX() + slot[0]) >> 4;
            int sy = pos.getY() >> 4;
            int sz = (pos.getZ() + slot[1]) >> 4;
            if (sections.add(net.minecraft.core.SectionPos.asLong(sx, sy, sz))) {
                try {
                    INVALIDATE.invoke(null, sx, sy, sz);
                } catch (Throwable e) {
                    LOGGER.debug("[Planarium View] Contraption Lights : impossible d'effacer une section", e);
                    return;
                }
            }
        }
    }

    public static void end() {
        Context context = CONTEXT.get();
        if (context == null || context.previous() == null) {
            CONTEXT.remove();
        } else {
            CONTEXT.set(context.previous());
        }
    }

    // ------------------------------------------------------------------ crochets (ClColorSectionMixin)

    /** Monde lu par Contraption Lights pour une section de couleurs. */
    public static BlockAndTintGetter sectionSource(BlockAndTintGetter level) {
        Context context = CONTEXT.get();
        return context != null && context.view() != null ? context.view() : level;
    }

    /** Section demandee a Contraption Lights, decalee loin du vrai monde pendant une construction. */
    public static int sectionX(int sectionX) {
        Context context = CONTEXT.get();
        return context != null && context.view() != null ? sectionX + (context.offsetX() >> 4) : sectionX;
    }

    public static int sectionZ(int sectionZ) {
        Context context = CONTEXT.get();
        return context != null && context.view() != null ? sectionZ + (context.offsetZ() >> 4) : sectionZ;
    }

    /** Inverse de sectionX : la section du faux monde (pour JarColor). */
    public static int localSectionX(int sectionX) {
        Context context = CONTEXT.get();
        return context != null && context.view() != null ? sectionX - (context.offsetX() >> 4) : sectionX;
    }

    public static int localSectionZ(int sectionZ) {
        Context context = CONTEXT.get();
        return context != null && context.view() != null ? sectionZ - (context.offsetZ() >> 4) : sectionZ;
    }

    private static void clearCache() {
        if (CLEAR == null) {
            return;
        }
        try {
            CLEAR.invoke(null);
        } catch (Throwable e) {
            LOGGER.debug("[Planarium View] Contraption Lights : impossible de vider le cache", e);
        }
    }
}

package com.matthieu.planariumview.compat;

import com.matthieu.planariumview.config.Config;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Contraption Lights ajoute sa couleur a la lumiere des blocs a la fin de LevelRenderer.getLightColor (tagBaked).
 * Ars dessine la miniature et le decor geant avec sa propre copie de cette methode, que Contraption Lights ne voit pas :
 * les blocs lumineux (glowstone...) y perdaient leur teinte. On ajoute la couleur de la meme facon.
 */
public final class ClTag {
    private static MethodHandle tagBaked;
    private static MethodHandle tag;
    private static MethodHandle baking;
    private static boolean missing;

    /**
     * Couleur de Contraption Lights pour une lumiere packee, calculee a partir de ses seuls niveaux de lumiere
     * (on enleve d'abord toute couleur deja ajoutee). Comme Contraption Lights pour les blocs speciaux hors des chunks.
     */
    public static int retag(int packed, BlockAndTintGetter level, BlockPos pos) {
        if (missing || !Config.coloredLight()) {
            return packed;
        }
        int pure = packed & 0x00F000F0;
        try {
            if (tag == null) {
                Class<?> feature = Class.forName("xyz.atmerek.contraptionlights.light.color.ColorLightFeature");
                tag = MethodHandles.publicLookup().findStatic(feature, "tag",
                        MethodType.methodType(int.class, int.class, BlockAndTintGetter.class, BlockPos.class));
                baking = MethodHandles.publicLookup().findStatic(feature, "baking", MethodType.methodType(boolean.class));
            }
            // Pour Flywheel (et les chunks), Contraption Lights utilise la version "baked" de sa couleur.
            if ((boolean) baking.invokeExact()) {
                return tagBaked(pure, level, pos);
            }
            return (int) tag.invokeExact(pure, level, pos);
        } catch (Throwable e) {
            missing = true;
            return packed;
        }
    }

    /** Comme retag, pour les copies du monde qui servent a construire les chunks (toujours la version "baked"). */
    public static int retagBaked(int packed, BlockAndTintGetter level, BlockPos pos) {
        if (missing || !Config.coloredLight()) {
            return packed;
        }
        return tagBaked(packed & 0x00F000F0, level, pos);
    }

    private ClTag() {
    }

    public static int tagBaked(int packed, BlockAndTintGetter level, BlockPos pos) {
        if (missing || !Config.coloredLight()) {
            return packed;
        }
        try {
            if (tagBaked == null) {
                Class<?> feature = Class.forName("xyz.atmerek.contraptionlights.light.color.ColorLightFeature");
                tagBaked = MethodHandles.publicLookup().findStatic(feature, "tagBaked",
                        MethodType.methodType(int.class, int.class, BlockAndTintGetter.class, BlockPos.class));
            }
            return (int) tagBaked.invokeExact(packed, level, pos);
        } catch (Throwable e) {
            missing = true;
            return packed;
        }
    }
}

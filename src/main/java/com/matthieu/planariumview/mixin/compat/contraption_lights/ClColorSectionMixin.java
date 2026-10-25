package com.matthieu.planariumview.mixin.compat.contraption_lights;

import com.matthieu.planariumview.client.jar.JarColor;
import com.matthieu.planariumview.compat.contraption_lights.ColorCompat;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.level.BlockAndTintGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Contraption Lights : la couleur de la lumiere au milieu du bocal (voir JarColor).
 * Sans ce correctif, la couleur ne depasse pas quelques blocs autour des parois.
 *
 * Aussi : pendant la construction du decor geant ou d'une miniature, le thread qui construit lit le faux monde,
 * decale loin du vrai (voir ColorCompat). Les autres threads gardent le vrai monde.
 */
@Pseudo
@Mixin(targets = "xyz.atmerek.contraptionlights.light.color.ColorLightEngine", remap = false)
public abstract class ClColorSectionMixin {
    @Unique
    private static final String COLOR_SECTION =
            "colorSection(Lxyz/atmerek/contraptionlights/light/color/ColorLightEngine$Scratch;Lnet/minecraft/world/level/BlockAndTintGetter;IIIZ)[S";

    @ModifyVariable(method = COLOR_SECTION, at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0)
    private static BlockAndTintGetter planariumview$source(BlockAndTintGetter level) {
        return ColorCompat.sectionSource(level);
    }

    @ModifyVariable(method = COLOR_SECTION, at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0)
    private static int planariumview$sectionX(int sectionX) {
        return ColorCompat.sectionX(sectionX);
    }

    @ModifyVariable(method = COLOR_SECTION, at = @At("HEAD"), argsOnly = true, ordinal = 2, require = 0)
    private static int planariumview$sectionZ(int sectionZ) {
        return ColorCompat.sectionZ(sectionZ);
    }

    @Inject(method = COLOR_SECTION, at = @At("RETURN"), cancellable = true, require = 0)
    private static void planariumview$jarColor(CallbackInfoReturnable<short[]> cir,
                                               @Local(ordinal = 0, argsOnly = true) int sx,
                                               @Local(ordinal = 1, argsOnly = true) int sy,
                                               @Local(ordinal = 2, argsOnly = true) int sz) {
        short[] original = cir.getReturnValue();
        // Positions du faux monde pendant une construction (les sections recues sont decalees).
        short[] result = JarColor.apply(original, ColorCompat.localSectionX(sx), sy, ColorCompat.localSectionZ(sz));
        if (result != original) {
            cir.setReturnValue(result);
        }
    }
}

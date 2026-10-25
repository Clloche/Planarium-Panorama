package com.matthieu.planariumview.mixin.compat.contraption_lights;

import com.matthieu.planariumview.compat.contraption_lights.ColorCompat;

import net.minecraft.world.level.BlockAndTintGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Contraption Lights ajoute sa couleur a toute lumiere lue par LevelRenderer.getLightColor, meme dans nos faux mondes.
 * Pendant une construction marquee sans couleur (voir ColorCompat.suppressed), la lumiere reste telle quelle : pas de
 * recherche de sources de lumiere. Le vrai monde reste inchange en dehors de ce contexte propre au thread.
 */
@Pseudo
@Mixin(targets = "xyz.atmerek.contraptionlights.light.color.ColorLightFeature", remap = false)
public abstract class ClColorFeatureMixin {
    @Inject(method = "tag(ILnet/minecraft/world/level/BlockAndTintGetter;IIIZ)I", at = @At("HEAD"), cancellable = true,
            require = 0)
    private static void planariumview$noColor(int lightmap, BlockAndTintGetter level, int x, int y, int z,
                                              boolean unlimited, CallbackInfoReturnable<Integer> cir) {
        if (ColorCompat.suppressed()) {
            cir.setReturnValue(lightmap);
        }
    }
}

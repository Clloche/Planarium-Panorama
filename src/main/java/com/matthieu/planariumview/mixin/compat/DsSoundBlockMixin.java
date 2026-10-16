package com.matthieu.planariumview.mixin.compat;

import com.matthieu.planariumview.client.sound.OutsideSoundClient;
import com.matthieu.planariumview.config.Config;

import net.minecraft.client.resources.sounds.SoundInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Dynamic Surroundings bloque le son de l'eau qui coule de Minecraft, parce qu'il le remplace par ses propres sons de
 * cascades. Ces sons-la viennent des vraies cascades autour du joueur et ne peuvent pas jouer pour le decor geant :
 * on laisse donc passer le son de l'eau (et les autres ambiances) du decor, et seulement celles-la.
 */
@Mixin(targets = "org.orecruncher.dsurround.sound.SoundInstanceHandler", remap = false)
public abstract class DsSoundBlockMixin {

    @Inject(method = "shouldBlockSoundPlay", at = @At("HEAD"), cancellable = true, require = 0)
    private static void planariumview$letSceneryThrough(SoundInstance sound, CallbackInfoReturnable<Boolean> cir) {
        if (Config.outsideAmbience() && OutsideSoundClient.isScenery(sound)) {
            cir.setReturnValue(false);
        }
    }
}

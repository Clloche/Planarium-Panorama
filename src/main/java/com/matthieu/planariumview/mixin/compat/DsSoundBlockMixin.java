package com.matthieu.planariumview.mixin.compat;

import com.matthieu.planariumview.client.sound.OutsideSoundClient;
import com.matthieu.planariumview.config.Config;

import net.minecraft.client.resources.sounds.SoundInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Dynamic Surroundings bloque certains sons d'ambiance de Minecraft parce qu'il les remplace par les siens. On laisse
 * passer ceux du decor geant, sauf le bruit de l'eau qui coule : DS le remplace par ses sons de cascades, que le decor
 * joue aussi (voir SceneryWaterfalls).
 */
@Mixin(targets = "org.orecruncher.dsurround.sound.SoundInstanceHandler", remap = false)
public abstract class DsSoundBlockMixin {

    @org.spongepowered.asm.mixin.Unique
    private static final net.minecraft.resources.ResourceLocation WATER_AMBIENT =
            net.minecraft.resources.ResourceLocation.withDefaultNamespace("block.water.ambient");

    @Inject(method = "shouldBlockSoundPlay", at = @At("HEAD"), cancellable = true, require = 0)
    private static void planariumview$letSceneryThrough(SoundInstance sound, CallbackInfoReturnable<Boolean> cir) {
        if (Config.outsideAmbience() && OutsideSoundClient.isScenery(sound)
                && !WATER_AMBIENT.equals(sound.getLocation())) {
            cir.setReturnValue(false);
        }
    }
}

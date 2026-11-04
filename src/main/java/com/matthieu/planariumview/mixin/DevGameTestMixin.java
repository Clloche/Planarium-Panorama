package com.matthieu.planariumview.mixin;

import net.neoforged.neoforge.gametest.GameTestHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * NeoForge 1.21.1 active les GameTests en IDE meme avec neoforge.enableGameTest=false.
 * Les lancements ordinaires du projet les desactivent explicitement pour ne pas charger les tests des mods tiers.
 * Sans la propriete definie dans build.gradle, le comportement de NeoForge reste intact.
 */
@Mixin(value = GameTestHooks.class, remap = false)
public abstract class DevGameTestMixin {
    @Inject(method = "isGametestEnabled", at = @At("HEAD"), cancellable = true)
    private static void planariumview$disableDevelopmentGameTests(CallbackInfoReturnable<Boolean> cir) {
        if (Boolean.getBoolean("planariumview.disableGameTests")) {
            cir.setReturnValue(false);
        }
    }
}

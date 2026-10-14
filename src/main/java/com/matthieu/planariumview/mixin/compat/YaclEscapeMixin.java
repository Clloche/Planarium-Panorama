package com.matthieu.planariumview.mixin.compat;

import com.matthieu.planariumview.ConfigScreen;
import dev.isxander.yacl3.gui.YACLScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Menu de configuration (YACL), pour notre menu seulement : les changements sont deja sauvegardes
 * (voir ConfigScreen.autoSave), donc Echap ferme toujours le menu, sans l'avertissement
 * "sauvegarder avant de quitter". En fermant le menu, le decor est reconstruit s'il y a eu des changements.
 */
@Mixin(value = YACLScreen.class, remap = false)
public abstract class YaclEscapeMixin {

    @Inject(method = "shouldCloseOnEsc", at = @At("HEAD"), cancellable = true, require = 0)
    private void planariumview$alwaysClose(CallbackInfoReturnable<Boolean> cir) {
        if (ConfigScreen.isOurs(((YACLScreen) (Object) this).config)) {
            cir.setReturnValue(true);
        }
    }

    /** "Reinitialiser" change toutes les options : on sauvegarde tout de suite, comme pour un changement normal. */
    @Inject(method = "cancelOrReset", at = @At("TAIL"), require = 0)
    private void planariumview$afterReset(CallbackInfo ci) {
        ConfigScreen.applyPending(((YACLScreen) (Object) this).config);
    }

    @Inject(method = "onClose", at = @At("HEAD"), require = 0)
    private void planariumview$closed(CallbackInfo ci) {
        ConfigScreen.onClosed(((YACLScreen) (Object) this).config);
    }
}

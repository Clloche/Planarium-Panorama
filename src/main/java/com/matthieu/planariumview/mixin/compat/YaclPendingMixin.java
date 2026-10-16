package com.matthieu.planariumview.mixin.compat;

import com.matthieu.planariumview.config.ConfigScreen;

import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.gui.YACLScreen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Menu de configuration (YACL), pour notre menu seulement : apres "Reinitialiser", YACL voyait encore des changements
 * en attente (options grisees qui ne peuvent pas prendre leur valeur par defaut) et montrait "Annuler" et
 * "Sauvegarder". Tout est deja sauvegarde : on applique ce qui reste et on efface le drapeau avant les boutons.
 */
@Mixin(value = YACLScreen.class, remap = false)
public abstract class YaclPendingMixin {

    @Shadow
    @Final
    public YetAnotherConfigLib config;

    @Shadow
    private boolean pendingChanges;

    @Inject(method = "onOptionChanged", at = @At(value = "INVOKE",
            target = "Ldev/isxander/yacl3/gui/YACLScreen$CategoryTab;updateButtons()V"), require = 0)
    private void planariumview$noPending(CallbackInfo ci) {
        if (ConfigScreen.isOurs(this.config)) {
            ConfigScreen.applyPending(this.config);
            this.pendingChanges = false;
        }
    }
}

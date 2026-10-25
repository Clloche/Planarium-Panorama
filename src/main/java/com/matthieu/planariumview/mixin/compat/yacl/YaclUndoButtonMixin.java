package com.matthieu.planariumview.mixin.compat.yacl;

import com.matthieu.planariumview.config.ConfigScreen;

import dev.isxander.yacl3.gui.YACLScreen;
import net.minecraft.client.gui.components.Button;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Menu de configuration (YACL), pour notre menu seulement : les changements sont sauvegardes tout de suite,
 * le bouton "Annuler les changements" ne sert donc a rien. On le cache et le bouton "Reinitialiser"
 * prend toute la largeur.
 */
@Mixin(targets = "dev.isxander.yacl3.gui.YACLScreen$CategoryTab", remap = false)
public abstract class YaclUndoButtonMixin {

    @Shadow
    @Final
    private YACLScreen screen;

    @Shadow
    @Final
    public Button saveFinishedButton;

    @Shadow
    @Final
    public Button cancelResetButton;

    @Shadow
    @Final
    public Button undoButton;

    @Inject(method = "<init>", at = @At("RETURN"), require = 0)
    private void planariumview$hideUndo(CallbackInfo ci) {
        planariumview$layout();
    }

    /** Filet de securite : aucun changement ne reste en attente (les boutons restent "Reinitialiser" et "Termine"). */
    @Inject(method = "updateButtons", at = @At("HEAD"), require = 0)
    private void planariumview$applyPending(CallbackInfo ci) {
        if (this.screen != null) {
            ConfigScreen.applyPending(this.screen.config);
        }
    }

    @Inject(method = "updateButtons", at = @At("TAIL"), require = 0)
    private void planariumview$hideUndoUpdate(CallbackInfo ci) {
        planariumview$layout();
    }

    @Inject(method = "doLayout", at = @At("TAIL"), require = 0)
    private void planariumview$hideUndoLayout(CallbackInfo ci) {
        planariumview$layout();
    }

    private void planariumview$layout() {
        if (this.screen == null || !ConfigScreen.isOurs(this.screen.config) || this.undoButton == null) {
            return;
        }
        this.undoButton.visible = false;
        this.undoButton.active = false;
        this.cancelResetButton.setX(this.saveFinishedButton.getX());
        this.cancelResetButton.setWidth(this.saveFinishedButton.getWidth());
        // Le texte de YACL dit "(this is reversible!)" : faux ici, la reinitialisation est sauvegardee tout de suite.
        this.cancelResetButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                net.minecraft.network.chat.Component.translatable("planariumview.config.reset.tooltip")));
    }
}

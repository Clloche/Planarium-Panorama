package com.matthieu.planariumview.mixin.compat;

import com.matthieu.planariumview.ConfigScreen;
import dev.isxander.yacl3.gui.LowProfileButtonWidget;
import dev.isxander.yacl3.gui.OptionListWidget;
import dev.isxander.yacl3.gui.YACLScreen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.MultiLineLabel;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Menu de configuration (YACL) : l'en-tete d'un groupe (Brouillard) a une barre translucide derriere lui, et son nom
 * est aligne a gauche, juste a droite de la fleche, comme dans Better Clouds. Pour notre menu seulement.
 */
@Mixin(value = OptionListWidget.GroupSeparatorEntry.class, remap = false)
public abstract class YaclGroupHeaderMixin {

    @Shadow
    @Final
    protected LowProfileButtonWidget expandMinimizeButton;

    @Shadow
    @Final
    protected Screen screen;

    /** La liste qui contient l'en-tete (classe englobante). */
    @Shadow
    @Final
    OptionListWidget this$0;

    private boolean planariumview$ours() {
        return this.screen instanceof YACLScreen yacl && ConfigScreen.isOurs(yacl.config);
    }

    @Redirect(method = "renderContent", at = @At(value = "INVOKE",
            target = "Ldev/isxander/yacl3/gui/LowProfileButtonWidget;render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V"),
            require = 0)
    private void planariumview$bar(LowProfileButtonWidget button, GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        if (planariumview$ours()) {
            int x = button.getX();
            int y = button.getY();
            graphics.fill(x, y, x + this.this$0.getRowWidth(), y + button.getHeight(), 0x66000000);
        }
        button.render(graphics, mouseX, mouseY, delta);
    }

    @Redirect(method = "renderContent", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/components/MultiLineLabel;renderCentered(Lnet/minecraft/client/gui/GuiGraphics;II)V"),
            require = 0)
    private void planariumview$leftName(MultiLineLabel label, GuiGraphics graphics, int centerX, int y) {
        if (!planariumview$ours()) {
            label.renderCentered(graphics, centerX, y);
            return;
        }
        LowProfileButtonWidget button = this.expandMinimizeButton;
        label.renderLeftAligned(graphics, button.getX() + button.getWidth() + 6, y, 9, 0xFFFFFFFF);
    }
}

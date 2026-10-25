package com.matthieu.planariumview.mixin.compat.yacl;

import com.matthieu.planariumview.config.ConfigScreen;

import dev.isxander.yacl3.gui.YACLScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Menu de configuration (YACL) : boutons translucides au lieu de la texture de bouton, comme Better Clouds.
 * Fond noir translucide, contour blanc au survol, contour gris quand l'option est grisee. Notre menu seulement.
 */
@Mixin(value = dev.isxander.yacl3.gui.AbstractWidget.class, remap = false)
public abstract class YaclButtonMixin {
    private static final int FILL = 0x6B000000;
    private static final int OUTLINE_HOVER = 0xFFFFFFFF;
    private static final int OUTLINE_OFF = 0xFFA0A0A0;

    @Inject(method = "drawButtonRect", at = @At("HEAD"), cancellable = true, require = 0)
    private void planariumview$translucent(GuiGraphics graphics, int x1, int y1, int x2, int y2, boolean hovered,
                                           boolean enabled, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof YACLScreen screen) || !ConfigScreen.isOurs(screen.config)) {
            return;
        }
        ci.cancel();
        int left = Math.min(x1, x2);
        int right = Math.max(x1, x2);
        int top = Math.min(y1, y2);
        int bottom = Math.max(y1, y2);
        graphics.fill(left, top, right, bottom, FILL);
        int outline = !enabled ? OUTLINE_OFF : hovered ? OUTLINE_HOVER : 0;
        if (outline != 0) {
            graphics.fill(left, top, right, top + 1, outline);
            graphics.fill(left, bottom - 1, right, bottom, outline);
            graphics.fill(left, top, left + 1, bottom, outline);
            graphics.fill(right - 1, top, right, bottom, outline);
        }
    }
}

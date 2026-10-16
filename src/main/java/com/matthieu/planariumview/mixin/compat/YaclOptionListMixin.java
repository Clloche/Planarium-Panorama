package com.matthieu.planariumview.mixin.compat;

import com.matthieu.planariumview.config.ConfigScreen;

import dev.isxander.yacl3.gui.OptionListWidget;
import dev.isxander.yacl3.gui.YACLScreen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Menu de configuration (YACL) : des options de largeur lisible, centrees, pour notre menu seulement.
 * La liste centre deja ses lignes selon leur largeur.
 */
@Mixin(value = OptionListWidget.class, remap = false)
public abstract class YaclOptionListMixin {

    @Shadow
    @Final
    private YACLScreen yaclScreen;

    @Inject(method = "getRowWidth", at = @At("RETURN"), cancellable = true, require = 0)
    private void planariumview$narrow(CallbackInfoReturnable<Integer> cir) {
        if (this.yaclScreen != null && ConfigScreen.isOurs(this.yaclScreen.config)
                && cir.getReturnValueI() > ConfigScreen.ROW_WIDTH) {
            cir.setReturnValue(ConfigScreen.ROW_WIDTH);
        }
    }
}

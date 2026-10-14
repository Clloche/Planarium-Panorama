package com.matthieu.planariumview.mixin.compat;

import com.hollingsworth.arsnouveau.ArsNouveau;
import com.matthieu.planariumview.SkySync;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Traveler's Titles (optionnel) : en entrant dans un bocal, il affichait "???" (dimension inconnue).
 * On affiche a la place le nom du Planarium, ou "Planarium" s'il n'a pas de nom.
 */
@Pseudo
@Mixin(targets = "com.yungnickyoung.minecraft.travelerstitles.render.TitleRenderer", remap = false)
public abstract class TravelersTitlesMixin {

    @ModifyVariable(method = "displayTitle", at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0)
    private Component planariumview$jarTitle(Component title) {
        if (title == null || !com.matthieu.planariumview.Config.enabled() || !"???".equals(title.getString())) {
            return title;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || !level.dimensionTypeRegistration().is(ArsNouveau.DIMENSION_TYPE_KEY)) {
            return title;
        }
        return SkySync.jarTitle(level);
    }
}

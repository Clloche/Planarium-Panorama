package com.matthieu.planariumview.mixin;

import com.matthieu.planariumview.client.sky.SkySync;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.LevelHeightAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sous la hauteur de l'horizon (y 63), Minecraft dessine un disque noir juste sous les yeux pour cacher le vide :
 * il apparaissait comme une ligne sombre a l'horizon du bocal, qui est tout entier sous y 63.
 * Dans un bocal qui montre le ciel du dehors, l'horizon est au fond du monde : plus de disque noir
 * (le bas est deja ferme par le decor geant et le mur de brouillard).
 */
@Mixin(targets = "net.minecraft.client.multiplayer.ClientLevel$ClientLevelData")
public abstract class HorizonMixin {

    @Inject(method = "getHorizonHeight", at = @At("HEAD"), cancellable = true, require = 0)
    private void planariumview$jarHorizon(LevelHeightAccessor level, CallbackInfoReturnable<Double> cir) {
        if (SkySync.skyActive(Minecraft.getInstance().level)) {
            cir.setReturnValue((double) level.getMinBuildHeight());
        }
    }
}

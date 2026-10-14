package com.matthieu.planariumview.mixin.compat;

import com.hollingsworth.arsnouveau.ArsNouveau;
import com.matthieu.planariumview.OutsideAmbience;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Infos de dimension de Dynamic Surroundings, pour les dimensions de bocal seulement.
 *
 * - Niveau de la mer : tout en bas, sauf si le Planarium est sous terre dehors (alors on fait croire
 *   a Dynamic Surroundings qu'on est sous terre, pour jouer l'ambiance de caverne comme a cote du Planarium).
 * - "Toujours dehors" : vrai si le Planarium est a l'air libre (sons du biome), faux s'il est abrite
 *   (le plafond du bocal fait alors croire a Dynamic Surroundings qu'on est a l'interieur : pas de sons de biome).
 */
@Mixin(targets = "org.orecruncher.dsurround.config.DimensionInfo", remap = false)
public abstract class DsDimensionInfoMixin {

    @Shadow
    protected int seaLevel;

    @Unique
    private boolean planariumview$jar;

    @Inject(method = "<init>(Lnet/minecraft/world/level/Level;)V", at = @At("TAIL"), require = 0)
    private void planariumview$jarSeaLevel(Level world, CallbackInfo ci) {
        if (world.dimensionTypeRegistration().is(ArsNouveau.DIMENSION_TYPE_KEY)) {
            this.planariumview$jar = true;
            this.seaLevel = world.getMinBuildHeight();
        }
    }

    @Inject(method = "getSeaLevel", at = @At("HEAD"), cancellable = true, require = 0)
    private void planariumview$seaLevel(CallbackInfoReturnable<Integer> cir) {
        if (this.planariumview$jar && OutsideAmbience.active() && OutsideAmbience.underground()) {
            cir.setReturnValue(1_000_000);
        }
    }

    @Inject(method = "alwaysOutside", at = @At("HEAD"), cancellable = true, require = 0)
    private void planariumview$alwaysOutside(CallbackInfoReturnable<Boolean> cir) {
        if (this.planariumview$jar && OutsideAmbience.active()) {
            cir.setReturnValue(!OutsideAmbience.sheltered());
        }
    }
}

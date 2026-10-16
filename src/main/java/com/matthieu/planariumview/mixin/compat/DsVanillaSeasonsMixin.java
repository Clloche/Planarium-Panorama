package com.matthieu.planariumview.mixin.compat;

import com.matthieu.planariumview.client.sound.OutsideAmbience;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sans Serene Seasons : dans un bocal, Dynamic Surroundings prend la temperature du biome exterieur
 * a la hauteur du Planarium (plus froid en altitude, comme dans l'Aether), pas a celle du joueur dans le bocal.
 */
@Mixin(targets = "org.orecruncher.dsurround.lib.seasons.compat.VanillaSeasons", remap = false)
public abstract class DsVanillaSeasonsMixin {

    @Inject(method = "getTemperature", at = @At("HEAD"), cancellable = true, require = 0)
    private void planariumview$temperature(BlockPos pos, CallbackInfoReturnable<Float> cir) {
        Holder<Biome> biome = OutsideAmbience.biomeHolder();
        if (biome != null) {
            cir.setReturnValue(OutsideAmbience.biomeTemperature(biome, OutsideAmbience.atPlanariumHeight(pos)));
        }
    }
}

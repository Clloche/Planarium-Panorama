package com.matthieu.planariumview.mixin.compat.dynamic_surroundings;

import com.matthieu.planariumview.client.sound.OutsideAmbience;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Les conditions des sons de Dynamic Surroundings ("biome.is...") utilisent aussi le biome exterieur dans un bocal.
 */
@Mixin(targets = "org.orecruncher.dsurround.runtime.variables.BiomeVariables", remap = false)
public abstract class DsBiomeVariablesMixin {

    @Redirect(method = "*",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;getBiome(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/Holder;"),
            require = 0)
    private Holder<Biome> planariumview$outsideBiome(Level level, BlockPos pos) {
        Holder<Biome> outside = OutsideAmbience.biomeHolder();
        return outside != null ? outside : level.getBiome(pos);
    }
}

package com.matthieu.planariumview.mixin.compat;

import com.matthieu.planariumview.AmbientSoundsCompat;
import com.matthieu.planariumview.OutsideAmbience;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** AmbientSounds : dans un bocal, le biome et la pluie sont ceux de l'endroit du Planarium. */
@Pseudo
@Mixin(targets = "team.creative.ambientsounds.environment.BiomeEnvironment", remap = false)
public abstract class AsBiomeMixin {

    @Redirect(method = "<init>(Lteam/creative/ambientsounds/engine/AmbientEngine;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/level/Level;Lteam/creative/ambientsounds/condition/AmbientVolume;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getBiome(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/Holder;"),
            require = 0)
    private Holder<Biome> planariumview$biome(Level level, BlockPos pos) {
        Holder<Biome> outside = AmbientSoundsCompat.active(level) ? OutsideAmbience.knownBiomeHolder() : null;
        return outside != null ? outside : level.getBiome(pos);
    }

    @Redirect(method = "<init>(Lteam/creative/ambientsounds/engine/AmbientEngine;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/level/Level;Lteam/creative/ambientsounds/condition/AmbientVolume;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;isRaining()Z"),
            require = 0)
    private boolean planariumview$raining(Level level) {
        return AmbientSoundsCompat.active(level) ? AmbientSoundsCompat.isRaining() : level.isRaining();
    }
}

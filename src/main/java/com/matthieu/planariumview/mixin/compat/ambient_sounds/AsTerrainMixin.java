package com.matthieu.planariumview.mixin.compat.ambient_sounds;

import com.matthieu.planariumview.compat.ambient_sounds.AmbientSoundsCompat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * AmbientSounds : dans un bocal, la hauteur du terrain et les blocs autour (feuilles, sable, eau, ciel ouvert...)
 * sont lus dehors, autour du Planarium : dans la photo du decor geant, et pas dans le bocal.
 */
@Pseudo
@Mixin(targets = "team.creative.ambientsounds.environment.TerrainEnvironment", remap = false)
public abstract class AsTerrainMixin {

    @Inject(method = "analyzeHeight", at = @At("HEAD"), cancellable = true, require = 0)
    private void planariumview$outsideHeight(CallbackInfo ci) {
        if (AmbientSoundsCompat.terrainHeight(this)) {
            ci.cancel();
        }
    }

    @ModifyArg(method = "analyzeAirPocket",
            at = @At(value = "INVOKE",
                    target = "Lteam/creative/ambientsounds/environment/pocket/AirPocketScanner;<init>(Lteam/creative/ambientsounds/engine/AmbientEngine;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Ljava/util/function/Consumer;)V"),
            index = 1, require = 0)
    private Level planariumview$scanLevel(Level level) {
        return AmbientSoundsCompat.scanLevel(level);
    }

    @ModifyArg(method = "analyzeAirPocket",
            at = @At(value = "INVOKE",
                    target = "Lteam/creative/ambientsounds/environment/pocket/AirPocketScanner;<init>(Lteam/creative/ambientsounds/engine/AmbientEngine;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Ljava/util/function/Consumer;)V"),
            index = 2, require = 0)
    private BlockPos planariumview$scanOrigin(BlockPos pos) {
        return AmbientSoundsCompat.scanOrigin(pos);
    }
}

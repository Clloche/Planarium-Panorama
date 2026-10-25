package com.matthieu.planariumview.mixin.compat.dynamic_surroundings;

import com.matthieu.planariumview.client.scenery.OutsideFluidView;
import com.matthieu.planariumview.client.sound.OutsideAmbience;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Only sound conditions use the exterior immersion; swimming and air supply keep their actual state. */
@Pseudo
@Mixin(targets = "org.orecruncher.dsurround.runtime.variables.PlayerVariables", remap = false)
public abstract class DsPlayerVariablesMixin {
    private static FogType planariumview$outside(Player player) {
        return OutsideAmbience.active() ? OutsideFluidView.fogAt(player.getEyePosition()) : FogType.NONE;
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;isUnderWater()Z"), require = 0)
    private boolean planariumview$water(Player player) {
        return player.isUnderWater() || planariumview$outside(player) == FogType.WATER;
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;isInLava()Z"), require = 0)
    private boolean planariumview$lava(Player player) {
        return player.isInLava() || planariumview$outside(player) == FogType.LAVA;
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;isInWaterOrRain()Z"), require = 0)
    private boolean planariumview$wet(Player player) {
        return player.isInWaterOrRain() || planariumview$outside(player) == FogType.WATER;
    }
}

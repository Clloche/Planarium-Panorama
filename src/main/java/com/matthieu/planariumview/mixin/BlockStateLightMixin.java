package com.matthieu.planariumview.mixin;

import com.matthieu.planariumview.client.jar.JarWalls;

import com.hollingsworth.arsnouveau.common.block.DimBoundary;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Les parois du bocal emettent la lumiere indiquee par leur propriete planariumview_light.
 * La valeur est ecrite directement dans le cache de l'etat de bloc (comme pour une torche), pour que
 * tous les moteurs de lumiere la voient, y compris ceux de mods d'optimisation qui lisent ce cache.
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateLightMixin {

    @Shadow
    @Final
    @Mutable
    private int lightEmission;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void planariumview$initWallLight(CallbackInfo ci) {
        Object self = this;
        if (self instanceof BlockState state && state.getBlock() instanceof DimBoundary && state.hasProperty(JarWalls.LIGHT)) {
            this.lightEmission = state.getValue(JarWalls.LIGHT);
        }
    }

    @Inject(method = "getLightEmission", at = @At("HEAD"), cancellable = true)
    private void planariumview$wallLight(CallbackInfoReturnable<Integer> cir) {
        Object self = this;
        if (self instanceof BlockState state && state.getBlock() instanceof DimBoundary && state.hasProperty(JarWalls.LIGHT)) {
            cir.setReturnValue(state.getValue(JarWalls.LIGHT));
        }
    }
}

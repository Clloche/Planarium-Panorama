package com.matthieu.planariumview.mixin.compat;

import com.matthieu.planariumview.client.scenery.SceneryReverb;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Reverberation et occlusion de Dynamic Surroundings pour un son du dehors : le calcul se fait dans le decor (voir
 * SceneryReverb), avec la vraie position du son et l'ecoute au Planarium, au lieu du bocal.
 *
 * SoundFXUtils jusqu'a DS 0.4.6, SourceAcoustics en 0.4.7 (ou les blocs sont lus par {@code ctx.blocks}). Le monde
 * ({@code ctx.world}) sert aussi de cle aux resultats gardes et partages : remplace aussi, pour ne jamais melanger
 * les rayons du decor et ceux du bocal.
 */
@Pseudo
@Mixin(targets = {"org.orecruncher.dsurround.runtime.audio.SoundFXUtils",
        "org.orecruncher.dsurround.runtime.audio.SourceAcoustics"}, remap = false)
public abstract class DsSoundFxMixin {

    @ModifyExpressionValue(method = {"calculate", "traceReverb", "calculateOcclusion"}, at = @At(value = "FIELD",
            target = "Lorg/orecruncher/dsurround/runtime/audio/WorldContext;world:Lnet/minecraft/world/level/Level;"),
            require = 0)
    private Level planariumview$sceneryWorld(Level original) {
        SceneryReverb.Context context = SceneryReverb.current();
        return context != null ? context.world() : original;
    }

    @ModifyExpressionValue(method = {"calculate", "traceReverb", "calculateOcclusion"}, at = @At(value = "FIELD",
            target = "Lorg/orecruncher/dsurround/runtime/audio/WorldContext;blocks:Lnet/minecraft/world/level/BlockGetter;"),
            require = 0)
    private BlockGetter planariumview$sceneryBlocks(BlockGetter original) {
        SceneryReverb.Context context = SceneryReverb.current();
        return context != null ? context.world() : original;
    }

    @ModifyExpressionValue(method = {"calculate", "traceReverb"}, at = @At(value = "FIELD",
            target = "Lorg/orecruncher/dsurround/runtime/audio/WorldContext;playerEyePosition:Lnet/minecraft/world/phys/Vec3;"),
            require = 0)
    private Vec3 planariumview$sceneryListener(Vec3 original) {
        SceneryReverb.Context context = SceneryReverb.current();
        return context != null ? context.listener() : original;
    }

    @ModifyExpressionValue(method = "calculate", at = @At(value = "INVOKE",
            target = "Lorg/orecruncher/dsurround/runtime/audio/SourceContext;getPosition()Lnet/minecraft/world/phys/Vec3;"),
            require = 0)
    private Vec3 planariumview$scenerySource(Vec3 original) {
        SceneryReverb.Context context = SceneryReverb.current();
        return context != null ? context.source() : original;
    }
}

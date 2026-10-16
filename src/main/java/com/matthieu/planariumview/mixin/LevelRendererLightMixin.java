package com.matthieu.planariumview.mixin;

import com.matthieu.planariumview.client.jar.JarLight;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lumiere des blocs dans le bocal : celle qui entre par le verre, repartie sur tout l'interieur (voir JarLight).
 * Minecraft et Sodium passent tous deux par cette methode pour eclairer les blocs, les entites et la main.
 */
@Mixin(value = LevelRenderer.class, priority = 900)
public abstract class LevelRendererLightMixin {

    /**
     * Seulement pour le vrai monde du bocal : le monde du client, et les copies que Minecraft et Sodium en font
     * pour construire les chunks. Pas pour le decor geant ni la miniature : Ars les dessine avec des positions
     * comptees depuis le coin de la photo (0, 1, 2...), qui tombent aussi dans la boite du bocal.
     */
    private static boolean planariumview$isJarWorld(BlockAndTintGetter level) {
        if (level instanceof net.minecraft.client.multiplayer.ClientLevel
                || level instanceof net.minecraft.client.renderer.chunk.RenderChunkRegion) {
            return true;
        }
        return level != null && level.getClass().getName().equals("net.caffeinemc.mods.sodium.client.world.LevelSlice");
    }

    @Inject(method = "getLightColor(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)I",
            at = @At("RETURN"), cancellable = true, require = 0)
    private static void planariumview$jarLight(BlockAndTintGetter level, BlockState state, BlockPos pos,
                                               CallbackInfoReturnable<Integer> cir) {
        if (!planariumview$isJarWorld(level)) {
            return;
        }
        int packed = cir.getReturnValueI();
        int next = JarLight.packedLight(packed, pos.getX(), pos.getY(), pos.getZ());
        if (next != packed) {
            // Blocs speciaux et autres rendus hors des chunks (le monde du client lui-meme) : Contraption Lights a peut-etre
            // deja calcule sa couleur avec l'ancienne lumiere. On la recalcule avec la nouvelle, quel que soit l'ordre.
            // Dans les chunks (Sodium), c'est le cas des coffres, lits, panneaux et cloches de Better Block Entities.
            if (level instanceof net.minecraft.client.multiplayer.ClientLevel) {
                next = com.matthieu.planariumview.compat.ClTag.retag(next, level, pos);
            } else {
                next = com.matthieu.planariumview.compat.ClTag.retagBaked(next, level, pos);
            }
            cir.setReturnValue(next);
        }
    }
}

package com.matthieu.planariumview.mixin.entities;

import com.matthieu.planariumview.client.jar.JarLight;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Les entites (animaux, objets au sol, joueurs) calculent leur lumiere sans passer par celle des blocs :
 * elles recoivent aussi la lumiere qui entre dans le bocal (voir JarLight). Priorite 900 : avant que
 * Contraption Lights ajoute sa couleur, pour qu'il voie la vraie lumiere.
 */
@Mixin(value = EntityRenderer.class, priority = 900)
public abstract class EntityLightMixin {

    @Inject(method = "getBlockLightLevel", at = @At("RETURN"), cancellable = true, require = 0)
    private void planariumview$jarLight(Entity entity, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        if (!(entity.level() instanceof ClientLevel)) {
            return;
        }
        int light = cir.getReturnValueI();
        int next = JarLight.blockLight(pos.getX(), pos.getY(), pos.getZ(), light);
        if (next != light) {
            cir.setReturnValue(next);
        }
    }
}

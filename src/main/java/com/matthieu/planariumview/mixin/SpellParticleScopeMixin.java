package com.matthieu.planariumview.mixin;

import com.matthieu.planariumview.client.scenery.SceneryParticles;

import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.SpellParticle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Particules d'effets de potion : vanilla les efface quand le joueur regarde a la longue-vue a moins de 3 blocs, pour
 * qu'elles ne bouchent pas la vue. Une miniature se regarde justement de pres a la longue-vue, et ses particules sont
 * 32 fois plus petites : elles y restent visibles. Le constructeur fait deja ce test (alpha a 0 des la naissance) :
 * SceneryParticles.miniature couvre aussi la creation.
 */
@Mixin(SpellParticle.class)
public abstract class SpellParticleScopeMixin {
    @Inject(method = "isCloseToScopingPlayer", at = @At("HEAD"), cancellable = true, require = 0)
    private void planariumview$miniature(CallbackInfoReturnable<Boolean> cir) {
        if (SceneryParticles.miniature((Particle) (Object) this)) cir.setReturnValue(false);
    }
}

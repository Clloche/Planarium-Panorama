package com.matthieu.planariumview.mixin;

import com.matthieu.planariumview.client.scenery.SceneryParticles;
import net.minecraft.client.particle.CampfireSmokeParticle;
import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** La derive aleatoire de la fumee est ajoutee a chaque tick : elle doit suivre l'echelle du decor. */
@Mixin(CampfireSmokeParticle.class)
public abstract class CampfireSmokeParticleMixin {
    @ModifyConstant(method = "tick", constant = @Constant(floatValue = 5000.0F), require = 0)
    private float planariumview$driftScale(float divisor) {
        return divisor / SceneryParticles.motionScale((Particle) (Object) this);
    }
}

package com.matthieu.planariumview.mixin;

import net.minecraft.client.particle.SingleQuadParticle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Taille de base d'une particule (avant les effets d'age comme la fumee qui grossit), voir SceneryParticles. */
@Mixin(SingleQuadParticle.class)
public interface SingleQuadParticleAccessor {
    @Accessor("quadSize")
    float planariumview$getQuadSize();
}

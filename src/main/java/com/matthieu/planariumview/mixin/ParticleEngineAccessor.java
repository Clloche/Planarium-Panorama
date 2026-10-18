package com.matthieu.planariumview.mixin;

import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/** Textures des particules de Minecraft (pour creer une vraie flamme dans le decor, voir SceneryParticles.vanillaFlame). */
@Mixin(ParticleEngine.class)
public interface ParticleEngineAccessor {
    @Accessor("spriteSets")
    Map<ResourceLocation, ?> planariumview$spriteSets();
}

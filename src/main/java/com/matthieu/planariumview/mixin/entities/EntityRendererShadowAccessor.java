package com.matthieu.planariumview.mixin.entities;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Use each renderer's own shadow size, including baby mobs, entity scale and modded overrides. */
@Mixin(EntityRenderer.class)
public interface EntityRendererShadowAccessor {
    @Invoker("getShadowRadius")
    float planariumview$shadowRadius(Entity entity);

    @Accessor("shadowStrength")
    float planariumview$shadowStrength();
}

package com.matthieu.planariumview.mixin.entities;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Dispatcher helpers for rendering copies without drawing against the player's world. */
@Mixin(EntityRenderDispatcher.class)
public interface EntityRenderDispatcherInvoker {
    @Invoker("renderFlame")
    void planariumview$renderFlame(PoseStack pose, MultiBufferSource buffers, Entity entity, Quaternionf rotation);

    @Accessor("shouldRenderShadow")
    boolean planariumview$shouldRenderShadow();
}

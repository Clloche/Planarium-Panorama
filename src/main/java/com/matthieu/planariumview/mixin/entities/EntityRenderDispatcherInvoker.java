package com.matthieu.planariumview.mixin.entities;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The normal dispatcher also draws shadows and hitboxes in the real world; copies only need its flames. */
@Mixin(EntityRenderDispatcher.class)
public interface EntityRenderDispatcherInvoker {
    @Invoker("renderFlame")
    void planariumview$renderFlame(PoseStack pose, MultiBufferSource buffers, Entity entity, Quaternionf rotation);
}

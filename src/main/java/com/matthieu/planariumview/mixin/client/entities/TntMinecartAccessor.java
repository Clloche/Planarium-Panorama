package com.matthieu.planariumview.mixin.client.entities;

import net.minecraft.world.entity.vehicle.MinecartTNT;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MinecartTNT.class)
public interface TntMinecartAccessor {
    @Accessor("fuse")
    void planariumview$setFuse(int fuse);
}

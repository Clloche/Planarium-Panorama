package com.matthieu.planariumview.mixin;

import net.minecraft.world.entity.vehicle.Boat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Boat.class)
public interface BoatAccessor {
    @Invoker("getBubbleTime")
    int planariumview$getBubbleTime();
}

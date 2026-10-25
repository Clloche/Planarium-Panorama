package com.matthieu.planariumview.mixin.entities;

import com.google.common.collect.ImmutableList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Relations et monde des copies visuelles, sans declencher les evenements de montee/descente du vrai jeu. */
@Mixin(Entity.class)
public interface SceneryEntityAccessor {
    @Accessor("vehicle")
    void planariumview$setVehicle(Entity vehicle);

    @Accessor("passengers")
    void planariumview$setPassengers(ImmutableList<Entity> passengers);

    @Accessor("level")
    void planariumview$setLevel(Level level);
}

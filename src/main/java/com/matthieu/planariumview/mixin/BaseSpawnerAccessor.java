package com.matthieu.planariumview.mixin;

import net.minecraft.world.level.BaseSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Rotation et delai visuels d'un spawner copie (voir SpawnerView) : vanilla ne les avance que dans clientTick. */
@Mixin(BaseSpawner.class)
public interface BaseSpawnerAccessor {
    @Accessor("spin")
    double planariumview$getSpin();

    @Accessor("spin")
    void planariumview$setSpin(double value);

    @Accessor("oSpin")
    void planariumview$setOSpin(double value);

    @Accessor("spawnDelay")
    int planariumview$getSpawnDelay();

    @Accessor("spawnDelay")
    void planariumview$setSpawnDelay(int value);

    @Accessor("requiredPlayerRange")
    int planariumview$getRequiredPlayerRange();
}

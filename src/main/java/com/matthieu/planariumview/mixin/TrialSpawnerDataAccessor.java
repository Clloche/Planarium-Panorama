package com.matthieu.planariumview.mixin;

import net.minecraft.world.level.block.entity.trialspawner.TrialSpawnerData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Rotation visuelle d'un trial spawner copie, gardee d'une copie a la suivante (voir SpawnerView). */
@Mixin(TrialSpawnerData.class)
public interface TrialSpawnerDataAccessor {
    @Accessor("spin")
    double planariumview$getSpin();

    @Accessor("spin")
    void planariumview$setSpin(double value);

    @Accessor("oSpin")
    void planariumview$setOSpin(double value);
}

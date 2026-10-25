package com.matthieu.planariumview.mixin.entities;

import net.minecraft.network.syncher.SynchedEntityData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lecture de l'apparence sans consommer les changements destines aux vrais clients. */
@Mixin(SynchedEntityData.class)
public interface SynchedEntityDataAccessor {
    @Accessor("itemsById")
    SynchedEntityData.DataItem<?>[] planariumview$getItems();
}

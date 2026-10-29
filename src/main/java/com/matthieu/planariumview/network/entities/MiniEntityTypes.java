package com.matthieu.planariumview.network.entities;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.entity.vehicle.Boat;

/** Shared snapshot categories; vehicles never consume the player's mob quota. */
public final class MiniEntityTypes {
    public static final int MAX_MOBS = 64;
    public static final int MAX_VEHICLES = 64;
    public static final int MAX_ENTITIES = MAX_MOBS + MAX_VEHICLES;
    public static final String VEHICLE = "planariumview_is_vehicle";
    public static final String MOUNT = "planariumview_mount";

    private MiniEntityTypes() {}

    public static boolean vehicle(Entity entity) {
        return entity instanceof Boat || entity instanceof AbstractMinecart;
    }

    public static boolean supported(Entity entity) {
        return vehicle(entity) || entity instanceof LivingEntity && !(entity instanceof Player) && !(entity instanceof ArmorStand);
    }

    public static boolean alive(Entity entity) {
        return !(entity instanceof LivingEntity living) || !living.isDeadOrDying();
    }

    public static boolean vehicle(CompoundTag tag) {
        return tag.getBoolean(VEHICLE);
    }
}

package com.matthieu.planariumview;

import com.matthieu.planariumview.client.scenery.entities.*;
import com.matthieu.planariumview.mixin.entities.IronGolemAnimationAccessor;
import com.matthieu.planariumview.network.entities.MobAnimations;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateHolder;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import java.lang.reflect.*;
import java.util.*;

/** Native handlers and captured bed lookups; mixin accessors and world access are fixture boundaries. */
public final class CopyAnimationChecks {
    private static Field field(Class<?> type, String name) throws Exception {
        Field result = type.getDeclaredField(name); result.setAccessible(true); return result;
    }
    private static <T> T fixture(Class<T> type) throws Exception {
        Method m = MobAnimationIntegrationChecks.class.getDeclaredMethod("fixture", Class.class);
        m.setAccessible(true); return type.cast(m.invoke(null, type));
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }

    public static class Golem extends IronGolem implements IronGolemAnimationAccessor {
        boolean silent, failSound;
        int audibleSounds;
        public Golem() { super(null, null); }
        @Override public boolean isSilent() { return silent; }
        @Override public void setSilent(boolean value) { silent = value; }
        @Override public void playSound(net.minecraft.sounds.SoundEvent sound, float volume, float pitch) {
            if (!silent) audibleSounds++;
            if (failSound) throw new IllegalStateException("fixture failure");
        }
        private int read(String name) {
            try { return field(IronGolem.class, name).getInt(this); }
            catch (Exception e) { throw new RuntimeException(e); }
        }
        private void write(String name, int value) {
            try { field(IronGolem.class, name).setInt(this, value); }
            catch (Exception e) { throw new RuntimeException(e); }
        }
        public int planariumview$getAttackAnimationTick() { return read("attackAnimationTick"); }
        public void planariumview$setAttackAnimationTick(int value) { write("attackAnimationTick", value); }
        public int planariumview$getOfferFlowerTick() { return read("offerFlowerTick"); }
        public void planariumview$setOfferFlowerTick(int value) { write("offerFlowerTick", value); }
    }

    public static void run() throws Exception {
        Golem golem = fixture(Golem.class);
        MobEventAnimations.tick(golem, List.of(4));
        require(golem.getAttackAnimationTick() == 9 && golem.audibleSounds == 0 && !golem.silent,
                "vanilla golem attack replays silently and advances its counter");
        for (int i = 0; i < 12; i++) MobEventAnimations.tick(golem, List.of());
        require(golem.getAttackAnimationTick() == 0, "golem arm returns to rest without AI");
        MobEventAnimations.tick(golem, List.of(11));
        require(golem.getOfferFlowerTick() == 399, "vanilla flower offering");
        MobEventAnimations.tick(golem, List.of(34));
        require(golem.getOfferFlowerTick() == 0, "vanilla flower withdrawal");
        golem.silent = true; MobEventAnimations.tick(golem, List.of(4));
        require(golem.silent, "original silence preserved");
        golem.silent = false; golem.failSound = true; MobEventAnimations.tick(golem, List.of(4));
        require(!golem.silent, "original silence restored after failure");
        MobEventAnimations.calm(golem);
        require(golem.getAttackAnimationTick() == 0 && golem.getOfferFlowerTick() == 0, "idle clears native counters");
        require(MobAnimations.visualEvent(golem, 4) && !MobAnimations.visualEvent(golem, 3)
                && !MobAnimations.visualEvent(golem, 60), "native replay excludes death and particles");

        var world = fixture(MobAnimationIntegrationChecks.CapturedWater.class);
        Method makeMob = MobAnimationIntegrationChecks.class.getDeclaredMethod("mob", int.class); makeMob.setAccessible(true);
        var entity = (net.minecraft.world.entity.Entity) makeMob.invoke(null, 9100);
        Field activeEntity = field(GiantEntities.class, "renderingEntity"), activeWorld = field(GiantEntities.class, "renderingWorld");
        Object previousEntity = activeEntity.get(null), previousWorld = activeWorld.get(null);
        try {
            activeEntity.set(null, entity); activeWorld.set(null, world);
            require(GiantEntities.renderingCopy(entity) && !GiantEntities.renderingCopy(golem), "bed override scoped to current copy");
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockState state = fixture(BlockState.class);
                field(StateHolder.class, "owner").set(state, fixture(BedBlock.class));
                var values = new it.unimi.dsi.fastutil.objects.Reference2ObjectArrayMap<net.minecraft.world.level.block.state.properties.Property<?>, Comparable<?>>();
                values.put(BedBlock.FACING, direction); field(StateHolder.class, "values").set(state, values);
                world.block = state;
                for (Vec3 pos : List.of(new Vec3(12.5, 7.6875, -3.5), new Vec3(-20.5, -1.3125, 15.5))) {
                    entity.setPos(pos);
                    require(GiantEntities.bedOrientation(entity) == direction && world.blockQuery.equals(BlockPos.containing(pos)),
                            "bed uses translated copy position in all four directions");
                }
            }
        } finally { activeEntity.set(null, previousEntity); activeWorld.set(null, previousWorld); }
        require(!GiantEntities.renderingCopy(entity), "bed scope restored");
        System.out.println("PASS: native golem attack, flower, silence, countdown and copy beds in four directions");
    }
}

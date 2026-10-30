package com.matthieu.planariumview.server;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.matthieu.planariumview.config.Config;
import com.matthieu.planariumview.network.entities.MiniLivePayload;
import com.matthieu.planariumview.network.entities.MiniWatchPayload;
import com.matthieu.planariumview.network.entities.MiniEntityTypes;
import com.matthieu.planariumview.client.mini.entities.MiniEntities;
import com.matthieu.planariumview.client.scenery.entities.OutsideAquatic;
import com.matthieu.planariumview.mixin.entities.SceneryEntityAccessor;
import com.matthieu.planariumview.network.entities.MobEnvironment;
import com.matthieu.planariumview.network.entities.MobAnimations;
import com.matthieu.planariumview.client.scenery.entities.MobAnimationTimeline;
import com.google.common.collect.ImmutableList;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ChunkTracker;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.entity.vehicle.Minecart;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.fml.config.IConfigSpec;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.lang.reflect.*;
import java.util.*;

/** Real codecs, config, snapshot comparison, client interpolation and vanilla chunk propagation. */
public final class MiniatureLiveChecks {
    private static int assertions;
    private static final ResourceLocation POCKET = ResourceLocation.parse("checks:miniature");
    private static void require(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private static void protocol() {
        for (boolean watching : List.of(false, true)) {
            var sent = new MiniWatchPayload(watching, new BlockPos(-12345, 40, 9876), 32, true);
            var buf = Unpooled.buffer();
            try {
                MiniWatchPayload.STREAM_CODEC.encode(buf, sent);
                require(sent.equals(MiniWatchPayload.STREAM_CODEC.decode(buf)), "watch request round trip");
                require(!buf.isReadable(), "watch request fully decoded");
            } finally { buf.release(); }
        }
        for (int limit : new int[]{-1, 0, 8, 32, 64, 65, Integer.MAX_VALUE}) {
            var buf = Unpooled.buffer();
            try {
                buf.writeBoolean(true); BlockPos.STREAM_CODEC.encode(buf, BlockPos.ZERO);
                net.minecraft.network.codec.ByteBufCodecs.VAR_INT.encode(buf, limit); buf.writeBoolean(true);
                var watch = MiniWatchPayload.STREAM_CODEC.decode(buf);
                require(watch.mobLimit() == Math.clamp(limit, 0, 64) && watch.vehicles(), "watch limits bounded independently of vehicles");
            } finally { buf.release(); }
        }
        List<MiniLivePayload.Entry> entries = new ArrayList<>();
        for (int i = 0; i < 64; i++) entries.add(new MiniLivePayload.Entry(UUID.randomUUID(), i / 2f, 3, 16,
                i * 13 - 360, 359 - i * 17, i - 32, i % 8));
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            MiniLivePayload.STREAM_CODEC.encode(buf, new MiniLivePayload(POCKET, entries));
            int bytes = buf.readableBytes();
            var decoded = MiniLivePayload.STREAM_CODEC.decode(buf);
            require(decoded.pocketDim().equals(POCKET) && decoded.entries().size() == 64, "pocket and bounded list round trip");
            for (int i = 0; i < 64; i++) {
                var a = entries.get(i); var b = decoded.entries().get(i);
                require(a.id().equals(b.id()) && a.x() == b.x() && a.y() == b.y() && a.z() == b.z(), "UUID and position retained");
                require(a.flags() == b.flags(), "all ground/water flags retained");
                require(Math.abs(Mth.wrapDegrees(a.bodyYaw() - b.bodyYaw())) < 1.407f
                        && Math.abs(Mth.wrapDegrees(a.headYaw() - b.headYaw())) < 1.407f
                        && Math.abs(Mth.wrapDegrees(a.pitch() - b.pitch())) < 1.407f, "byte angles retain precision across wrap");
            }
            require(!buf.isReadable() && bytes * 5 < 13 * 1024, "light stream under 13 KiB/s for 64 mobs");
            System.out.println("PASS: live movement stream = " + bytes * 5 + " B/s for 64 mobs at 5 Hz");
            UUID id = UUID.randomUUID();
            var living = new com.matthieu.planariumview.network.entities.MobAnimations.Snapshot(7, 10, true, 3, true, true, 2, 6);
            var environment = new MobEnvironment(true, false, true, false, .5f, -.25f, 0, null);
            var hit = new com.matthieu.planariumview.network.entities.MobAnimations.Event(9, 42, 120,
                    com.matthieu.planariumview.network.entities.MobAnimations.Kind.HURT, false, 10);
            buf.clear();
            MiniLivePayload.STREAM_CODEC.encode(buf, new MiniLivePayload(POCKET, 123, true, List.of(new MiniLivePayload.Entry(id,
                    1, 2, 3, 0, 0, 0, 1, 42, new byte[]{1, 2, 3}, new byte[]{4}, living, environment)), List.of(hit)));
            var full = MiniLivePayload.STREAM_CODEC.decode(buf);
            var entry = full.entries().get(0);
            require(full.tick() == 123 && full.resetAnimations() && full.events().equals(List.of(hit)), "clock, reset and events round trip");
            require(entry.entityId() == 42 && Arrays.equals(entry.data(), new byte[]{1, 2, 3}) && Arrays.equals(entry.equipment(), new byte[]{4})
                    && living.equals(entry.living()) && environment.equals(entry.environment()), "appearance, animation and water round trip");
            require(!buf.isReadable(), "live animation entry fully decoded");
            for (int count : new int[]{-1, 129, Integer.MAX_VALUE}) {
                buf.clear(); buf.writeResourceLocation(POCKET); buf.writeVarInt(0); buf.writeBoolean(false); buf.writeVarInt(count);
                boolean rejected = false;
                try { MiniLivePayload.STREAM_CODEC.decode(buf); } catch (IllegalArgumentException e) { rejected = true; }
                require(rejected, "invalid entry count rejected before allocation");
            }
        } finally { buf.release(); }
    }

    private static CompoundTag snapshot(UUID id, double x, int age) {
        CompoundTag tag = new CompoundTag(), mob = new CompoundTag();
        mob.putUUID("UUID", id); mob.putString("id", "minecraft:cow"); mob.putInt("Age", age);
        ListTag pos = new ListTag(); pos.add(DoubleTag.valueOf(x)); pos.add(DoubleTag.valueOf(3)); pos.add(DoubleTag.valueOf(8));
        mob.put("Pos", pos); mob.putBoolean("OnGround", true);
        ListTag mobs = new ListTag(); mobs.add(mob); tag.put("mobs", mobs); tag.put("entities", new ListTag());
        return tag;
    }

    private static void snapshots() {
        UUID id = UUID.randomUUID();
        CompoundTag before = snapshot(id, 2, -100), after = snapshot(id, 10, -80), copy = after.copy();
        var mob = after.getList("mobs", Tag.TAG_COMPOUND).getCompound(0);
        mob.putBoolean("OnGround", false); mob.put("Rotation", new ListTag());
        require(MiniatureSync.appearance(before).equals(MiniatureSync.appearance(after)), "motion, ground and aging do not resend full NBT");
        require(after.getList("mobs", Tag.TAG_COMPOUND).getCompound(0).getList("Pos", Tag.TAG_DOUBLE).getDouble(0) == 10,
                "latest pose preserved for new watchers and final snapshot");
        require(copy.getList("mobs", Tag.TAG_COMPOUND).getCompound(0).getInt("Age") == mob.getInt("Age"), "comparison does not normalize cached age");
        mob.putInt("Age", 0);
        require(!MiniatureSync.appearance(before).equals(MiniatureSync.appearance(after)), "baby to adult resends appearance");
        mob.putInt("Age", -80); mob.putString("CustomName", "Mini");
        require(!MiniatureSync.appearance(before).equals(MiniatureSync.appearance(after)), "appearance changes still broadcast");
        require(!MiniatureSync.appearance(before).equals(MiniatureSync.appearance(snapshot(UUID.randomUUID(), 2, -100))), "replacement mob resends UUID list");
        after = before.copy(); after.getList("mobs", Tag.TAG_COMPOUND).clear();
        require(!MiniatureSync.appearance(before).equals(MiniatureSync.appearance(after)), "death of last mob sends empty list");
        after = before.copy();
        after.getList("mobs", Tag.TAG_COMPOUND).add(snapshot(UUID.randomUUID(), 4, 0).getList("mobs", Tag.TAG_COMPOUND).getCompound(0));
        copy = after.copy(); Collections.reverse(copy.getList("mobs", Tag.TAG_COMPOUND));
        require(MiniatureSync.appearance(copy).equals(MiniatureSync.appearance(after)), "entity enumeration order does not resend NBT");
    }

    /** Only ticket storage is a fixture; propagation is Minecraft's real ChunkTracker. */
    private static final class Tracker extends ChunkTracker {
        final Map<Long, Integer> sources = new HashMap<>(), levels = new HashMap<>();
        Tracker() { super(35, 16, 256); }
        protected int getLevelFromSource(long key) { return sources.getOrDefault(key, 34); }
        protected int getLevel(long key) { return levels.getOrDefault(key, 34); }
        protected void setLevel(long key, int level) { if (level >= 34) levels.remove(key); else levels.put(key, level); }
        void tickets(boolean add) {
            for (int x = 0; x < 2; x++) for (int z = 0; z < 2; z++) {
                long key = ChunkPos.asLong(x, z);
                if (add) sources.put(key, 31); else sources.remove(key);
                update(key, add ? 31 : 34, add);
            }
            runUpdates(Integer.MAX_VALUE);
        }
    }

    private static void tickets() throws Exception {
        var distance = MiniLive.class.getDeclaredField("TICKET_DISTANCE"); distance.setAccessible(true);
        require(33 - distance.getInt(null) == 31, "region ticket distance produces level 31");
        Tracker tracker = new Tracker(); tracker.tickets(true);
        require(tracker.levels.values().stream().filter(n -> n == 31).count() == 4, "exactly four entity-ticking chunks");
        require(tracker.levels.values().stream().filter(n -> n == 32).count() == 12, "twelve extra block-ticking chunks");
        require(tracker.levels.values().stream().filter(n -> n == 33).count() == 20, "twenty extra full chunks without ticks");
        tracker.tickets(true);
        require(tracker.levels.size() == 36, "shared tickets do not multiply loaded footprint");
        tracker.tickets(false);
        require(tracker.levels.isEmpty(), "last watcher release removes entire footprint");
        System.out.println("PASS: 4 entity + 12 block + 20 full chunks; ticket removal releases all 36");
    }

    private static void load(ModConfigSpec spec) throws Exception {
        CommentedConfig values = CommentedConfig.inMemory(); spec.correct(values);
        var ctor = Class.forName("net.neoforged.fml.config.LoadedConfig")
                .getDeclaredConstructor(CommentedConfig.class, java.nio.file.Path.class, ModConfig.class);
        ctor.setAccessible(true);
        spec.acceptConfig((IConfigSpec.ILoadedConfig) ctor.newInstance(values, null, null));
    }

    private static void options() throws Exception {
        require(!Config.miniatureLive(), "no world means no live miniature permission");
        load(Config.SERVER_SPEC); load(Config.CLIENT_SPEC);
        try {
            require(!Config.MINIATURE_LIVE.get() && Config.MINIATURE_LIVE_VIEW.get() && Config.miniatureLiveMax() == 4, "world OFF, player ON, cap 4 defaults");
            require(Config.MINIATURE_MOBS.get() == 32, "32 mobs by default");
            require(!Config.miniatureMobs() && !Config.miniatureVehicles() && !Config.miniatureLiveView(),
                    "world OFF disables all three effective miniature options");
            for (int mask = 0; mask < 16; mask++) for (int limit = 0; limit <= 64; limit += 8) {
                Config.ENABLED.set((mask & 1) != 0); Config.MINIATURE_MOBS.set(limit);
                Config.MINIATURE_LIVE_VIEW.set((mask & 4) != 0); Config.MINIATURE_LIVE.set((mask & 8) != 0);
                require(Config.miniatureLiveView() == ((mask & 13) == 13 && limit > 0),
                        "zero mobs disables direct movement even with vehicles shown");
                require(Config.miniatureMobLimit() == ((mask & 9) == 9 ? limit : 0), "master and world gate every mob limit");
                require(Config.miniatureVehicles() == ((mask & 9) == 9), "vehicles follow master and world permission");
            }
            Config.MINIATURE_LIVE.set(false);
            require(Config.MINIATURE_MOBS.get() == 64 && !Config.miniatureVehicles() && Config.MINIATURE_LIVE_VIEW.get(),
                    "world OFF preserves all player choices");
            Config.MINIATURE_LIVE.set(true); Config.MINIATURE_MOBS.set(0);
            require(!Config.miniatureLiveView() && Config.miniatureVehicles() && Config.MINIATURE_LIVE_VIEW.get(),
                    "zero mobs disables live mode without erasing the choice or hiding permitted static vehicles");
            Config.MINIATURE_MOBS.set(32);
            require(Config.miniatureLiveView(), "restoring mobs restores the remembered live setting");
        } finally { Config.SERVER_SPEC.acceptConfig(null); Config.CLIENT_SPEC.acceptConfig(null); }
    }

    private static void interpolation() throws Exception {
        Class<?> type = Class.forName("com.matthieu.planariumview.client.mini.entities.MiniEntities$LiveMob");
        Constructor<?> constructor = type.getDeclaredConstructor(); constructor.setAccessible(true);
        Object state = constructor.newInstance();
        Method accept = type.getDeclaredMethod("accept", MiniLivePayload.Entry.class, long.class, boolean.class);
        Method apply = type.getDeclaredMethod("apply", Entity.class, long.class);
        accept.setAccessible(true); apply.setAccessible(true);
        Class<?> fixtures = Class.forName("com.matthieu.planariumview.MobAnimationIntegrationChecks");
        Method create = fixtures.getDeclaredMethod("mob", int.class); create.setAccessible(true);
        LivingEntity mob = (LivingEntity) create.invoke(null, 1);
        UUID id = UUID.randomUUID(); long now = 2_000_000_000L;
        accept.invoke(state, new MiniLivePayload.Entry(id, 2, 3, 8, 179, 179, 0, 1), now, true);
        accept.invoke(state, new MiniLivePayload.Entry(id, 6, 5, 8, -179, -179, 20, 6), now, false);
        apply.invoke(state, mob, now + 100_000_000L);
        require(mob.getX() == 4 && mob.getY() == 3, "midpoint interpolates with miniature origin offset");
        require(Math.abs(Math.abs(mob.yBodyRot) - 180) < .001 && mob.getXRot() == 10, "yaw takes shortest path across wrap");
        require(mob.xo == mob.getX() && mob.yBodyRotO == mob.yBodyRot, "no second vanilla interpolation");
        apply.invoke(state, mob, now + 1_000_000_000L);
        require(mob.getX() == 6 && mob.getY() == 4, "missing packets clamp to final received position");
        Class<?> miniType = Class.forName("com.matthieu.planariumview.client.mini.entities.MiniEntities$MiniMob");
        Constructor<?> miniConstructor = miniType.getDeclaredConstructor(UUID.class, Entity.class);
        miniConstructor.setAccessible(true);
        Object mini = miniConstructor.newInstance(id, mob);
        Method tickLive = miniType.getDeclaredMethod("tickLive", type); tickLive.setAccessible(true);
        tickLive.invoke(mini, state);
        require(mob.isInWater() && mob.isUnderWater() && !mob.onGround(), "live copies use transmitted water and ground flags");
        require(mob.getDeltaMovement().x == 1 && mob.getDeltaMovement().y == .5, "swimming models receive velocity from pose differences");
        float speed = mob.walkAnimation.speed();
        require(speed > 0, "live movement animates walking");
        mob.hurtTime = 8; mob.deathTime = 9; mob.attackAnim = .5f;
        mob.setSharedFlagOnFire(true);
        Method tickIdle = miniType.getDeclaredMethod("tick"); tickIdle.setAccessible(true); tickIdle.invoke(mini);
        require(mob.hurtTime == 0 && mob.deathTime == 0 && mob.attackAnim == 0
                        && !((com.matthieu.planariumview.MobAnimationIntegrationChecks.VisualMob) mob).onFire,
                "idle fallback clears hit, death, attack and fire");
        require(mob.walkAnimation.speed() < speed && mob.getX() == 6, "idle fallback stops walking without moving the final pose");
        accept.invoke(state, new MiniLivePayload.Entry(id, 20, 3, 8, 0, 0, 0, 1), now, true);
        apply.invoke(state, mob, now);
        require(mob.getX() == 20, "resuming stale stream does not sweep across old positions");
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field result = type.getDeclaredField(name); result.setAccessible(true); return result;
    }

    private static <T> T fixture(Class<T> type) throws Exception {
        Method allocate = Class.forName("com.matthieu.planariumview.MobAnimationIntegrationChecks").getDeclaredMethod("fixture", Class.class);
        allocate.setAccessible(true); return type.cast(allocate.invoke(null, type));
    }

    /** Serialization and mixin accessors are boundaries; vehicle selection and playback stay production code. */
    public static class TestBoat extends Boat implements SceneryEntityAccessor, OutsideAquatic.EnvironmentCopy {
        public TestBoat() { super(null, null); }
        public void planariumview$setVehicle(Entity value) { set("vehicle", value); }
        public void planariumview$setPassengers(ImmutableList<Entity> value) { set("passengers", value); }
        public void planariumview$setLevel(Level value) { set("level", value); }
        public void planariumview$environment(MobEnvironment value) {
            set("wasTouchingWater", value.inWater()); set("wasEyeInWater", value.underwater()); set("onGround", value.onGround());
        }
        private void set(String name, Object value) {
            try { field(Entity.class, name).set(this, value); } catch (Exception e) { throw new RuntimeException(e); }
        }
        public boolean saveAsPassenger(CompoundTag tag) {
            tag.putUUID("UUID", getUUID()); tag.putString("id", "minecraft:boat"); tag.putString("Type", "oak");
            tag.put("Items", new ListTag()); return true;
        }
    }

    @SuppressWarnings("unchecked") private static void vehiclesAndCopies() throws Exception {
        Class<?> fixtures = Class.forName("com.matthieu.planariumview.MobAnimationIntegrationChecks");
        Method create = fixtures.getDeclaredMethod("mob", int.class); create.setAccessible(true);
        List<Entity> candidates = new ArrayList<>();
        ListTag tags = new ListTag();
        for (int i = 0; i < 70; i++) {
            Entity mob = (Entity) create.invoke(null, i); mob.setUUID(UUID.randomUUID());
            candidates.add(mob); candidates.add(i % 2 == 0 ? fixture(Boat.class) : fixture(Minecart.class));
            CompoundTag tag = snapshot(mob.getUUID(), i, 0).getList("mobs", Tag.TAG_COMPOUND).getCompound(0);
            tags.add(tag);
            CompoundTag vehicle = tag.copy(); vehicle.putUUID("UUID", UUID.randomUUID());
            vehicle.putBoolean(MiniEntityTypes.VEHICLE, true); tags.add(vehicle);
        }
        Method choose = MiniEntities.class.getDeclaredMethod("selectedTags", ListTag.class, int.class, boolean.class); choose.setAccessible(true);
        for (int limit = 0; limit <= 64; limit += 8) for (boolean vehicles : List.of(false, true)) {
            List<Entity> selected = MiniatureSync.selectEntities(candidates, limit, vehicles);
            require(selected.stream().filter(entity -> !MiniEntityTypes.vehicle(entity)).count() == limit, "server respects every mob slider step");
            require(selected.stream().filter(MiniEntityTypes::vehicle).count() == (vehicles ? 64 : 0), "boats and minecarts do not consume mob quota");
            List<CompoundTag> client = (List<CompoundTag>) choose.invoke(null, tags, limit, vehicles);
            require(client.size() == selected.size(), "snapshot display matches server streaming quotas including zero");
        }
        TestBoat boat = fixture(TestBoat.class); boat.setUUID(UUID.randomUUID());
        Entity passenger = candidates.getFirst();
        for (String name : List.of("position", "blockPosition", "chunkPosition", "levelCallback", "dimensions", "random", "passengers"))
            field(Entity.class, name).set(boat, field(Entity.class, name).get(passenger));
        boat.setPos(6, 3, 8);
        Method save = MiniatureSync.class.getDeclaredMethod("saveMob", Entity.class); save.setAccessible(true);
        CompoundTag saved = (CompoundTag) save.invoke(null, boat);
        require(saved != null && MiniEntityTypes.vehicle(saved) && saved.getString("Type").equals("oak"), "boat identity and appearance saved");
        require(!saved.contains("Items") && saved.getList("Pos", Tag.TAG_DOUBLE).getDouble(0) == 6, "inventory omitted while final vehicle position retained");

        Class<?> copyType = Class.forName(MiniEntities.class.getName() + "$MiniMob");
        Constructor<?> copyConstructor = copyType.getDeclaredConstructor(UUID.class, Entity.class); copyConstructor.setAccessible(true);
        Object boatCopy = copyConstructor.newInstance(boat.getUUID(), boat), riderCopy = copyConstructor.newInstance(passenger.getUUID(), passenger);
        field(copyType, "mount").set(riderCopy, boat.getUUID());
        Map<UUID, Object> copies = new HashMap<>(); copies.put(boat.getUUID(), boatCopy); copies.put(passenger.getUUID(), riderCopy);
        Method link = MiniEntities.class.getDeclaredMethod("linkMounts", Map.class); link.setAccessible(true); link.invoke(null, copies);
        require(passenger.getVehicle() == boat && boat.getPassengers().contains(passenger), "passengers retain riding pose on copied vehicles");
        copies.remove(boat.getUUID()); link.invoke(null, copies);
        require(passenger.getVehicle() == null, "hiding vehicles clears stale mount references");

        Class<?> stateType = Class.forName(MiniEntities.class.getName() + "$LiveMob");
        Constructor<?> stateConstructor = stateType.getDeclaredConstructor(); stateConstructor.setAccessible(true);
        Object state = stateConstructor.newInstance();
        Method accept = stateType.getDeclaredMethod("accept", MiniLivePayload.Entry.class, long.class, boolean.class); accept.setAccessible(true);
        Method apply = stateType.getDeclaredMethod("apply", Entity.class, long.class); apply.setAccessible(true);
        long now = 2_000_000_000L;
        accept.invoke(state, new MiniLivePayload.Entry(boat.getUUID(), 6, 3, 8, 179, 179, 0, 2), now, true);
        accept.invoke(state, new MiniLivePayload.Entry(boat.getUUID(), 10, 3, 8, -179, -179, 0, 2), now, false);
        apply.invoke(state, boat, now + 100_000_000L);
        require(boat.getX() == 8 && boat.getY() == 2 && Math.abs(Math.abs(boat.getYRot()) - 180) < .001,
                "nonliving vehicle interpolates position and yaw across wrap");
        Method tick = copyType.getDeclaredMethod("tickLive", stateType); tick.setAccessible(true); tick.invoke(boatCopy, state);
        require(boat.isInWater() && boat.getDeltaMovement().x == 1, "live vehicle receives its environment and velocity");

        MiniEntities.clear();
        var packet = new MiniLivePayload(POCKET, List.of(new MiniLivePayload.Entry(boat.getUUID(), 10, 3, 8, 0, 0, 0, 2)));
        MiniEntities.receiveLive(packet);
        Map<?, ?> scenes = (Map<?, ?>) field(MiniEntities.class, "LIVE").get(null);
        Object shared = scenes.get(POCKET);
        MiniEntities.receiveLive(packet);
        require(scenes.size() == 1 && scenes.get(POCKET) == shared, "all placed copies share one scene for the same interior");
        MiniEntities.receiveLive(new MiniLivePayload(ResourceLocation.parse("checks:other"), List.of()));
        require(scenes.size() == 2, "different interiors retain independent live scenes");
        MiniEntities.clear();
        require(scenes.isEmpty(), "disconnect releases shared scenes");
        System.out.println("PASS: vehicle quotas, snapshot appearance, passenger links, interpolation and shared Planarium live scenes");
    }

    private static void stableMiniatures() throws Exception {
        MiniatureSync.clear();
        var key = ResourceKey.create(Registries.DIMENSION, POCKET);
        var other = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse("checks:other"));
        Method create = Class.forName("com.matthieu.planariumview.MobAnimationIntegrationChecks").getDeclaredMethod("mob", int.class);
        create.setAccessible(true);
        List<Entity> crowd = new ArrayList<>();
        for (int i = 0; i < 70; i++) {
            Entity entity = (Entity) create.invoke(null, i); entity.setUUID(new UUID(0, i + 100)); crowd.add(entity);
        }
        List<Entity> admitted = MiniatureSync.selectTracked(key, crowd);
        require(admitted.size() == 64, "miniature capture retains its bounded global quota");
        List<Entity> first32 = MiniatureSync.selectEntities(admitted, 32, false);
        Entity newborn = (Entity) create.invoke(null, 100); newborn.setUUID(new UUID(0, 1)); crowd.add(newborn);
        for (int update = 0; update < 50; update++) {
            Collections.shuffle(crowd, new Random(update));
            List<Entity> next = MiniatureSync.selectTracked(key, crowd);
            require(next.equals(admitted), "new lower UUID and changing enumeration never evict existing miniature mobs");
            require(MiniatureSync.selectEntities(next, 32, false).equals(first32), "player's smaller limit keeps the same visible subset");
        }
        require(MiniatureSync.selectTracked(other, crowd).contains(newborn), "each Planarium has its own admission history");
        crowd.remove(admitted.getFirst());
        List<Entity> replaced = MiniatureSync.selectTracked(key, crowd);
        require(replaced.size() == 64 && replaced.subList(0, 63).equals(admitted.subList(1, 64)),
                "losing one mob preserves every other admission in order");
        require(replaced.getLast() == newborn, "new mob fills the free capture slot");
        List<Entity> visible = MiniatureSync.selectEntities(replaced, 32, false);
        require(visible.subList(0, 31).equals(first32.subList(1, 32)) && visible.getLast() == admitted.get(32),
                "one visible departure promotes exactly one waiting mob");
        MiniatureSync.clear();
        require(((Map<?, ?>) field(MiniatureSync.class, "MINI_ORDER").get(null)).isEmpty(), "server shutdown clears miniature admission history");
        System.out.println("PASS: stable miniature selections across births, removals, shared views and world changes");
    }

    /** Only type construction is a fixture; registry keys and selection use production methods. */
    private static EntityType<?> registeredType(String name) throws Exception {
        EntityType<?> type = fixture(EntityType.class);
        BuiltInRegistries.ENTITY_TYPE.createIntrusiveHolder(type);
        return Registry.register(BuiltInRegistries.ENTITY_TYPE, ResourceLocation.parse("checks:" + name), type);
    }

    private static Entity typedMob(int id, EntityType<?> type) throws Exception {
        Method create = Class.forName("com.matthieu.planariumview.MobAnimationIntegrationChecks").getDeclaredMethod("mob", int.class);
        create.setAccessible(true);
        Entity mob = (Entity) create.invoke(null, id);
        mob.setUUID(new UUID(0, id));
        field(Entity.class, "type").set(mob, type);
        return mob;
    }

    @SuppressWarnings("unchecked") private static void variedMiniatures() throws Exception {
        // Permit empty registry creation without launching Minecraft or loading a mod list.
        // The fixture agent skips EntityType's vanilla registration, but keeps getKey intact.
        field(Bootstrap.class, "isBootstrapped").setBoolean(null, true);
        EntityType<?> sheep = registeredType("sheep"), cow = registeredType("cow");
        require(!EntityType.getKey(sheep).equals(EntityType.getKey(cow)), "variety fixtures have distinct registered types");
        var key = ResourceKey.create(Registries.DIMENSION, POCKET);
        List<Entity> crowd = new ArrayList<>();
        for (int i = 1; i <= 20; i++) crowd.add(typedMob(i, i <= 15 ? sheep : cow));
        List<Entity> visible = null;
        for (int seed = 0; seed < 10; seed++) {
            MiniatureSync.clear();
            Collections.shuffle(crowd, new Random(seed));
            List<Entity> selected = MiniatureSync.selectEntities(MiniatureSync.selectTracked(key, crowd), 8, false);
            require(selected.stream().filter(entity -> entity.getType() == cow).count() == 4
                            && selected.stream().filter(entity -> entity.getType() == sheep).count() == 4,
                    "15 sheep and 5 cows show exactly 4 of each with a limit of 8");
            if (visible == null) visible = selected;
            require(selected.equals(visible), "initial variety order is independent of entity enumeration");
        }
        require(visible.getFirst().getType() == cow && visible.getFirst().getUUID().equals(new UUID(0, 16))
                        && visible.get(1).getUUID().equals(new UUID(0, 1)),
                "equal type counts use type ID order, then UUID within each type");
        ListTag tags = new ListTag();
        for (Entity entity : MiniatureSync.selectTracked(key, crowd)) {
            CompoundTag tag = new CompoundTag(); tag.putUUID("UUID", entity.getUUID());
            tag.putString("id", EntityType.getKey(entity.getType()).toString()); tags.add(tag);
        }
        Method choose = MiniEntities.class.getDeclaredMethod("selectedTags", ListTag.class, int.class, boolean.class);
        choose.setAccessible(true);
        List<CompoundTag> client = (List<CompoundTag>) choose.invoke(null, tags, 8, false);
        require(client.stream().map(tag -> tag.getUUID("UUID")).toList().equals(visible.stream().map(Entity::getUUID).toList()),
                "client snapshot selection shows the same varied subset as live updates");
        crowd.add(typedMob(-1, sheep)); crowd.add(typedMob(0, cow));
        for (int update = 0; update < 10; update++) {
            Collections.shuffle(crowd, new Random(update));
            require(MiniatureSync.selectEntities(MiniatureSync.selectTracked(key, crowd), 8, false).equals(visible),
                    "new mobs of either type never replace the displayed varied subset");
        }
        MiniatureSync.clear();
        crowd.clear();
        for (int i = 100; i < 164; i++) crowd.add(typedMob(i, cow));
        List<Entity> admitted = MiniatureSync.selectTracked(key, crowd);
        crowd.remove(admitted.getFirst());
        crowd.add(typedMob(0, cow));
        Entity newcomer = typedMob(1, sheep); crowd.add(newcomer); crowd.add(typedMob(2, sheep));
        List<Entity> replaced = MiniatureSync.selectTracked(key, crowd);
        require(replaced.size() == 64 && replaced.subList(0, 63).equals(admitted.subList(1, 64)),
                "variety preserves all retained mobs in order when filling a free slot");
        require(replaced.getLast() == newcomer, "least represented type fills a free slot before type ID and UUID ordering");
        MiniatureSync.clear();
        System.out.println("PASS: 15 sheep + 5 cows -> 4 of each at limit 8; stable order and varied replacements");
    }

    @SuppressWarnings("unchecked")
    private static void emptySnapshotPreservesPlayback() throws Exception {
        Class<?> type = Class.forName("com.matthieu.planariumview.client.mini.entities.MiniEntities$Mobs");
        Constructor<?> constructor = type.getDeclaredConstructor(ResourceLocation.class, Level.class);
        constructor.setAccessible(true);
        Object playback = constructor.newInstance(POCKET, null);
        Map<Object, Object> copies = (Map<Object, Object>) field(MiniEntities.class, "MOBS").get(null);
        Object miniature = new Object();
        copies.put(miniature, playback);
        try {
            MiniEntities.receive(new com.matthieu.planariumview.network.entities.MiniEntitiesPayload(POCKET, new CompoundTag()));
            require(copies.get(miniature) == playback, "empty snapshot preserves playback until death animation completes");
        } finally { MiniEntities.clear(); }
    }

    private static MiniLivePayload.Entry animationEntry(UUID id, int networkId, MobAnimations.Snapshot state) {
        return new MiniLivePayload.Entry(id, 1, 2, 3, 0, 0, 0, 1, networkId,
                new byte[0], new byte[0], state, null);
    }

    private static Map<?, ?> liveMobs() throws Exception {
        Map<?, ?> scenes = (Map<?, ?>) field(MiniEntities.class, "LIVE").get(null);
        Object scene = scenes.get(POCKET);
        return (Map<?, ?>) field(scene.getClass(), "mobs").get(scene);
    }

    private static MobAnimationTimeline timeline(Object state) throws Exception {
        return (MobAnimationTimeline) field(state.getClass(), "timeline").get(state);
    }

    private static void animationRecovery() throws Exception {
        UUID id = UUID.randomUUID();
        var alive = new MobAnimations.Snapshot(0, 10, false, 0, false, false, 0, 6);
        var dying = new MobAnimations.Snapshot(0, 10, true, 3, true, true, 2, 6);
        try {
            MiniEntities.clear();
            MiniEntities.receiveLive(new MiniLivePayload(POCKET, 96, false,
                    List.of(animationEntry(id, 42, alive)), List.of()));
            MiniEntities.receiveLive(new MiniLivePayload(POCKET, 100, true,
                    List.of(animationEntry(id, 42, dying)), List.of()));
            MobAnimationTimeline clock = timeline(liveMobs().get(id));
            clock.advance(100);
            require(clock.state().dying() && clock.state().deathTime() == 3,
                    "reset retains the replacement death snapshot");
            require(clock.state().swinging() && clock.state().offHand(),
                    "reset retains the replacement attack snapshot");

            MiniEntities.clear();
            MiniEntities.receiveLive(new MiniLivePayload(POCKET, 100, false,
                    List.of(animationEntry(id, 42, dying)), List.of()));
            MiniEntities.receiveLive(new MiniLivePayload(POCKET, 104, false, List.of(), List.of(
                    new MobAnimations.Event(1, 42, 102, MobAnimations.Kind.POOF, false, 0))));
            require(liveMobs().containsKey(id), "two packets before animation tick retain pending death and poof");
            clock = timeline(liveMobs().get(id)); clock.advance(104);
            require(clock.ended() && clock.takePoof() && !clock.takePoof(), "pending death emits exactly one poof");
            MiniEntities.receiveLive(new MiniLivePayload(POCKET, 108, false, List.of(), List.of()));
            require(!liveMobs().containsKey(id), "finished death releases its live state");

            MiniEntities.clear();
            MiniEntities.receiveLive(new MiniLivePayload(POCKET, 100, false,
                    List.of(animationEntry(id, 42, alive)), List.of()));
            clock = timeline(liveMobs().get(id)); clock.advance(100);
            MiniEntities.receiveLive(new MiniLivePayload(POCKET, 104, false, List.of(), List.of(
                    new MobAnimations.Event(1, 42, 101, MobAnimations.Kind.DEATH, false, 0),
                    new MobAnimations.Event(2, 42, 102, MobAnimations.Kind.POOF, false, 0))));
            require(liveMobs().containsKey(id), "removal packet delivers death events before pruning absent mobs");
            clock.advance(104);
            require(clock.ended() && clock.takePoof(), "removal packet death and poof are played");

            MiniEntities.clear();
            MiniEntities.receiveLive(new MiniLivePayload(POCKET, 100, false,
                    List.of(animationEntry(id, 42, dying)), List.of()));
            for (int tick = 104; tick <= 144; tick += 4) {
                MiniEntities.receiveLive(new MiniLivePayload(POCKET, tick, false, List.of(), List.of()));
                require(liveMobs().containsKey(id), "pending death retained within the two-second grace period");
            }
            MiniEntities.receiveLive(new MiniLivePayload(POCKET, 148, false, List.of(), List.of()));
            require(!liveMobs().containsKey(id), "missing poof cannot retain a dead mob indefinitely");
        } finally { MiniEntities.clear(); }
        System.out.println("PASS: animation reset, batched death packets, removal events, unique poof and timeout");
    }

    private static void dyingPacketQuota() throws Exception {
        Method create = Class.forName("com.matthieu.planariumview.MobAnimationIntegrationChecks")
                .getDeclaredMethod("mob", int.class);
        create.setAccessible(true);
        List<Entity> active = new ArrayList<>();
        List<LivingEntity> dying = new ArrayList<>();
        Set<UUID> sent = new HashSet<>();
        for (int i = 0; i < 70; i++) {
            LivingEntity mob = (LivingEntity) create.invoke(null, i);
            mob.setUUID(UUID.randomUUID()); active.add(mob);
            Boat boat = fixture(Boat.class); boat.setUUID(UUID.randomUUID()); active.add(boat);
            LivingEntity dead = (LivingEntity) create.invoke(null, 100 + i);
            dead.setUUID(UUID.randomUUID()); dying.add(dead); sent.add(dead.getUUID());
        }
        for (int deaths : new int[]{0, 1, 32, 64, 70}) {
            for (int limit : new int[]{0, 8, 32, 64}) {
                List<Entity> selected = MiniLive.selectForViewer(active, dying.subList(0, deaths), sent, limit, true);
                int retained = Math.min(deaths, 64);
                require(selected.size() == 64 + retained + Math.min(limit, 64 - retained),
                        "dying mobs reserve packet slots before live replacements");
                require(selected.containsAll(dying.subList(0, retained)), "already displayed deaths keep their slots");
                var entries = selected.stream().map(entity -> animationEntry(entity.getUUID(), 42, null)).toList();
                var buf = new FriendlyByteBuf(Unpooled.buffer());
                try {
                    MiniLivePayload.STREAM_CODEC.encode(buf, new MiniLivePayload(POCKET, 100, false, entries, List.of()));
                    var decoded = MiniLivePayload.STREAM_CODEC.decode(buf);
                    require(decoded.entries().size() == selected.size() && !buf.isReadable(),
                            "production selection including dying mobs fits the wire limit");
                } finally { buf.release(); }
            }
        }
        require(MiniLive.selectForViewer(active, dying, Set.of(), 64, true).stream().noneMatch(dying::contains),
                "unseen dying mobs never consume viewer slots");
        System.out.println("PASS: packet quotas with 64 vehicles, replacements and up to 70 dying mobs");
    }

    public static void main(String[] args) throws Exception {
        protocol(); snapshots(); tickets(); options(); interpolation(); vehiclesAndCopies(); stableMiniatures(); variedMiniatures();
        emptySnapshotPreservesPlayback();
        animationRecovery(); dyingPacketQuota();
        System.out.println("PASS: " + assertions + " live miniature assertions");
    }
}

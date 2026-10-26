package com.matthieu.planariumview;

import com.matthieu.planariumview.network.entities.MobAnimations;
import com.matthieu.planariumview.network.entities.MobEnvironment;
import com.matthieu.planariumview.network.entities.OutsideMobsPayload;
import com.matthieu.planariumview.server.entities.MobEventQueue;
import com.matthieu.planariumview.server.entities.OutsideMobsServer;
import com.google.common.collect.ImmutableList;
import com.matthieu.planariumview.client.scenery.entities.OutsideMobs;
import com.matthieu.planariumview.client.scenery.entities.OutsideAquatic;
import com.matthieu.planariumview.client.scenery.entities.MobEffects;
import com.matthieu.planariumview.client.scenery.entities.MobShadows;
import com.matthieu.planariumview.client.scenery.entities.GiantEntities;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.matthieu.planariumview.config.Config;
import com.matthieu.planariumview.mixin.entities.SceneryEntityAccessor;
import com.matthieu.planariumview.network.*;
import com.matthieu.planariumview.server.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.network.protocol.game.*;
import net.minecraft.resources.*;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Mth;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.animal.Squid;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.common.ModConfigSpec;
import sun.misc.Unsafe;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import java.lang.reflect.*;
import java.util.*;

/** Controlled world boundaries around real server capture and client receive/tick/prepare paths. */
public final class MobAnimationIntegrationChecks {
    private static final Unsafe UNSAFE;
    static {
        try { Field field = field(Unsafe.class, "theUnsafe"); UNSAFE = (Unsafe) field.get(null); }
        catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    private static final ResourceLocation POCKET = ResourceLocation.parse("checks:pocket");
    private static final ResourceKey<Level> SOURCE = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse("checks:source"));
    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static <T> T fixture(Class<T> type) throws Exception { return type.cast(UNSAFE.allocateInstance(type)); }
    private static Object construct(Class<?> type) throws Exception {
        Constructor<?> constructor = type.getDeclaredConstructor(); constructor.setAccessible(true); return constructor.newInstance();
    }
    private static void require(boolean result, String message) { if (!result) throw new AssertionError(message); }
    private static void config(ModConfigSpec.ConfigValue<?> option, Object value) throws Exception {
        field(ModConfigSpec.ConfigValue.class, "cachedValue").set(option, value);
    }

    private static void environment(Entity entity, MobEnvironment state) {
        try {
            field(Entity.class, "wasTouchingWater").setBoolean(entity, state.inWater());
            field(Entity.class, "wasEyeInWater").setBoolean(entity, state.underwater());
            field(Entity.class, "onGround").setBoolean(entity, state.onGround());
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    public static class VisualMob extends LivingEntity implements SceneryEntityAccessor, MobEffects.PotionParticles, OutsideAquatic.EnvironmentCopy {
        float health;
        int hurtCalls;
        List<ParticleOptions> effects;
        boolean ambient, invisible;
        MobEnvironment environment;
        public VisualMob() { super(null, null); }
        public void planariumview$environment(MobEnvironment state) { environment = state; environment(this, state); }
        @Override public boolean isInWaterOrBubble() { return environment != null && environment.waterOrBubble(); }
        public List<ParticleOptions> planariumview$effectParticles() { return effects; }
        public boolean planariumview$ambientEffects() { return ambient; }
        @Override public boolean isInvisible() { return invisible; }
        @Override public float getHealth() { return health; }
        @Override public void setHealth(float value) { health = value; }
        @Override public Iterable<ItemStack> getArmorSlots() { return List.of(); }
        @Override public ItemStack getItemBySlot(EquipmentSlot slot) { return ItemStack.EMPTY; }
        @Override public void setItemSlot(EquipmentSlot slot, ItemStack stack) {}
        @Override public HumanoidArm getMainArm() { return HumanoidArm.RIGHT; }
        @Override public int getCurrentSwingDuration() { return 12; }
        @Override public void animateHurt(float yaw) { super.animateHurt(yaw); hurtCalls++; }
        @Override protected AABB makeBoundingBox() { return new AABB(getX() - .3, getY(), getZ() - .3, getX() + .3, getY() + 1.8, getZ() + .3); }
        public void planariumview$setVehicle(Entity entity) { try { field(Entity.class, "vehicle").set(this, entity); } catch (Exception e) { throw new RuntimeException(e); } }
        public void planariumview$setPassengers(ImmutableList<Entity> entities) { try { field(Entity.class, "passengers").set(this, entities); } catch (Exception e) { throw new RuntimeException(e); } }
        public void planariumview$setLevel(Level level) { setLevel(level); }
    }

    private static VisualMob mob(int id) throws Exception {
        VisualMob mob = fixture(VisualMob.class); mob.health = 20; mob.setId(id);
        field(Entity.class, "position").set(mob, Vec3.ZERO);
        mob.setDeltaMovement(Vec3.ZERO);
        field(Entity.class, "blockPosition").set(mob, BlockPos.ZERO);
        field(Entity.class, "chunkPosition").set(mob, new ChunkPos(0, 0));
        field(Entity.class, "levelCallback").set(mob, EntityInLevelCallback.NULL);
        field(Entity.class, "dimensions").set(mob, EntityDimensions.scalable(.6F, 1.8F));
        field(Entity.class, "random").set(mob, RandomSource.create(id));
        field(LivingEntity.class, "walkAnimation").set(mob, new WalkAnimationState());
        mob.effects = List.of();
        mob.planariumview$setPassengers(ImmutableList.of());
        return mob;
    }

    public static class VisualSquid extends Squid implements OutsideAquatic.EnvironmentCopy {
        MobEnvironment environment;
        public VisualSquid() { super(null, null); }
        public void planariumview$environment(MobEnvironment state) { environment = state; environment(this, state); }
        @Override public boolean isInWaterOrBubble() { return environment != null && environment.waterOrBubble(); }
    }

    @SuppressWarnings("unchecked") private static void server() throws Exception {
        Class<?> stateType = Class.forName(OutsideMobsServer.class.getName() + "$State");
        Class<?> sentType = Class.forName(OutsideMobsServer.class.getName() + "$Sent");
        Map<UUID, Object> states = (Map<UUID, Object>) field(OutsideMobsServer.class, "STATES").get(null);
        OutsideMobsServer.clearAll();
        UUID firstId = UUID.randomUUID(), secondId = UUID.randomUUID();
        Object first = construct(stateType), second = construct(stateType);
        for (Object state : List.of(first, second)) {
            field(stateType, "source").set(state, SOURCE);
            ((Map<Integer, Object>) field(stateType, "sent").get(state)).put(71, construct(sentType));
        }
        states.put(firstId, first); states.put(secondId, second);
        ServerLevel world = fixture(ServerLevel.class);
        MinecraftServer server = fixture(DedicatedServer.class);
        field(ServerLevel.class, "server").set(world, server);
        field(Level.class, "dimension").set(world, SOURCE);
        field(MinecraftServer.class, "tickCount").setInt(server, 100);
        VisualMob source = mob(71); source.planariumview$setLevel(world);
        OutsideMobsServer.capture(source, fixture(ClientboundDamageEventPacket.class));
        OutsideMobsServer.capture(source, new ClientboundAnimatePacket(source, 3));
        OutsideMobsServer.capture(source, new ClientboundEntityEventPacket(source, (byte) 3));
        OutsideMobsServer.capture(source, new ClientboundEntityEventPacket(source, (byte) 60));
        OutsideMobsServer.capture(source, new ClientboundEntityEventPacket(source, (byte) 29)); // shield block
        MobEventQueue firstQueue = (MobEventQueue) field(stateType, "events").get(first);
        MobEventQueue secondQueue = (MobEventQueue) field(stateType, "events").get(second);
        List<MobAnimations.Event> events = firstQueue.drain();
        require(events.size() == 4 && secondQueue.drain().equals(events), "real capture dispatches all accepted animation kinds to both viewers");
        require(events.get(1).offHand() && events.get(1).duration() == 12, "capture reads the source hand and swing duration");
        source.setId(99); OutsideMobsServer.capture(source, new ClientboundAnimatePacket(source, 0));
        require(firstQueue.drain().isEmpty(), "untracked entity cannot enqueue events");
        source.setId(71);
        field(Level.class, "dimension").set(world, ResourceKey.create(Registries.DIMENSION, POCKET));
        OutsideMobsServer.capture(source, new ClientboundAnimatePacket(source, 0));
        require(firstQueue.drain().isEmpty(), "same entity ID in another world cannot enqueue events");
        OutsideMobsServer.forget(firstId); OutsideMobsServer.logout(secondId);
        require(states.isEmpty(), "leaving/disconnecting releases observer queues");
        OutsideMobsServer.clearAll();
    }

    private static OutsideMobsPayload.Entry entry(int id, int vehicle) {
        return new OutsideMobsPayload.Entry(id, 2, 70, 4, 0, 0, 0, 0, vehicle, 0, null, null, new byte[0], new byte[0],
                new MobAnimations.Snapshot(0, 0, false, 0, false, false, 0, 6));
    }
    private static OutsideMobsPayload packet(int tick, boolean full, List<OutsideMobsPayload.Entry> entries, int[] removed, List<MobAnimations.Event> events) {
        return new OutsideMobsPayload(POCKET, 0, new BlockPos(1000, 0, 0), tick, full, entries, removed, events, false);
    }

    @SuppressWarnings("unchecked") private static void client() throws Exception {
        config(Config.ENABLED, true); config(Config.OUTSIDE_MOBS, true); config(Config.OUTSIDE_VEHICLES, false);
        config(Config.SCENERY_PARTICLES, false); // Particle sampling is checked separately without a GPU engine.
        OutsideMobs.logout();
        ClientLevel world = fixture(ClientLevel.class);
        field(Level.class, "dimension").set(world, ResourceKey.create(Registries.DIMENSION, POCKET));
        Minecraft.getInstance().level = world;
        field(OutsideMobs.class, "level").set(null, world);
        field(OutsideMobs.class, "pocket").set(null, POCKET);
        field(OutsideMobs.class, "clientTick").setInt(null, 100);
        field(OutsideMobs.class, "warmedUp").setBoolean(null, true);
        Class<?> copyType = Class.forName(OutsideMobs.class.getName() + "$Copy");
        Constructor<?> constructor = copyType.getDeclaredConstructor(Entity.class); constructor.setAccessible(true);
        Map<Integer, Object> copies = (Map<Integer, Object>) field(OutsideMobs.class, "COPIES").get(null);
        VisualMob mount = mob(70), rider = mob(71);
        copies.put(70, constructor.newInstance(mount)); copies.put(71, constructor.newInstance(rider));
        Method prepare = OutsideMobs.class.getDeclaredMethod("prepare", float.class, BlockPos.class); prepare.setAccessible(true);
        OutsideMobs.receive(packet(100, true, List.of(entry(70, -1), entry(71, 70)), new int[0], List.of()));
        field(OutsideMobs.class, "clientTick").setInt(null, 102);
        OutsideMobs.receive(packet(102, false, List.of(), new int[0], List.of()));
        require(((List<?>) prepare.invoke(null, .5F, BlockPos.ZERO)).size() == 2, "stationary copies become ready from heartbeat poses");
        require(rider.getVehicle() == mount && mount.getPassengers().equals(List.of(rider)), "mount chain connects only copies");
        var hit = new MobAnimations.Event(1, 71, 103, MobAnimations.Kind.HURT, false, 10);
        field(OutsideMobs.class, "clientTick").setInt(null, 104);
        OutsideMobs.receive(packet(104, false, List.of(), new int[]{70}, List.of(hit)));
        require(copies.containsKey(70) && rider.getVehicle() == mount, "receiving a removal keeps the mount until rendered time");
        OutsideMobs.tick(); OutsideMobs.tick();
        require(rider.hurtTime > 0 && rider.hurtCalls == 1, "receive/tick drives real vanilla animateHurt once");
        OutsideMobs.receive(packet(104, false, List.of(), new int[]{70}, List.of(hit)));
        for (int i = 0; i < 4; i++) OutsideMobs.tick();
        require(!copies.containsKey(70) && rider.getVehicle() == null && mount.getPassengers().isEmpty(), "delayed mount removal clears both link directions");
        require(rider.hurtCalls == 1, "duplicate packet cannot replay hit in production client");
        var death = new MobAnimations.Event(2, 71, 110, MobAnimations.Kind.DEATH, false, 0);
        OutsideMobs.receive(packet(110, false, List.of(), new int[0], List.of(death)));
        while (field(OutsideMobs.class, "clientTick").getInt(null) < 128) {
            OutsideMobs.tick();
            int now = field(OutsideMobs.class, "clientTick").getInt(null);
            if (now % 2 == 0) OutsideMobs.receive(packet(now, false, List.of(), new int[0], List.of()));
        }
        require(rider.deathTime == 15 && rider.health == 0, "death advances on delayed clock without ticking the real entity");
        Object riderCopy = copies.get(71);
        field(copyType, "ready").setBoolean(riderCopy, false); // No GPU particle engine in this world fixture.
        var poof = new MobAnimations.Event(3, 71, 130, MobAnimations.Kind.POOF, false, 0);
        OutsideMobs.receive(packet(130, true, List.of(), new int[]{71}, List.of(poof)));
        require(copies.containsKey(71), "full snapshot omission cannot immediately cut off death");
        for (int i = 0; i < 4; i++) OutsideMobs.tick();
        require(copies.containsKey(71) && rider.deathTime == 19, "production client keeps the nineteenth death tick");
        OutsideMobs.tick();
        require(!copies.containsKey(71) && rider.deathTime == 20, "terminal event removes copy after twenty death ticks");
        copies.put(71, constructor.newInstance(mob(71)));
        int token = OutsideMobs.resyncCounter(); OutsideMobs.clear();
        OutsideMobs.receive(packet(130, true, List.of(), new int[0], List.of(hit)));
        require(copies.isEmpty() && OutsideMobs.resyncCounter() != token, "old view cannot restore copies or animations");
        OutsideMobs.logout(); Minecraft.getInstance().level = null;
    }

    @SuppressWarnings("unchecked") private static void aquatic() throws Exception {
        var water = new MobEnvironment(true, true, true, false, 0, .25F, .3F, new MobEnvironment.SquidPose(20, 350, .7F));
        VisualSquid squid = fixture(VisualSquid.class);
        OutsideAquatic.apply(squid, water);
        require(squid.isInWater() && squid.isUnderWater() && squid.isInWaterOrBubble() && !squid.onGround(), "copies use the source environment without querying a client world");
        require(squid.xBodyRot == 20 && squid.xBodyRotO == 20 && squid.zBodyRot == 350 && squid.zBodyRotO == 350
                && squid.tentacleAngle == .7F && squid.oldTentacleAngle == .7F, "squid renderer receives the interpolated body and tentacles without a second interpolation");
        Method capture = OutsideMobsServer.class.getDeclaredMethod("environment", Entity.class); capture.setAccessible(true);
        require(water.equals(capture.invoke(null, squid)), "server captures water, velocity and squid pose from real vanilla fields");
        var bubble = new MobEnvironment(false, false, true, false, 0, .2F, 0, null);
        VisualMob swimmer = mob(73);
        OutsideAquatic.apply(swimmer, bubble);
        require(!swimmer.isInWater() && !swimmer.isUnderWater() && swimmer.isInWaterOrBubble()
                && bubble.equals(capture.invoke(null, swimmer)), "bubble columns remain distinct from ordinary water");

        OutsideMobs.logout();
        ClientLevel world = fixture(ClientLevel.class);
        field(Level.class, "dimension").set(world, ResourceKey.create(Registries.DIMENSION, POCKET));
        Minecraft.getInstance().level = world;
        field(OutsideMobs.class, "level").set(null, world);
        field(OutsideMobs.class, "pocket").set(null, POCKET);
        field(OutsideMobs.class, "clientTick").setInt(null, 100);
        field(OutsideMobs.class, "warmedUp").setBoolean(null, true);
        Class<?> copyType = Class.forName(OutsideMobs.class.getName() + "$Copy");
        Constructor<?> constructor = copyType.getDeclaredConstructor(Entity.class); constructor.setAccessible(true);
        Map<Integer, Object> copies = (Map<Integer, Object>) field(OutsideMobs.class, "COPIES").get(null);
        copies.put(73, constructor.newInstance(swimmer));
        var wet = new MobEnvironment(true, true, true, false, 0, .25F, .3F, null);
        var slower = new MobEnvironment(true, true, true, false, 0, .25F, .1F, null);
        var dry = new MobEnvironment(false, false, false, true, 0, 0, 0, null);
        OutsideMobs.receive(packet(100, true, List.of(aquaticEntry(70, wet)), new int[0], List.of()));
        field(OutsideMobs.class, "clientTick").setInt(null, 102);
        OutsideMobs.receive(packet(102, false, List.of(aquaticEntry(70.5F, slower)), new int[0], List.of()));
        BlockPos photo = new BlockPos(1000, 64, 0);
        require(OutsideMobs.prepare(0, photo).equals(List.of(swimmer)), "aquatic copies wait for the same two snapshots as other mobs");
        OutsideMobs.tick(); OutsideMobs.tick(); // Displayed tick 101, midway between the wet snapshots.
        require(swimmer.walkAnimation.speed() > 0, "vertical swimming advances the actual vanilla walk animation");
        OutsideMobs.prepare(0, photo);
        require(Math.abs(swimmer.getY() - 6.25) < .00001 && Math.abs(swimmer.getDeltaMovement().z - .2) < .00001
                && swimmer.getDeltaMovement().y == .25, "position and swim velocity share the delayed display clock and survive photo rebasing");
        OutsideMobs.receive(packet(104, false, List.of(aquaticEntry(71, dry)), new int[0], List.of()));
        OutsideMobs.prepare(0, photo);
        require(swimmer.isInWater() && !swimmer.onGround(), "receiving a dry pose cannot change the earlier displayed wet pose");
        field(OutsideMobs.class, "clientTick").setInt(null, 106);
        OutsideMobs.receive(packet(106, false, List.of(), new int[0], List.of()));
        OutsideMobs.prepare(0, photo);
        require(swimmer.isInWater() && !swimmer.onGround(), "water flags stay wet until the transition tick");
        OutsideMobs.tick(); OutsideMobs.prepare(0, photo);
        require(!swimmer.isInWater() && !swimmer.isUnderWater() && !swimmer.isInWaterOrBubble() && swimmer.onGround()
                && swimmer.getDeltaMovement().equals(Vec3.ZERO), "dry/ground pose and stopped velocity take effect exactly at their delayed tick");
        OutsideMobs.tick(); OutsideMobs.prepare(0, photo);
        require(!swimmer.isInWater() && swimmer.onGround() && swimmer.getDeltaMovement().equals(Vec3.ZERO), "stationary heartbeats preserve the latest aquatic environment");
        OutsideMobs.logout(); Minecraft.getInstance().level = null;
    }

    private static OutsideMobsPayload.Entry aquaticEntry(float y, MobEnvironment environment) {
        return new OutsideMobsPayload.Entry(73, 2, y, 4, 0, 0, 0, 0, -1, 0, null, null, new byte[0], new byte[0],
                new MobAnimations.Snapshot(0, 0, false, 0, false, false, 0, 6), environment);
    }

    private static void effects() throws Exception {
        VisualMob mob = mob(72);
        mob.setPos(100, 64, -100);
        ParticleOptions first = () -> null, second = () -> null; // Opaque options must survive sampling unchanged.
        mob.effects = List.of(first, second);
        for (boolean invisible : List.of(false, true)) for (boolean ambient : List.of(false, true)) {
            mob.invisible = invisible; mob.ambient = ambient;
            RandomSource random = RandomSource.create(72);
            int count = 0, firstCount = 0;
            for (int i = 0; i < 16000; i++) {
                MobEffects.Emission emission = MobEffects.sample(mob, random);
                if (emission == null) continue;
                count++;
                require(emission.options() == first || emission.options() == second, "particle options retain their identity and metadata");
                if (emission.options() == first) firstCount++;
                require(emission.x() >= 100 - mob.getBbWidth() / 2 && emission.x() <= 100 + mob.getBbWidth() / 2
                        && emission.y() >= 64 && emission.y() <= 64 + mob.getBbHeight()
                        && emission.z() >= -100 - mob.getBbWidth() / 2 && emission.z() <= -100 + mob.getBbWidth() / 2,
                        "potion particles start within the interpolated mob's dimensions");
            }
            double expected = 16000.0 / ((invisible ? 15 : 4) * (ambient ? 5 : 1));
            require(Math.abs(count - expected) < expected * .15 + 20, "vanilla ambient/invisible emission rates");
            require(firstCount > count * .35 && firstCount < count * .65, "all synchronized potion types are represented");
        }
        mob.effects = List.of();
        for (int i = 0; i < 100; i++) require(MobEffects.sample(mob, mob.getRandom()) == null, "removed or hidden effects stop emitting");

        for (Quaternionf rotation : List.of(new Quaternionf(), new Quaternionf().rotationXYZ(.8F, -.4F, .3F))) {
            Quaternionf yaw = new Quaternionf().rotationY(1.2F);
            Quaternionf camera = new Quaternionf(rotation).mul(yaw).rotateX(-.5F);
            Matrix4f terrain = new Matrix4f().translation(100, -40, 250).rotate(rotation).scale(32);
            Quaternionf savedCamera = new Quaternionf(camera);
            Matrix4f savedTerrain = new Matrix4f(terrain);
            Quaternionf actual = MobEffects.flameRotation(terrain, camera);
            require(Math.abs(actual.dot(yaw)) > .99999F, "flames face the camera around the scenery's vertical, including tilted views");
            require(camera.equals(savedCamera) && terrain.equals(savedTerrain), "flames leave the shared camera and terrain transforms intact");
        }
    }

    private static Method shadowMethod(String name, Class<?>... parameters) throws Exception {
        Method method = MobShadows.class.getDeclaredMethod(name, parameters); method.setAccessible(true); return method;
    }

    private static final class ShadowVertices implements VertexConsumer {
        final List<Vector3f> positions = new ArrayList<>();
        final List<float[]> uv = new ArrayList<>();
        final List<Integer> alphas = new ArrayList<>();
        public VertexConsumer addVertex(float x, float y, float z) { positions.add(new Vector3f(x, y, z)); return this; }
        public VertexConsumer setColor(int r, int g, int b, int alpha) { alphas.add(alpha); return this; }
        public VertexConsumer setUv(float u, float v) { uv.add(new float[]{u, v}); return this; }
        public VertexConsumer setUv1(int u, int v) { return this; }
        public VertexConsumer setUv2(int u, int v) { return this; }
        public VertexConsumer setNormal(float x, float y, float z) { return this; }
    }

    private static void shadows() throws Exception {
        Method camera = shadowMethod("camera", Matrix4f.class);
        Method strength = shadowMethod("strength", double.class, float.class);
        Method darken = shadowMethod("skyDarken", float.class, float.class, float.class);
        Method alpha = shadowMethod("alpha", float.class, double.class, float.class);
        Method light = shadowMethod("light", BlockAndTintGetter.class, BlockPos.class, int.class);
        Method quad = shadowMethod("quad", PoseStack.Pose.class, VertexConsumer.class, BlockPos.class, AABB.class,
                double.class, double.class, double.class, float.class, int.class);

        Vector3f eye = new Vector3f(40, 45, 40);
        for (Quaternionf rotation : List.of(new Quaternionf(), new Quaternionf().rotationXYZ(.8F, -.4F, .3F))) {
            Matrix4f terrain = new Matrix4f().rotate(rotation).scale(32).translate(-eye.x, -eye.y, -eye.z);
            Matrix4f saved = new Matrix4f(terrain);
            Vector3f actual = (Vector3f) camera.invoke(null, terrain);
            require(actual.distance(eye) < .0001F && terrain.equals(saved), "shadow camera stays in captured coordinates despite giant scale and tilt");
            double distance = actual.distanceSquared(new Vector3f(48, 45, 40));
            require(Math.abs((float) strength.invoke(null, distance, .8F) - .6F) < .0001F,
                    "a mob eight captured blocks away retains its shadow, even 256 rendered blocks away");
        }
        require((float) strength.invoke(null, 256D, 1F) == 0 && (float) strength.invoke(null, 400D, 1F) == 0, "shadows fade out at vanilla's sixteen-block distance");
        require((int) darken.invoke(null, 0F, 0F, 0F) == 0 && (int) darken.invoke(null, .5F, 0F, 0F) == 11, "source day and night control shadow lighting");
        require((int) darken.invoke(null, 0F, 1F, 1F) > 0, "source storms darken shadows");
        BlockAndTintGetter capturedLight = (BlockAndTintGetter) Proxy.newProxyInstance(BlockAndTintGetter.class.getClassLoader(),
                new Class<?>[]{BlockAndTintGetter.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("getBrightness")) return arguments[0] == LightLayer.SKY ? 15 : 8;
                    throw new AssertionError("Shadow lighting must read the captured light layers: " + method.getName());
                });
        require((int) light.invoke(null, capturedLight, BlockPos.ZERO, 0) == 15
                && (int) light.invoke(null, capturedLight, BlockPos.ZERO, 11) == 8, "block light supports shadows after the captured sky darkens");
        require((int) alpha.invoke(null, 1F, 0D, 1F) == 127 && (int) alpha.invoke(null, 1F, 2D, 1F) == 0,
                "shadows soften with height instead of floating below airborne mobs");
        require((int) alpha.invoke(null, 1F, 0D, .1F) < 20, "dark terrain receives faint shadows");

        PoseStack pose = new PoseStack();
        pose.translate(10, 20, 30); pose.mulPose(new Quaternionf().rotationZ(Mth.HALF_PI)); pose.scale(32, 32, 32);
        pose.translate(2.5, 3, 4.5); // The model has finished rendering; there is no renderer-specific offset.
        Matrix4f saved = new Matrix4f(pose.last().pose());
        ShadowVertices vertices = new ShadowVertices();
        quad.invoke(null, pose.last(), vertices, new BlockPos(2, 3, 4), new AABB(0, 0, 0, 1, 1, 1), 2.5, 3D, 4.5, .5F, 127);
        List<Vector3f> expected = List.of(new Vector3f(-86, 84, 158), new Vector3f(-86, 84, 190),
                new Vector3f(-86, 116, 190), new Vector3f(-86, 116, 158));
        require(vertices.positions.size() == 4 && pose.last().pose().equals(saved), "shadow quad leaves the entity pose intact");
        for (int i = 0; i < 4; i++) {
            require(vertices.positions.get(i).distance(expected.get(i)) < .0001F, "shadow lies on the captured floor after scale and rotation");
            require(vertices.alphas.get(i) == 127, "shadow opacity reaches every vertex");
        }
        require(Arrays.equals(vertices.uv.get(0), new float[]{1, 1}) && Arrays.equals(vertices.uv.get(2), new float[]{0, 0}), "shadow texture stays centered on the mob");
    }

    private static void camera() {
        // No GL context is needed: the actual RenderSystem model-view stack and its shader matrix are CPU state.
        RenderSystem.initRenderThread();
        var stack = RenderSystem.getModelViewStack();
        Matrix4f initial = new Matrix4f(stack);
        Matrix4f placement = new Matrix4f().translation(-80, -120, 64).rotateZ(.3F).scale(32).translate(-4, -4, -4);
        Vector3f source = new Vector3f(2, 4, 10);
        try {
            for (Matrix4f previous : List.of(new Matrix4f(), new Matrix4f().rotationXYZ(.2F, .4F, -.1F).translate(1, 2, 3))) {
                stack.set(previous);
                RenderSystem.applyModelViewMatrix();
                for (Matrix4f view : List.of(new Matrix4f(), new Matrix4f().rotationX(.7F).rotateY(-1.2F),
                        new Matrix4f().rotationY(Mth.PI))) {
                    Matrix4f savedView = new Matrix4f(view);
                    Vector3f terrainVertex = new Matrix4f(view).mul(placement).transformPosition(new Vector3f(source));
                    GiantEntities.withCamera(view, () -> {
                        Vector3f mobVertex = new Matrix4f(RenderSystem.getModelViewMatrix()).mul(placement)
                                .transformPosition(new Vector3f(source));
                        require(mobVertex.distance(terrainVertex) < .0001F, "mob and terrain agree after camera yaw, pitch and tilted scenery placement");
                        require(RenderSystem.getModelViewMatrix().equals(view), "camera rotation is applied exactly once, regardless of the previous render pass");
                    });
                    require(stack.equals(previous) && RenderSystem.getModelViewMatrix().equals(previous) && view.equals(savedView),
                            "entity pass restores the stack and shader matrix without modifying the event view");
                }
                RuntimeException failedRenderer = new RuntimeException("renderer fixture");
                try {
                    GiantEntities.withCamera(new Matrix4f().rotationY(1.2F), () -> { throw failedRenderer; });
                    throw new AssertionError("renderer exception must propagate");
                } catch (RuntimeException failure) {
                    require(failure == failedRenderer, "camera scope preserves the original render failure");
                }
                require(stack.equals(previous) && RenderSystem.getModelViewMatrix().equals(previous),
                        "failed rendering or buffer flush cannot leak the mob camera into water or subsequent passes");
            }
        } finally {
            stack.set(initial);
            RenderSystem.applyModelViewMatrix();
        }
    }

    public static void main(String[] args) throws Exception {
        server(); client(); aquatic(); effects(); shadows(); camera();
        System.out.println("PASS: production capture, independent observers, world isolation, stationary readiness, delayed removals, mount links, hurt playback, aquatic poses, potion particles, flame orientation, scenery shadows and camera alignment");
    }
}

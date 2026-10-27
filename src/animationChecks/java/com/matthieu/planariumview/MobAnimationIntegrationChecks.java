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
import com.matthieu.planariumview.client.scenery.entities.AquaticEffects;
import com.matthieu.planariumview.client.scenery.entities.OutsideWings;
import com.matthieu.planariumview.client.scenery.SceneryAmbientParticles;
import com.matthieu.planariumview.client.scenery.ClientView;
import com.matthieu.planariumview.client.scenery.SceneryParticles;
import com.matthieu.planariumview.client.scenery.SceneryWaterParticles;
import com.matthieu.planariumview.client.scenery.LitRenderingWorld;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleRenderType;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.Tesselator;
import net.minecraft.client.renderer.texture.TextureManager;
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
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.network.protocol.game.*;
import net.minecraft.resources.*;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Mth;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.animal.Squid;
import net.minecraft.world.entity.animal.Dolphin;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Parrot;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.level.biome.AmbientParticleSettings;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.StateHolder;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.WaterFluid;
import it.unimi.dsi.fastutil.objects.Reference2ObjectArrayMap;
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
        VisualSquid squid = fixture(VisualSquid.class);
        squid.setId(71); field(Entity.class, "level").set(squid, world);
        OutsideMobsServer.captureInk(squid);
        events = firstQueue.drain();
        require(events.size() == 1 && secondQueue.drain().equals(events)
                && events.getFirst().kind() == MobAnimations.Kind.INK && events.getFirst().tick() == 100,
                "actual ink emission reaches both observers at the source tick");
        squid.setId(99); OutsideMobsServer.captureInk(squid);
        require(firstQueue.drain().isEmpty(), "untracked squid cannot emit scenery ink");
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

    public static class VisualDolphin extends Dolphin {
        boolean wet, noAi;
        public VisualDolphin() { super(null, null); }
        @Override public boolean isInWater() { return wet; }
        @Override public boolean isNoAi() { return noAi; }
    }

    private record Emission(ParticleOptions type, Vec3 at, Vec3 velocity) {}

    private static void aquaticParticles() throws Exception {
        // Particle identifiers only; no registries, providers or graphics context are needed by the sampler.
        Class.forName(ParticleTypes.class.getName());
        for (String name : List.of("DOLPHIN", "GLOW", "SQUID_INK", "GLOW_SQUID_INK", "UNDERWATER")) {
            Field target = field(ParticleTypes.class, name);
            UNSAFE.putObject(UNSAFE.staticFieldBase(target), UNSAFE.staticFieldOffset(target), new SimpleParticleType(false));
        }
        List<Emission> emitted = new ArrayList<>();
        AquaticEffects.Sink sink = (type, x, y, z, vx, vy, vz) -> emitted.add(new Emission(type, new Vec3(x, y, z), new Vec3(vx, vy, vz)));
        VisualDolphin dolphin = fixture(VisualDolphin.class);
        field(Entity.class, "position").set(dolphin, new Vec3(10, 20, 30));
        dolphin.setDeltaMovement(.2, 0, 0); dolphin.wet = true;
        for (float yaw : new float[]{0, 90, 180}) {
            emitted.clear();
            dolphin.setYRot(yaw + 90); // Rendering may have left a different orientation in the copy.
            AquaticEffects.sample(dolphin, yaw, 0, RandomSource.create(1), sink);
            require(emitted.size() == 4 && emitted.stream().allMatch(e -> e.type() == ParticleTypes.DOLPHIN
                    && e.velocity().equals(Vec3.ZERO)), "moving dolphin emits four native wake particles");
            Vec3 center = emitted.get(0).at().add(emitted.get(1).at()).scale(.5);
            Vec3 behind = center.subtract(dolphin.position());
            require(behind.dot(Vec3.directionFromRotation(0, yaw)) < -.49
                    && Math.abs(emitted.get(0).at().distanceTo(emitted.get(1).at()) - .6) < .0001,
                    "wake stays behind the delayed pose, on both sides, independent of stale render fields");
        }
        emitted.clear(); dolphin.wet = false;
        AquaticEffects.sample(dolphin, 0, 0, RandomSource.create(1), sink);
        dolphin.wet = true; dolphin.noAi = true;
        AquaticEffects.sample(dolphin, 0, 0, RandomSource.create(1), sink);
        dolphin.noAi = false; dolphin.setDeltaMovement(.01, 0, 0);
        AquaticEffects.sample(dolphin, 0, 0, RandomSource.create(1), sink);
        require(emitted.isEmpty(), "dry, stationary and no-AI dolphins leave no wake");

        for (Squid squid : List.of(fixture(VisualSquid.class), fixture(GlowSquid.class))) {
            field(Entity.class, "position").set(squid, new Vec3(10, 20, 30));
            field(Entity.class, "dimensions").set(squid, EntityDimensions.scalable(.8F, .8F));
            emitted.clear();
            AquaticEffects.sample(squid, 0, 0, RandomSource.create(2), sink);
            if (squid instanceof GlowSquid) {
                require(emitted.size() == 1 && emitted.getFirst().type() == ParticleTypes.GLOW
                        && emitted.getFirst().at().y >= 20 && emitted.getFirst().at().y <= 20.8,
                        "glow squid emits its native sparkle within its body");
            } else require(emitted.isEmpty(), "ordinary squid does not invent a constant bubble trail");
            emitted.clear(); squid.xBodyRot = 90;
            AquaticEffects.ink(squid, 90, RandomSource.create(3), sink);
            ParticleOptions expected = squid instanceof GlowSquid ? ParticleTypes.GLOW_SQUID_INK : ParticleTypes.SQUID_INK;
            Vec3 origin = new Vec3(9, 20.5, 30);
            require(emitted.size() == 30 && emitted.stream().allMatch(e -> e.type() == expected
                    && e.at().distanceTo(origin) < .0001 && e.velocity().x < -.029 && e.velocity().x > -.231),
                    "ink uses the correct type, tilted mouth and server particle speed");
        }
    }

    private static final class ProbeParticle extends Particle {
        int ticks, renders, begins;
        final ParticleRenderType type = new ParticleRenderType() {
            public BufferBuilder begin(Tesselator tessellator, TextureManager textures) { begins++; return null; }
            public boolean isTranslucent() { return false; }
        };
        ProbeParticle(ClientLevel level) { super(level, 1, 2, 3); }
        @Override public void tick() { ticks++; setPos(x + 1, y, z); }
        @Override public void render(VertexConsumer buffer, Camera camera, float partialTick) { renders++; }
        @Override public ParticleRenderType getRenderType() { return type; }
    }

    public static class CapturedWater extends LitRenderingWorld {
        BlockPos fluidQuery, blockQuery;
        FluidState water;
        BlockState block;
        public CapturedWater() { super(null, List.of()); }
        @Override public FluidState getFluidState(BlockPos pos) { fluidQuery = pos.immutable(); return water; }
        @Override public BlockState getBlockState(BlockPos pos) { blockQuery = pos.immutable(); return block; }
    }

    @SuppressWarnings("unchecked") private static void capturedWater() throws Exception {
        CapturedWater world = fixture(CapturedWater.class);
        world.water = fixture(FluidState.class); world.block = fixture(BlockState.class);
        Map<Particle, SceneryParticles.Space> spaces = (Map<Particle, SceneryParticles.Space>) field(SceneryParticles.class, "SPACE").get(null);
        ProbeParticle particle = new ProbeParticle(fixture(ClientLevel.class));
        try {
            for (double scale : new double[]{32, 1D / 32}) {
                spaces.put(particle, new SceneryParticles.Space(world, scale, 1024, 900, -500, null, true, null));
                particle.setPos(1024 + 2.25 * scale, 900 + 70.5 * scale, -500 - 3.75 * scale);
                require(SceneryParticles.fluidAt(particle) == world.water && SceneryParticles.blockAt(particle) == world.block
                        && world.fluidQuery.equals(new BlockPos(2, 70, -4)) && world.blockQuery.equals(world.fluidQuery),
                        "bubbles and ink query the captured water, including scale, offsets and negative positions");
                require(SceneryParticles.motionScale(particle) == (float) scale,
                        "particle acceleration uses the same scale as its position and initial velocity");
            }
        } finally { spaces.remove(particle); }
    }

    private static void waterParticleLifecycle() throws Exception {
        Method supports = SceneryWaterParticles.class.getDeclaredMethod("supports", ParticleOptions.class); supports.setAccessible(true);
        require((boolean) supports.invoke(null, ParticleTypes.UNDERWATER)
                && !(boolean) supports.invoke(null, new SimpleParticleType(false)),
                "underwater ambience renders before the water surface, without moving unrelated particles to that pass");
        config(Config.SCENERY_PARTICLES, true);
        Minecraft.getInstance().level = fixture(ClientLevel.class);
        Method wrap = SceneryWaterParticles.class.getDeclaredMethod("wrap", Particle.class); wrap.setAccessible(true);
        Method clear = SceneryWaterParticles.class.getDeclaredMethod("clear"); clear.setAccessible(true);
        Field drawing = field(SceneryWaterParticles.class, "drawing");
        ProbeParticle inner = new ProbeParticle(Minecraft.getInstance().level);
        Particle particle = (Particle) wrap.invoke(null, inner);
        try {
            particle.tick();
            require(inner.ticks == 1 && particle.getBoundingBox().equals(inner.getBoundingBox()),
                    "early rendering wrapper ticks once and keeps the engine's culling bounds current");
            ParticleRenderType sheet = particle.getRenderType();
            sheet.begin(null, null);
            require(inner.begins == 0 && !sheet.isTranslucent(), "normal passes skip the early particles and retain their native sheet kind");
            drawing.setBoolean(null, true); sheet.begin(null, null);
            require(inner.begins == 1, "before-water pass delegates the original shader and blending setup");
            particle.render(null, null, 0);
            require(inner.renders == 1, "before-water rendering delegates once");
            clear.invoke(null); particle.render(null, null, 0); particle.tick();
            require(inner.renders == 1 && inner.ticks == 1 && !inner.isAlive() && !particle.isAlive(),
                    "leaving the scenery immediately stops drawing and releases the old particles");
            ProbeParticle next = new ProbeParticle(Minecraft.getInstance().level);
            Particle nextWrapper = (Particle) wrap.invoke(null, next);
            config(Config.SCENERY_PARTICLES, false); nextWrapper.tick();
            require(!nextWrapper.isAlive() && !next.isAlive(), "disabling scenery particles removes both wrapper and contents");
            require(SceneryParticles.fluidAt(next) == null && SceneryParticles.blockAt(next) == null
                    && SceneryParticles.motionScale(next) == 1, "ordinary particles keep their native world and movement");
        } finally {
            drawing.setBoolean(null, false); clear.invoke(null); Minecraft.getInstance().level = null;
            config(Config.SCENERY_PARTICLES, false);
        }
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

    private static void modelLighting() {
        for (float scale : new float[]{32, 1F / 32}) {
            for (Quaternionf rotation : List.of(new Quaternionf(), new Quaternionf().rotationXYZ(.8F, -.4F, .3F))) {
                PoseStack frame = new PoseStack();
                frame.translate(100, 80, -40); frame.mulPose(rotation); frame.scale(scale, scale, scale);
                Matrix4f saved = new Matrix4f(frame.last().pose());
                PoseStack copy = GiantEntities.copyPose(frame.last());
                require(copy.last().pose().equals(saved), "lighting fix preserves the scaled vertex positions");
                for (Vector3f normal : List.of(new Vector3f(1, 0, 0), new Vector3f(0, 1, 0), new Vector3f(0, 0, 1))) {
                    // GeckoLib reads normal() directly; vanilla uses transformNormal() and normalizes it.
                    Vector3f gecko = copy.last().normal().transform(new Vector3f(normal));
                    Vector3f vanilla = copy.last().transformNormal(normal, new Vector3f());
                    Vector3f expected = new Vector3f(normal).rotate(rotation);
                    require(Math.abs(gecko.length() - 1) < .00001 && gecko.distance(expected) < .00001
                            && gecko.distance(vanilla) < .00001,
                            "Ars/GeckoLib and vanilla receive equal unit lighting normals at giant and miniature scales");
                }
                copy.translate(5, 6, 7); copy.mulPose(new Quaternionf().rotationY(1));
                require(frame.last().pose().equals(saved), "one renderer cannot change the next entity's placement or lighting");
            }
        }
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

    public static class AmbientWater extends WaterFluid.Source {
        boolean source;
        int nativeCalls;
        @Override public boolean isSource(FluidState state) { return source; }
        // Simulate a mod such as Sable suppressing emissions in a snapshot world.
        @Override public void animateTick(Level level, BlockPos at, FluidState state, RandomSource random) { nativeCalls++; }
    }

    public static class AmbientBlock extends Block {
        public AmbientBlock() { super(null); }
        @Override public boolean isAir(BlockState state) { return false; }
    }

    private static void underwaterEmission() throws Exception {
        // Skip the renderer bootstrap, but exercise the complete production animateBlock -> particle sink path.
        Class.forName(ClientView.class.getName());
        Class<?> ambience = Class.forName("com.matthieu.planariumview.client.scenery.SceneryAmbience");
        for (var entry : Map.of("AMBIENT_RANDOM", RandomSource.create(827), "AMBIENT_BROKEN", new HashSet<>(),
                "LOGGER", com.mojang.logging.LogUtils.getLogger()).entrySet()) {
            Field target = field(ambience, entry.getKey());
            UNSAFE.putObject(UNSAFE.staticFieldBase(target), UNSAFE.staticFieldOffset(target), entry.getValue());
        }
        AmbientWater source = fixture(AmbientWater.class), flowing = fixture(AmbientWater.class), other = fixture(AmbientWater.class);
        source.source = true; other.source = true;
        Class.forName(Fluids.class.getName());
        for (var entry : Map.of("WATER", source, "FLOWING_WATER", flowing).entrySet()) {
            Field target = field(Fluids.class, entry.getKey());
            UNSAFE.putObject(UNSAFE.staticFieldBase(target), UNSAFE.staticFieldOffset(target), entry.getValue());
        }
        LitRenderingWorld world = fixture(LitRenderingWorld.class);
        BlockState block = fixture(BlockState.class);
        field(StateHolder.class, "owner").set(block, fixture(AmbientBlock.class));
        BlockPos at = new BlockPos(12, 8, -4);
        List<Emission> emitted = new ArrayList<>();
        for (AmbientWater water : List.of(source, flowing, other)) {
            for (boolean falling : new boolean[]{false, true}) {
                var properties = new Reference2ObjectArrayMap<Property<?>, Comparable<?>>();
                properties.put(FlowingFluid.FALLING, falling);
                field(BlockBehaviour.BlockStateBase.class, "fluidState").set(block, new FluidState(water, properties, null));
                emitted.clear(); water.nativeCalls = 0;
                ((RandomSource) field(ambience, "AMBIENT_RANDOM").get(null)).setSeed(827);
                LitRenderingWorld.withSceneryParticles((level, options, x, y, z, vx, vy, vz) ->
                        emitted.add(new Emission(options, new Vec3(x, y, z), new Vec3(vx, vy, vz))), () -> {
                    for (int i = 0; i < 10_000; i++) ClientView.animateBlock(world, at, block);
                });
                if (water != other && (water.source || falling)) {
                    require(water.nativeCalls == 0 && Math.abs(emitted.size() - 1000) < 160,
                            "source and falling water emit underwater ambience at the native density even when a mod suppresses native emission");
                    for (Emission particle : emitted) require(particle.type == ParticleTypes.UNDERWATER
                                    && BlockPos.containing(particle.at).equals(at) && particle.velocity.equals(Vec3.ZERO),
                            "underwater ambience starts motionless inside the sampled water block");
                } else {
                    require(water.nativeCalls == 10_000 && emitted.isEmpty(),
                            "horizontal flowing water retains native sounds and modded fluids retain native animation without duplicate emissions");
                }
            }
        }
    }

    private static void biomeParticles() {
        BlockPos at = new BlockPos(-15, 84, 11);
        // The four native Nether densities, plus an arbitrary modded biome setting: no hard-coded biome whitelist.
        for (float probability : new float[]{.00625F, .118093334F, .025F, .01428F, .45F}) {
            ParticleOptions type = new SimpleParticleType(false);
            AmbientParticleSettings settings = new AmbientParticleSettings(type, probability);
            RandomSource random = RandomSource.create(827);
            int count = 0, samples = 100_000;
            for (int i = 0; i < samples; i++) {
                var particle = SceneryAmbientParticles.sample(settings, at, random);
                if (particle == null) continue;
                count++;
                require(particle.options() == type && BlockPos.containing(particle.x(), particle.y(), particle.z()).equals(at),
                        "ambient emission retains the source biome's particle options and sampled block, including negative coordinates");
            }
            double expected = samples * probability, deviation = Math.sqrt(expected * (1 - probability));
            require(Math.abs(count - expected) < 5 * deviation, "biome ambience retains its native density");
        }
        AmbientParticleSettings quiet = new AmbientParticleSettings(new SimpleParticleType(false), 0);
        RandomSource random = RandomSource.create(927);
        for (int i = 0; i < 1000; i++) require(SceneryAmbientParticles.sample(quiet, at, random) == null,
                "biomes with disabled ambient particles remain quiet");
    }

    @SuppressWarnings("unchecked") private static void particleBudget() throws Exception {
        Map<Particle, Integer> lights = (Map<Particle, Integer>) field(SceneryParticles.class, "LIGHT").get(null);
        Map<Particle, Boolean> wrapped = (Map<Particle, Boolean>) field(SceneryParticles.class, "WRAPPED").get(null);
        Method capacity = SceneryParticles.class.getDeclaredMethod("hasWrappedCapacity"); capacity.setAccessible(true);
        List<ProbeParticle> particles = new ArrayList<>();
        ClientLevel level = fixture(ClientLevel.class);
        try {
            for (int i = 0; i < 4096; i++) {
                ProbeParticle particle = new ProbeParticle(level);
                particles.add(particle); lights.put(particle, 0x00F000F0);
            }
            require((boolean) capacity.invoke(null), "dense biome ambience cannot exhaust the budget for mob and underwater effects");
            for (int i = 0; i < 2048; i++) wrapped.put(particles.get(i), true);
            require(!(boolean) capacity.invoke(null), "wrapped particles retain their own safety limit");
            particles.getFirst().remove(); SceneryParticles.tick();
            require((boolean) capacity.invoke(null) && !wrapped.containsKey(particles.getFirst()),
                    "expired effects release their slots on the next tick without waiting for garbage collection");
        } finally {
            for (Particle particle : particles) { lights.remove(particle); wrapped.remove(particle); }
        }
    }

    public static class VisualBat extends Bat {
        boolean resting;
        public VisualBat() { super(null, null); }
        @Override public boolean isResting() { return resting; }
    }

    private static void wings() throws Exception {
        Chicken chicken = fixture(Chicken.class);
        Parrot parrot = fixture(Parrot.class);
        Vec3 velocity = new Vec3(.2, -.3, .1), position = new Vec3(4, 70, -2);
        for (Entity bird : List.of(chicken, parrot)) {
            bird.setDeltaMovement(velocity);
            field(Entity.class, "position").set(bird, position);
        }
        OutsideWings chickenWings = new OutsideWings(), parrotWings = new OutsideWings();
        for (int tick = 0; tick < 20; tick++) {
            chickenWings.tick(chicken); parrotWings.tick(parrot);
            require(chicken.flap > chicken.oFlap && parrot.flap > parrot.oFlap
                    && chicken.flapSpeed == 1 && parrot.flapSpeed == 1,
                    "airborne birds animate every client tick and retain the previous wing pose for interpolation");
        }
        require(chicken.getDeltaMovement().equals(velocity) && parrot.getDeltaMovement().equals(velocity)
                && chicken.position().equals(position) && parrot.position().equals(position),
                "visual wing updates preserve captured movement and do not apply vanilla falling physics");
        field(Entity.class, "onGround").setBoolean(chicken, true);
        field(Entity.class, "vehicle").set(parrot, chicken);
        for (int tick = 0; tick < 4; tick++) { chickenWings.tick(chicken); parrotWings.tick(parrot); }
        require(chicken.flapSpeed == 0 && parrot.flapSpeed == 0,
                "landing and perching gradually stop wing movement, including an airborne passenger");
        field(Entity.class, "onGround").setBoolean(chicken, false);
        field(Entity.class, "vehicle").set(parrot, null);
        chickenWings.tick(chicken); parrotWings.tick(parrot);
        require(chicken.flapSpeed == 1 && parrot.flapSpeed == 1, "taking off resumes flapping immediately");

        VisualBat bat = fixture(VisualBat.class);
        field(Bat.class, "flyAnimationState").set(bat, new AnimationState());
        field(Bat.class, "restAnimationState").set(bat, new AnimationState());
        OutsideWings batWings = new OutsideWings();
        bat.tickCount = 20; batWings.tick(bat);
        bat.tickCount = 26; batWings.tick(bat); bat.flyAnimationState.updateTime(26, 1);
        require(bat.flyAnimationState.isStarted() && !bat.restAnimationState.isStarted()
                && bat.flyAnimationState.getAccumulatedTime() == 300,
                "bat flight starts once and keeps progressing instead of restarting on each update");
        bat.resting = true; bat.tickCount++; batWings.tick(bat);
        require(!bat.flyAnimationState.isStarted() && bat.restAnimationState.isStarted(), "resting bat stops its wings");
        bat.resting = false; bat.tickCount++; batWings.tick(bat);
        require(bat.flyAnimationState.isStarted() && !bat.restAnimationState.isStarted()
                && bat.flyAnimationState.getAccumulatedTime() == 0, "bat starts a new flight after leaving its perch");
    }

    public static void main(String[] args) throws Exception {
        server(); client(); aquatic(); effects(); aquaticParticles(); underwaterEmission(); biomeParticles(); particleBudget(); wings(); waterParticleLifecycle(); capturedWater(); shadows(); modelLighting(); camera();
        System.out.println("PASS: production capture, independent observers, world isolation, stationary readiness, delayed removals, mount links, hurt playback, aquatic poses/particles, ink, biome ambience, wings, captured water, particle lifecycle, potion particles, flame orientation, scenery shadows, scaled model lighting and camera alignment");
    }
}

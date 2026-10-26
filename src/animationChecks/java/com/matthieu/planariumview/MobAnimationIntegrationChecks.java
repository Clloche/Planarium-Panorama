package com.matthieu.planariumview;

import com.matthieu.planariumview.network.entities.MobAnimations;
import com.matthieu.planariumview.network.entities.OutsideMobsPayload;
import com.matthieu.planariumview.server.entities.MobEventQueue;
import com.matthieu.planariumview.server.entities.OutsideMobsServer;
import com.google.common.collect.ImmutableList;
import com.matthieu.planariumview.client.scenery.entities.OutsideMobs;
import com.matthieu.planariumview.client.scenery.entities.MobEffects;
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
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.common.ModConfigSpec;
import sun.misc.Unsafe;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
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

    public static class VisualMob extends LivingEntity implements SceneryEntityAccessor, MobEffects.PotionParticles {
        float health;
        int hurtCalls;
        List<ParticleOptions> effects;
        boolean ambient, invisible;
        public VisualMob() { super(null, null); }
        public List<ParticleOptions> planariumview$effectParticles() { return effects; }
        public boolean planariumview$ambientEffects() { return ambient; }
        @Override public boolean isInvisible() { return invisible; }
        @Override public float getHealth() { return health; }
        @Override public void setHealth(float value) { health = value; }
        @Override public void calculateEntityAnimation(boolean flying) {}
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

    public static void main(String[] args) throws Exception {
        server(); client(); effects();
        System.out.println("PASS: production capture, independent observers, world isolation, stationary readiness, delayed removals, mount links, hurt playback, potion particles and flame orientation");
    }
}

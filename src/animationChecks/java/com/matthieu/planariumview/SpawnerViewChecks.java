package com.matthieu.planariumview;

import com.matthieu.planariumview.client.scenery.*;
import com.matthieu.planariumview.mixin.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.*;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.TrialSpawnerBlock;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.entity.trialspawner.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.Vec3;
import java.lang.reflect.*;
import java.util.*;
import sun.misc.Unsafe;

/** Production visual tick and native trial client handler; accessors and copied world are fixture boundaries. */
public final class SpawnerViewChecks {
    private static int assertions;
    private static Field field(Class<?> type, String name) throws Exception {
        Field f = type.getDeclaredField(name); f.setAccessible(true); return f;
    }
    private static <T> T fixture(Class<T> type) throws Exception {
        Method allocate = MobAnimationIntegrationChecks.class.getDeclaredMethod("fixture", Class.class);
        allocate.setAccessible(true); return type.cast(allocate.invoke(null, type));
    }
    private static void require(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    private static void staticValue(Class<?> type, String name, Object value) throws Exception {
        Unsafe unsafe = (Unsafe) field(Unsafe.class, "theUnsafe").get(null);
        Class.forName(type.getName()); Field f = field(type, name);
        unsafe.putObject(unsafe.staticFieldBase(f), unsafe.staticFieldOffset(f), value);
    }
    public static class World extends LitRenderingWorld {
        RandomSource random;
        long time;
        public World() { super(null, List.of()); }
        @Override public RandomSource getRandom() { return random; }
        @Override public long getGameTime() { return time; }
        @Override public void playLocalSound(double x, double y, double z, net.minecraft.sounds.SoundEvent sound,
                net.minecraft.sounds.SoundSource source, float volume, float pitch, boolean delay) {}
    }
    public static class Classic extends BaseSpawner implements BaseSpawnerAccessor {
        Entity display;
        public void broadcastEvent(Level level, BlockPos pos, int event) { throw new AssertionError("visual tick must not activate a spawner"); }
        @Override public Entity getOrCreateDisplayEntity(Level level, BlockPos pos) { return display; }
        public double planariumview$getSpin() { return getSpin(); }
        public double planariumview$getOSpin() { return getoSpin(); }
        public void planariumview$setSpin(double value) { set("spin", value); }
        public void planariumview$setOSpin(double value) { set("oSpin", value); }
        public int planariumview$getSpawnDelay() { return (int) get("spawnDelay"); }
        public void planariumview$setSpawnDelay(int value) { set("spawnDelay", value); }
        public int planariumview$getRequiredPlayerRange() { return (int) get("requiredPlayerRange"); }
        private Object get(String name) {
            try { return field(BaseSpawner.class, name).get(this); } catch (Exception e) { throw new RuntimeException(e); }
        }
        private void set(String name, Object value) {
            try { field(BaseSpawner.class, name).set(this, value); } catch (Exception e) { throw new RuntimeException(e); }
        }
    }
    public static class TrialData extends TrialSpawnerData implements TrialSpawnerDataAccessor {
        public double planariumview$getSpin() { return spin; }
        public void planariumview$setSpin(double value) { spin = value; }
        public void planariumview$setOSpin(double value) { oSpin = value; }
    }
    private static SpawnerBlockEntity classic(BlockPos pos) throws Exception {
        Classic base = fixture(Classic.class);
        base.set("requiredPlayerRange", 16); base.planariumview$setSpawnDelay(100);
        Method make = MobAnimationIntegrationChecks.class.getDeclaredMethod("mob", int.class); make.setAccessible(true);
        base.display = (Entity) make.invoke(null, 8200);
        SpawnerBlockEntity copy = fixture(SpawnerBlockEntity.class);
        field(SpawnerBlockEntity.class, "spawner").set(copy, base);
        field(BlockEntity.class, "worldPosition").set(copy, pos);
        return copy;
    }
    private static List<ParticleOptions> tick(World world, List<BlockEntity> copies, String scope, Vec3 viewer) {
        List<ParticleOptions> emissions = new ArrayList<>();
        LitRenderingWorld.withSceneryParticles((w, options, x, y, z, vx, vy, vz) -> emissions.add(options),
                () -> SpawnerView.tick(world, copies, scope, viewer, p -> true));
        return emissions;
    }
    public static void run() throws Exception {
        // Distinct fixture types let the native particle branches be checked without bootstrapping registries.
        Map<String, Object> oldParticles = new HashMap<>();
        for (String name : List.of("SMOKE", "FLAME", "SOUL_FIRE_FLAME", "SMALL_FLAME")) {
            Class.forName(ParticleTypes.class.getName()); oldParticles.put(name, field(ParticleTypes.class, name).get(null));
            staticValue(ParticleTypes.class, name, fixture(SimpleParticleType.class));
        }
        BooleanProperty ominous = BooleanProperty.create("ominous");
        Class.forName(TrialSpawnerBlock.class.getName()); Object oldOminous = field(TrialSpawnerBlock.class, "OMINOUS").get(null);
        staticValue(TrialSpawnerBlock.class, "OMINOUS", ominous);
        World world = fixture(World.class); world.random = RandomSource.create(17);
        BlockPos pos = new BlockPos(8, 3, 6); Vec3 viewer = pos.getCenter();
        try {
            SpawnerView.clear(); SpawnerBlockEntity copy = classic(pos); Classic base = (Classic) copy.getSpawner();
            List<ParticleOptions> first = tick(world, List.of(copy), "mini:a", viewer);
            require(first.equals(List.of(ParticleTypes.SMOKE, ParticleTypes.FLAME)), "classic emits smoke and flame in copied coordinates");
            require(base.getSpin() > 0 && base.getoSpin() == 0 && base.planariumview$getSpawnDelay() == 99, "visual counters advance once");
            double spin = base.getSpin();
            require(tick(world, List.of(copy), "mini:a", viewer).equals(first) && base.getSpin() == spin,
                    "shared miniature replays identical emissions without advancing rotation twice");
            SpawnerView.beginTick(); tick(world, List.of(copy), "mini:a", viewer);
            require(base.getoSpin() == spin && base.getSpin() > spin, "next tick advances the display entity smoothly");
            SpawnerView.beginTick(); double held = base.getSpin();
            require(tick(world, List.of(copy), "mini:a", viewer.add(17, 0, 0)).isEmpty() && base.getSpin() == held,
                    "outside required player range, rotation and particles stop");
            SpawnerView.beginTick(); base.set("requiredPlayerRange", 4);
            require(tick(world, List.of(copy), "mini:a", viewer.add(5, 0, 0)).isEmpty(), "configured activation range is respected");
            SpawnerView.beginTick(); base.planariumview$setSpawnDelay(0); tick(world, List.of(copy), "mini:a", viewer);
            require(base.planariumview$getSpawnDelay() == 400, "expired visual delay resets without server spawning");
            held = base.getSpin(); SpawnerView.beginTick(); SpawnerBlockEntity replacement = classic(pos);
            tick(world, List.of(replacement), "mini:a", viewer);
            require(replacement.getSpawner().getoSpin() == held, "replacement snapshot resumes the preceding rotation");
            SpawnerView.beginTick(); SpawnerBlockEntity independent = classic(pos); tick(world, List.of(independent), "mini:b", viewer);
            require(independent.getSpawner().getoSpin() == 0, "separate pocket scope does not inherit another rotation");
            SpawnerView.beginTick(); tick(world, List.of(), "mini:a", viewer); SpawnerView.beginTick();
            SpawnerBlockEntity recreated = classic(pos); tick(world, List.of(recreated), "mini:a", viewer);
            require(recreated.getSpawner().getoSpin() == 0, "removed spawner does not leave rotation for a later replacement");
            SpawnerView.beginTick(); SpawnerBlockEntity empty = classic(pos.offset(1, 0, 0)); ((Classic) empty.getSpawner()).display = null;
            require(tick(world, List.of(empty), "mini:empty", viewer).isEmpty(), "empty display cage has no fake activity");

            TrialData data = fixture(TrialData.class); TrialSpawner trial = fixture(TrialSpawner.class);
            TrialSpawnerState[] trialState = {TrialSpawnerState.ACTIVE};
            Object accessor = Proxy.newProxyInstance(SpawnerViewChecks.class.getClassLoader(), new Class<?>[]{TrialSpawner.StateAccessor.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("getState")) return trialState[0];
                        throw new AssertionError("visual tick must not update server trial state");
                    });
            field(TrialSpawner.class, "stateAccessor").set(trial, accessor);
            field(TrialSpawner.class, "data").set(trial, data);
            TrialSpawnerBlockEntity trialCopy = fixture(TrialSpawnerBlockEntity.class);
            field(TrialSpawnerBlockEntity.class, "trialSpawner").set(trialCopy, trial);
            field(BlockEntity.class, "worldPosition").set(trialCopy, pos);
            BlockState state = fixture(BlockState.class);
            var values = new it.unimi.dsi.fastutil.objects.Reference2ObjectArrayMap<net.minecraft.world.level.block.state.properties.Property<?>, Comparable<?>>();
            values.put(ominous, false); field(net.minecraft.world.level.block.state.StateHolder.class, "values").set(state, values);
            field(BlockEntity.class, "blockState").set(trialCopy, state);
            SpawnerView.beginTick();
            require(tick(world, List.of(trialCopy), "mini:trial", viewer).equals(List.of(ParticleTypes.SMOKE, ParticleTypes.FLAME))
                    && data.getSpin() > 0, "native active trial rotates and emits normal flames");
            values.put(ominous, true); SpawnerView.beginTick();
            require(tick(world, List.of(trialCopy), "mini:trial", viewer).equals(List.of(ParticleTypes.SMOKE, ParticleTypes.SOUL_FIRE_FLAME)),
                    "ominous trial uses native soul flames");
            trialState[0] = TrialSpawnerState.INACTIVE; held = data.getSpin(); SpawnerView.beginTick();
            require(tick(world, List.of(trialCopy), "mini:trial", viewer).isEmpty() && data.getSpin() == held,
                    "inactive trial does not invent an active challenge");
        } finally {
            SpawnerView.clear(); staticValue(TrialSpawnerBlock.class, "OMINOUS", oldOminous);
            for (var entry : oldParticles.entrySet()) staticValue(ParticleTypes.class, entry.getKey(), entry.getValue());
        }
        System.out.println("PASS: " + assertions + " classic/trial spawner assertions: range, shared views, snapshots, rotation and ominous particles");
    }
}

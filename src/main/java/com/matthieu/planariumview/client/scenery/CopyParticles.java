package com.matthieu.planariumview.client.scenery;

import com.hollingsworth.arsnouveau.client.renderer.PlanariumRenderingWorld;
import com.hollingsworth.arsnouveau.client.renderer.world.CulledStatePos;
import com.matthieu.planariumview.config.Config;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.joml.Matrix4d;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

/** Client particle emitters belonging to already-rendered copies; no server simulation or chunk tickets. */
public final class CopyParticles {
    private static final Map<LitRenderingWorld, Emitter> EMITTERS = new IdentityHashMap<>();
    private static final RandomSource RANDOM = RandomSource.create();
    private static final Set<Class<?>> FAILED = new HashSet<>();
    private static long ticks;
    private static int cursor;
    private static Constructor<?> emberType;

    private static final class Emitter {
        final LitRenderingWorld world;
        final Object level = Minecraft.getInstance().level;
        final List<CulledStatePos> states;
        final List<BlockPos> campfires;
        final SceneryParticles.Space space;
        final FramedParticle.Frame frame;
        Matrix4d pose = new Matrix4d();
        long seen;
        boolean closed;

        Emitter(LitRenderingWorld world, double scale, List<CulledStatePos> states) {
            this.world = world;
            this.states = states == null ? List.of() : states;
            campfires = ClientView.findCampfires(this.states);
            space = new SceneryParticles.Space(world, scale, FramedParticle.OFFSET, FramedParticle.OFFSET,
                    FramedParticle.OFFSET, null, Config.realLighting(), null);
            frame = new FramedParticle.Frame(() -> pose, () -> !closed && ticks - seen < 10
                    && level == Minecraft.getInstance().level && (this.states.isEmpty() || Config.subLevelViews()), scale);
        }

        void spawn(LitRenderingWorld ignored, ParticleOptions options, double x, double y, double z,
                   double vx, double vy, double vz) {
            if (options == null || !Config.sceneryParticles() || !SceneryParticles.allowed()) return;
            int light = Config.realLighting() ? SceneryParticles.rawLight(world, BlockPos.containing(x, y, z)) : 0x00F000F0;
            SceneryParticles.framed(frame, () -> SceneryParticles.spawn(space, options, x, y, z, vx, vy, vz, light));
        }
    }

    private CopyParticles() {}

    public static void seen(LitRenderingWorld world, Matrix4d pose, double scale, List<CulledStatePos> states) {
        if (!Config.enabled() || !Config.sceneryParticles()) return;
        Emitter emitter = EMITTERS.computeIfAbsent(world, ignored -> new Emitter(world, scale, states));
        emitter.pose = pose;
        emitter.seen = ticks;
    }

    public static void forget(PlanariumRenderingWorld world) {
        Emitter emitter = EMITTERS.remove(world);
        if (emitter != null) emitter.closed = true;
    }

    /** Block-entity ticks can emit through the fake world, independently of sound settings. */
    public static void capture(PlanariumRenderingWorld world, Runnable action) {
        Emitter emitter = EMITTERS.get(world);
        LitRenderingWorld.captureSceneryParticles(emitter == null || !Config.sceneryParticles() ? null : emitter::spawn);
        try { action.run(); }
        finally { LitRenderingWorld.captureSceneryParticles(null); }
    }

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !Config.enabled() || !Config.sceneryParticles()) { clear(); return; }
        if (mc.isPaused()) return;
        ticks++;
        EMITTERS.values().removeIf(emitter -> {
            if (emitter.level == mc.level && ticks - emitter.seen < 10) return false;
            emitter.closed = true;
            return true;
        });
        List<Emitter> active = new ArrayList<>(EMITTERS.values());
        int budget = 667; // Shared across all sections, rather than 667 random ticks per section.
        for (int i = 0; i < active.size(); i++) {
            Emitter emitter = active.get(Math.floorMod(i + cursor, active.size()));
            LitRenderingWorld.captureSceneryParticles(emitter::spawn);
            try {
                double expected = emitter.states.size() * .1;
                int picks = Math.min(budget, (int) expected + (RANDOM.nextDouble() < expected - (int) expected ? 1 : 0));
                budget -= picks;
                for (int pick = 0; pick < picks; pick++) {
                    CulledStatePos at = emitter.states.get(RANDOM.nextInt(emitter.states.size()));
                    ClientView.animateBlock(emitter.world, at.pos, at.state);
                }
                for (BlockPos at : emitter.campfires) ClientView.campfireTick(emitter.world, at, emitter.world.getBlockState(at));
                for (BlockEntity entity : emitter.world.blockEntityMap.values()) {
                    BlockPos at = entity.getBlockPos();
                    if (!emitter.states.isEmpty() && (at.getX() < 0 || at.getX() > 15 || at.getY() < 0
                            || at.getY() > 15 || at.getZ() < 0 || at.getZ() > 15)) continue;
                    if (entity.getClass().getName().endsWith(".HotAirBurnerBlockEntity")) burner(entity, emitter);
                }
            } finally { LitRenderingWorld.captureSceneryParticles(null); }
        }
        cursor++;
    }

    private record Burner(Method signal, Method intensity, Method value, Method output, Field capacity) {
        Burner(Class<?> type) throws ReflectiveOperationException {
            this(type.getMethod("getSignalStrength"), type.getMethod("getClientIntensity"),
                    type.getMethod("getClientIntensity").getReturnType().getMethod("getValue"),
                    type.getMethod("getGasOutput"), field(type, "maxCapacity"));
        }
    }
    private static final ClassValue<Burner> BURNERS = new ClassValue<>() {
        @Override protected Burner computeValue(Class<?> type) {
            try { return new Burner(type); }
            catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        }
    };

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try { Field field = current.getDeclaredField(name); field.setAccessible(true); return field; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }

    /** Aeronautics deliberately skips these client effects on virtual blocks. Keep its native rates and particle types. */
    private static void burner(BlockEntity entity, Emitter emitter) {
        if (FAILED.contains(entity.getClass())) return;
        try {
            Burner hooks = BURNERS.get(entity.getClass());
            float signal = ((Number) hooks.signal.invoke(entity)).floatValue() / 15;
            if (signal <= 0) return;
            float intensity = ((Number) hooks.value.invoke(hooks.intensity.invoke(entity))).floatValue();
            double capacity = ((Number) hooks.capacity.get(entity)).doubleValue();
            double output = ((Number) hooks.output.invoke(entity)).doubleValue();
            double probability = capacity > 0 ? .4 * Math.sqrt(Math.clamp(output / capacity, 0, 1)) : 0;
            BlockPos at = entity.getBlockPos();
            if (RANDOM.nextDouble() < probability) emitter.spawn(emitter.world, ParticleTypes.LARGE_SMOKE,
                    at.getX() + .5 + (RANDOM.nextDouble() - .5) * .4,
                    at.getY() + (RANDOM.nextDouble() + RANDOM.nextDouble()) * .5 + .56,
                    at.getZ() + .5 + (RANDOM.nextDouble() - .5) * .4, 0, signal * signal * .3, 0);
            if (RANDOM.nextDouble() < probability / 5) emitter.spawn(emitter.world, ParticleTypes.LAVA,
                    at.getX() + .5, at.getY() + .5, at.getZ() + .5, RANDOM.nextFloat() / 2, .00005, RANDOM.nextFloat() / 2);
            if (RANDOM.nextFloat() < .5F * intensity) {
                if (emberType == null) emberType = Class.forName("dev.eriksonn.aeronautics.content.particle.HotAirEmberParticleData")
                        .getConstructor(boolean.class);
                boolean soul = entity.getBlockState().getValues().values().stream().anyMatch(value -> value.toString().equalsIgnoreCase("soul_fire"));
                emitter.spawn(emitter.world, (ParticleOptions) emberType.newInstance(soul),
                        at.getX() + .5 + (RANDOM.nextDouble() - .5) * .5, at.getY() + .6,
                        at.getZ() + .5 + (RANDOM.nextDouble() - .5) * .5, intensity, intensity, intensity);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (FAILED.add(entity.getClass())) LogUtils.getLogger().warn("[Planarium Panorama] Burner preview particles unavailable", e);
        }
    }

    public static void clear() {
        EMITTERS.values().forEach(emitter -> emitter.closed = true);
        EMITTERS.clear();
    }
}

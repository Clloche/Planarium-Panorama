package com.matthieu.planariumview.client.sound;

import com.hollingsworth.arsnouveau.client.renderer.PlanariumRenderingWorld;
import com.matthieu.planariumview.client.scenery.LitRenderingWorld;
import com.matthieu.planariumview.client.scenery.SubLevelView;
import com.matthieu.planariumview.compat.CreateCopies;
import com.matthieu.planariumview.config.Config;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Vector3d;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.UnaryOperator;

/** Replays client machine ambience from snapshots, including Create's grouped and looping sounds. */
public final class MachineSounds {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<String, Group> GROUPS = new HashMap<>();
    private static final Map<Object, Loop> LOOPS = new HashMap<>();
    private static final Map<SoundInstance, Native> NATIVE = new IdentityHashMap<>();
    private static final Map<PlanariumRenderingWorld, UnaryOperator<Vec3>> ACTIVE = new IdentityHashMap<>();
    private static final Map<Object, Playback> DESIRED = new HashMap<>();
    private static final Map<String, List<Tone>> RECIPES = new HashMap<>();
    private static final Map<String, SoundEvent> AERO_EVENTS = new HashMap<>();
    private static final Set<Class<?>> FAILED = new HashSet<>();
    private static final net.minecraft.util.RandomSource RANDOM = SoundInstance.createUnseededRandom();
    private static boolean audible, createEnabled = true;
    private static float createCap = 0.2F;
    private static int ticks;
    private static Object level;

    private record Tone(SoundEvent event, float volume, float pitch, int delay) {}
    private record Playback(SoundEvent event, SoundSource category, float volume, float pitch, Vec3 pos) {}
    private record Native(PlanariumRenderingWorld world, SoundInstance sound) {}
    private static final class Group {
        final List<Tone> tones;
        final boolean create;
        final Set<Vec3> sources = new HashSet<>();
        Vec3 sum = Vec3.ZERO;
        float strength;
        Group(List<Tone> tones, boolean create) { this.tones = tones; this.create = create; }
        void add(Vec3 pos, float volume) {
            double range = create ? 16 : 10;
            if (!Double.isFinite(pos.lengthSqr()) || pos.distanceToSqr(new Vec3(0.5, 0.5, 0.5)) >= range * range) return;
            if (sources.add(pos)) { sum = sum.add(pos); strength += volume; }
        }
    }

    private MachineSounds() {}

    public static void tick(LitRenderingWorld scenery, int radius, BlockPos origin) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !Config.enabled()) { clear(); return; }
        if (mc.isPaused()) return;
        if (level != mc.level) { clear(); level = mc.level; }
        ticks++;
        audible = scenery != null && Config.sceneryAmbience();
        GROUPS.clear(); DESIRED.clear(); ACTIVE.clear();
        readCreateOptions();
        CreateCopies.beginTick();
        if (scenery != null) {
            world(scenery, SubLevelView.sourceFrame(origin, radius));
            SubLevelView.soundWorlds(MachineSounds::world);
        }
        CreateCopies.tickVisible();
        for (var entry : GROUPS.entrySet()) {
            Group group = entry.getValue();
            int count = group.sources.size();
            if (count == 0) continue;
            Vec3 at = group.sum.scale(1.0 / count);
            double distance = at.distanceTo(new Vec3(0.5, 0.5, 0.5));
            float volume = group.create
                    ? Mth.clamp(count / 150.0F, 0.025F, createCap) * Math.max(0, 2 - (float) distance / 8)
                    : group.strength / count * Math.max(0, 1 - (float) distance / 10);
            for (int i = 0; i < group.tones.size(); i++) {
                Tone tone = group.tones.get(i);
                float finalVolume = volume * tone.volume * OutsideSoundClient.AMBIENCE_VOLUME;
                if (tone.delay > 0) {
                    if (ticks % tone.delay == 0) OutsideSoundClient.play(tone.event, SoundSource.AMBIENT,
                            finalVolume, tone.pitch, at.x, at.y, at.z, true);
                } else desire(entry.getKey() + "/" + i, tone.event, SoundSource.AMBIENT, finalVolume, tone.pitch, at);
            }
        }
        for (var entry : List.copyOf(NATIVE.entrySet())) {
            Native nativeSound = entry.getValue();
            UnaryOperator<Vec3> space = ACTIVE.get(nativeSound.world);
            SoundInstance sound = nativeSound.sound;
            if (!audible || space == null) { endNative(sound); NATIVE.remove(sound); continue; }
            try {
                if (sound instanceof TickableSoundInstance tickable) {
                    CreateCopies.soundTick(nativeSound.world, space, tickable::tick);
                    if (tickable.isStopped()) { NATIVE.remove(sound); continue; }
                }
                Vec3 at = space.apply(new Vec3(sound.getX(), sound.getY(), sound.getZ()));
                float volume = attenuate(sound.getVolume(), at, 16) * OutsideSoundClient.AMBIENCE_VOLUME;
                desire(sound, SoundEvent.createVariableRangeEvent(sound.getLocation()), sound.getSource(), volume, sound.getPitch(), at);
            } catch (RuntimeException e) { NATIVE.remove(sound); endNative(sound); }
        }
        LOOPS.entrySet().removeIf(entry -> {
            if (DESIRED.containsKey(entry.getKey())) return false;
            entry.getValue().end();
            return true;
        });
        for (var entry : DESIRED.entrySet()) {
            Loop loop = LOOPS.get(entry.getKey());
            if (loop == null || loop.isStopped()) {
                loop = new Loop(entry.getValue());
                LOOPS.put(entry.getKey(), loop);
                mc.getSoundManager().play(loop);
            }
            loop.update(entry.getValue());
        }
    }

    private static void world(LitRenderingWorld world, Matrix4d matrix) {
        UnaryOperator<Vec3> space = pos -> {
            Vector3d transformed = matrix.transformPosition(new Vector3d(pos.x, pos.y, pos.z));
            return new Vec3(transformed.x, transformed.y, transformed.z);
        };
        ACTIVE.put(world, space);
        CreateCopies.tick(world, audible ? space : null);
    }

    /** Receives all groups emitted by tickAudio, including overrides from Create addons. */
    public static void group(Object group, BlockPos pos, float pitch) {
        var context = CreateCopies.context();
        if (!audible || !createEnabled || createCap <= 0 || context == null || context.source() == null || !Float.isFinite(pitch)) return;
        String name = ((Enum<?>) group).name() + "/" + bucket(pitch);
        try {
            List<Tone> tones = RECIPES.get(name);
            if (tones == null) {
                Object scape = group.getClass().getMethod("instantiate", float.class).invoke(group, pitch);
                tones = new ArrayList<>();
                for (Object sound : (List<?>) field(scape, "continuous")) {
                    SoundInstance instance = (SoundInstance) sound;
                    tones.add(new Tone(SoundEvent.createVariableRangeEvent(instance.getLocation()),
                            ((Number) field(sound, "relativeVolume")).floatValue(), instance.getPitch(), 0));
                }
                for (Object sound : (List<?>) field(scape, "repeating")) {
                    tones.add(new Tone((SoundEvent) field(sound, "event"), ((Number) field(sound, "relativeVolume")).floatValue(),
                            ((Number) field(sound, "sharedPitch")).floatValue(), ((Number) field(sound, "repeatDelay")).intValue()));
                }
                RECIPES.put(name, tones);
            }
            List<Tone> recipe = tones;
            GROUPS.computeIfAbsent("create/" + name, ignored -> new Group(recipe, true))
                    .add(context.source().apply(pos.getCenter()), 1);
        } catch (ReflectiveOperationException | RuntimeException e) { failure(group.getClass(), e); }
    }

    /** Aeronautics' gas emitters use global client loops rather than tickAudio or animateTick. */
    public static void burner(BlockEntity entity) {
        try { gasEmitter(entity); }
        catch (ReflectiveOperationException | RuntimeException e) { failure(entity.getClass(), e); }
    }

    private static void gasEmitter(BlockEntity entity) throws ReflectiveOperationException {
        if (!audible || CreateCopies.context().source() == null) return;
        String type = entity.getClass().getName();
        String event;
        if (type.endsWith(".HotAirBurnerBlockEntity")) event = "HOT_AIR_BURNER_HEAT";
        else if (type.endsWith(".SteamVentBlockEntity")) event = "STEAM_VENT_HEAT";
        else return;
        if (!(Boolean) entity.getClass().getMethod("canOutputGas").invoke(entity)) return;
        Object intensity = entity.getClass().getMethod("getClientIntensity").invoke(entity);
        float amount = ((Number) intensity.getClass().getMethod("getValue").invoke(intensity)).floatValue();
        SoundEvent sound = AERO_EVENTS.get(event);
        if (sound == null) {
            Object entry = Class.forName("dev.eriksonn.aeronautics.index.AeroSoundEvents").getField(event).get(null);
            sound = (SoundEvent) entry.getClass().getMethod("event").invoke(entry);
            AERO_EVENTS.put(event, sound);
        }
        SoundEvent resolved = sound;
        GROUPS.computeIfAbsent("aeronautics/" + event,
                ignored -> new Group(List.of(new Tone(resolved, 0.325F, 1, 0)), false))
                .add(CreateCopies.context().source().apply(entity.getBlockPos().getCenter()), Mth.clamp(amount * 4, 0, 2));
        if (event.equals("HOT_AIR_BURNER_HEAT") && RANDOM.nextInt(20) == 0
                && entity.getBlockState().getOptionalValue(BlockStateProperties.POWERED).orElse(false)) {
            local(entity.getBlockPos().getX() + 0.5, entity.getBlockPos().getY() + 0.5, entity.getBlockPos().getZ() + 0.5,
                    SoundEvents.CAMPFIRE_CRACKLE, SoundSource.BLOCKS, 0.25F + RANDOM.nextFloat() * 0.25F,
                    RANDOM.nextFloat() * 0.7F + 0.6F);
        }
    }

    public static boolean capture(SoundInstance sound) {
        var context = CreateCopies.context();
        if (context == null || !context.ticking() || OutsideSoundClient.isScenery(sound)) return false;
        if (audible && context.source() != null) {
            if (sound.isLooping()) NATIVE.putIfAbsent(sound, new Native(context.world(), sound));
            else local(sound.getX(), sound.getY(), sound.getZ(), SoundEvent.createVariableRangeEvent(sound.getLocation()),
                    sound.getSource(), sound.getVolume(), sound.getPitch());
        }
        return true;
    }

    /** Also catches playLocalSound from virtual block-entity ticks without playing it at template coordinates. */
    public static boolean local(double x, double y, double z, SoundEvent sound, SoundSource category, float volume, float pitch) {
        var context = CreateCopies.context();
        if (context == null || !context.ticking()) return false;
        if (audible && context.source() != null) {
            Vec3 at = context.source().apply(new Vec3(x, y, z));
            float finalVolume = attenuate(volume, at, sound.getRange(volume)) * OutsideSoundClient.AMBIENCE_VOLUME;
            OutsideSoundClient.play(sound, category, finalVolume, pitch, at.x, at.y, at.z, true);
        }
        return true;
    }

    private static float attenuate(float volume, Vec3 pos, double range) {
        return range > 0 ? volume * (float) Math.max(0, 1 - pos.distanceTo(new Vec3(0.5, 0.5, 0.5)) / range) : 0;
    }

    private static void desire(Object key, SoundEvent event, SoundSource category, float volume, float pitch, Vec3 at) {
        if (volume > 0.0001F && Float.isFinite(volume) && pitch > 0 && Float.isFinite(pitch)
                && Double.isFinite(at.lengthSqr())) DESIRED.put(key, new Playback(event, category, volume, pitch, at));
    }

    static int bucket(float pitch) { return pitch < 0.7F ? 0 : pitch < 0.9F ? 1 : pitch < 1.1F ? 2 : pitch < 1.3F ? 3 : 4; }

    private static Object field(Object object, String name) throws ReflectiveOperationException {
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(object); }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }

    private static void readCreateOptions() {
        try {
            Object config = Class.forName("com.simibubi.create.infrastructure.config.AllConfigs").getMethod("client").invoke(null);
            Object enabled = field(config, "enableAmbientSounds");
            createEnabled = (Boolean) enabled.getClass().getMethod("get").invoke(enabled);
            Object cap = field(config, "ambientVolumeCap");
            createCap = ((Number) cap.getClass().getMethod("get").invoke(cap)).floatValue();
        } catch (ReflectiveOperationException | LinkageError ignored) { }
    }

    private static void failure(Class<?> type, Exception exception) {
        if (FAILED.add(type)) LOGGER.warn("[Planarium Panorama] Machine ambience skipped for {}", type.getName(), exception);
    }

    private static void endNative(SoundInstance sound) {
        if (sound instanceof AbstractTickableSoundInstance) {
            try {
                Method stop = AbstractTickableSoundInstance.class.getDeclaredMethod("stop");
                stop.setAccessible(true);
                stop.invoke(sound);
            } catch (ReflectiveOperationException ignored) { }
        }
    }

    public static boolean isScenery(SoundInstance sound) { return sound instanceof Loop; }

    public static void clear() {
        LOOPS.values().forEach(Loop::end); LOOPS.clear();
        NATIVE.keySet().forEach(MachineSounds::endNative); NATIVE.clear();
        GROUPS.clear(); ACTIVE.clear(); DESIRED.clear(); level = null; audible = false;
        CreateCopies.clear();
    }

    private static final class Loop extends AbstractTickableSoundInstance {
        Playback playback;
        Loop(Playback playback) {
            super(playback.event, playback.category, RANDOM);
            looping = true; delay = 0; attenuation = SoundInstance.Attenuation.NONE;
            update(playback);
        }
        void update(Playback playback) {
            this.playback = playback; volume = playback.volume; pitch = playback.pitch; place();
        }
        private void place() {
            Vec3 at = OutsideSoundClient.playbackPosition(playback.pos.x, playback.pos.y, playback.pos.z);
            x = at.x; y = at.y; z = at.z;
        }
        @Override public void tick() { place(); }
        void end() { stop(); }
    }
}

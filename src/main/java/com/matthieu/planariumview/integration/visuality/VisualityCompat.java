package com.matthieu.planariumview.integration.visuality;

import com.matthieu.planariumview.client.scenery.LitRenderingWorld;
import com.matthieu.planariumview.client.scenery.entities.AquaticEffects;
import com.mojang.logging.LogUtils;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PowerableMob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.Supplier;

/** Client-only bridge to Visuality: Reforged's emitters and particle providers. */
public final class VisualityCompat {
    private static final String PACKAGE = "plus.dragons.visuality.";
    private static final String CONFIG = PACKAGE + "config.Config";

    private static LitRenderingWorld samplingWorld;
    private static MobCapture mobCapture;
    private static boolean ambientFailed;
    private static boolean armorFailed;
    private static boolean chargeFailed;
    private static boolean rainFailed;

    private record Emitter(Field config, Method spawn) {}
    private record MobCapture(Level level, AquaticEffects.Sink sink) {}

    private static final class Ambient {
        private static final Emitter EMITTER = emitter("BLOCK_AMBIENT_PARTICLES",
                BlockState.class, Level.class, BlockPos.class, RandomSource.class);
    }

    private static final class Mobs {
        private static final Emitter ARMOR = emitter("ENTITY_ARMOR_PARTICLES", LivingEntity.class);
        private static final Method CHARGE = chargeMethod();
    }

    private static final class Rain {
        private static final RainSettings SETTINGS = rainSettings();
    }

    private record RainSettings(Supplier<?> enabled, Supplier<?> colored, Supplier<?> density,
                                Supplier<?> particle, Method withColor) {}

    private VisualityCompat() {}

    public static boolean isParticle(Particle particle) {
        return particle.getClass().getName().startsWith(PACKAGE + "particle.");
    }

    /**
     * Sample once for every captured block, including overrides of Block.animateTick that skip super.
     * The optional mixin admits this call through Visuality's chunk check.
     */
    public static void animateBlock(LitRenderingWorld world, BlockPos pos, BlockState state, RandomSource random) {
        if (ambientFailed || Ambient.EMITTER == null) return;
        LitRenderingWorld previous = samplingWorld;
        samplingWorld = world;
        try {
            Object config = Ambient.EMITTER.config().get(null);
            if (config != null) Ambient.EMITTER.spawn().invoke(config, state, world, pos, random);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            ambientFailed = true;
            unavailable("block ambient particles", error);
        } finally {
            samplingWorld = previous;
        }
    }

    public static boolean sampling(LitRenderingWorld world) {
        return samplingWorld == world;
    }

    public static void sampleMob(LivingEntity entity, AquaticEffects.Sink sink) {
        if (Mobs.ARMOR == null && Mobs.CHARGE == null) return;
        MobCapture previous = mobCapture;
        mobCapture = new MobCapture(entity.level(), sink);
        try {
            sampleArmor(entity);
            if (entity instanceof PowerableMob) sampleCharge(entity);
        } finally {
            mobCapture = previous;
        }
    }

    public static boolean captureMobParticle(Level level, ParticleOptions options, double x, double y, double z,
                                             double vx, double vy, double vz) {
        MobCapture capture = mobCapture;
        if (capture == null || capture.level() != level) return false;
        mobCapture = null;
        try {
            capture.sink().emit(options, x, y, z, vx, vy, vz);
        } finally {
            mobCapture = capture;
        }
        return true;
    }

    private static void sampleArmor(LivingEntity entity) {
        if (armorFailed || Mobs.ARMOR == null) return;
        try {
            Object config = Mobs.ARMOR.config().get(null);
            if (config != null) Mobs.ARMOR.spawn().invoke(config, entity);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            armorFailed = true;
            unavailable("armor particles", error);
        }
    }

    private static void sampleCharge(LivingEntity entity) {
        if (chargeFailed || Mobs.CHARGE == null) return;
        try {
            Mobs.CHARGE.invoke(null, new EntityTickEvent.Post(entity));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            chargeFailed = true;
            unavailable("charge particles", error);
        }
    }

    /** WeatherView already checks that rain reaches the sampled surface. */
    public static ParticleOptions rainCircle(LitRenderingWorld world, BlockPos pos, RandomSource random) {
        if (rainFailed || Rain.SETTINGS == null) return null;
        RainSettings settings = Rain.SETTINGS;
        try {
            if (!(boolean) settings.enabled().get() || random.nextInt(256) >= (int) settings.density().get()) return null;
            int color = (boolean) settings.colored().get() ? BiomeColors.getAverageWaterColor(world, pos) : -1;
            return (ParticleOptions) settings.withColor().invoke(settings.particle().get(), color);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            rainFailed = true;
            unavailable("rain circles", error);
            return null;
        }
    }

    private static Emitter emitter(String fieldName, Class<?>... parameters) {
        try {
            Field field = Class.forName(CONFIG, false, VisualityCompat.class.getClassLoader()).getField(fieldName);
            return new Emitter(field, field.getType().getMethod("spawnParticles", parameters));
        } catch (ClassNotFoundException absent) {
            return null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            unavailable(fieldName, error);
            return null;
        }
    }

    private static Method chargeMethod() {
        try {
            return Class.forName(PACKAGE + "event.ParticleEventHandler", false, VisualityCompat.class.getClassLoader())
                    .getMethod("spawnChargeParticles", EntityTickEvent.Post.class);
        } catch (ClassNotFoundException absent) {
            return null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            unavailable("charge particles", error);
            return null;
        }
    }

    private static RainSettings rainSettings() {
        try {
            Class<?> config = Class.forName(CONFIG, false, VisualityCompat.class.getClassLoader());
            Class<?> particles = Class.forName(PACKAGE + "registry.VisualityParticles", false, VisualityCompat.class.getClassLoader());
            Class<?> colorType = Class.forName(PACKAGE + "particle.type.ColorParticleType", false, VisualityCompat.class.getClassLoader());
            return new RainSettings(value(config, "WATER_CIRCLE_ENABLED"), value(config, "WATER_CIRCLE_COLORED"),
                    value(config, "WATER_CIRCLE_DENSITY"), value(particles, "WATER_CIRCLE"), colorType.getMethod("withColor", int.class));
        } catch (ClassNotFoundException absent) {
            return null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            unavailable("rain circles", error);
            return null;
        }
    }

    private static Supplier<?> value(Class<?> type, String name) throws ReflectiveOperationException {
        return (Supplier<?>) type.getField(name).get(null);
    }

    private static void unavailable(String feature, Throwable error) {
        LogUtils.getLogger().debug("[Planarium Panorama] Visuality: Reforged integration unavailable for {}", feature, error);
    }
}

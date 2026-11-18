package com.matthieu.planariumview.integration.particle_core;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;

import java.lang.reflect.Method;

/** Optional client integration with Particle Core 0.3.3. */
public final class ParticleCoreCompat {
    private static final String PACKAGE = "me.fzzyhmstrs.particle_core.";
    private static final TagKey<ParticleType<?>> EXCLUDED =
            TagKey.create(Registries.PARTICLE_TYPE, ResourceLocation.fromNamespaceAndPath("particle_core", "excluded_particles"));

    private static Settings settings;
    private static boolean lookedUp;
    private static boolean rotationFailed;
    private static boolean asyncReadFailed;
    private static boolean filterFailed;

    private record Settings(Object config, Method impl, Method async, Method disable, Method value, Method shouldSpawn,
                            boolean typeFilter, boolean asyncHook) {}

    private static final class Rotation {
        private static final Method SETUP = rotationMethod();
    }

    private ParticleCoreCompat() {}

    public static boolean requiresSerialTick() {
        Settings api = settings();
        if (api == null || !api.asyncHook()) return false;
        if (asyncReadFailed) return true;
        try {
            return enabled(api, api.async(), api.impl().invoke(api.config()));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            asyncReadFailed = true;
            unavailable(error);
            return true;
        }
    }

    /** A null type represents a custom factory with no registered particle type. */
    public static boolean allows(ParticleType<?> type) {
        Settings api = settings();
        if (api == null || !api.typeFilter() || filterFailed) return true;
        try {
            Object config = api.impl().invoke(api.config());
            if (enabled(api, api.disable(), config)) return false;
            if (type == null || BuiltInRegistries.PARTICLE_TYPE.getResourceKey(type).isEmpty()) return true;
            return !BuiltInRegistries.PARTICLE_TYPE.wrapAsHolder(type).is(EXCLUDED)
                    && (boolean) api.shouldSpawn().invoke(config, type);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            filterFailed = true;
            unavailable(error);
            return true;
        }
    }

    public static boolean billboardCamera(Camera camera) {
        if (rotationFailed || Rotation.SETUP == null) return false;
        var engine = Minecraft.getInstance().particleEngine;
        if (!Rotation.SETUP.getDeclaringClass().isInstance(engine)) return false;
        try {
            Rotation.SETUP.invoke(engine, camera);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            rotationFailed = true;
            LogUtils.getLogger().debug("[Planarium Panorama] Particle Core camera cache unavailable", error);
            return false;
        }
    }

    private static boolean enabled(Settings api, Method setting, Object config) throws ReflectiveOperationException {
        return (boolean) api.value().invoke(setting.invoke(config));
    }

    private static Settings settings() {
        if (!lookedUp) {
            lookedUp = true;
            try {
                Class<?> owner = Class.forName(PACKAGE + "PcConfig");
                Method impl = owner.getMethod("getImpl");
                Class<?> config = impl.getReturnType();
                Method disable = config.getMethod("getDisableParticles");
                Class<?> conditions = Class.forName(PACKAGE + "plugin.PcConditionTester");
                Object tester = conditions.getConstructor().newInstance();
                Method satisfied = conditions.getMethod("isSatisfied", String.class);
                boolean typeFilter = (boolean) satisfied.invoke(tester, PACKAGE + "mixins.WorldRendererTypeMixin");
                boolean asyncHook = (boolean) satisfied.invoke(tester, PACKAGE + "mixins.ParticleManagerAsyncMixin");
                settings = new Settings(owner.getField("INSTANCE").get(null), impl, config.getMethod("getAsynchronousTicking"),
                        disable, disable.getReturnType().getMethod("get"), config.getMethod("shouldSpawnParticle", ParticleType.class),
                        typeFilter, asyncHook);
            } catch (ClassNotFoundException absent) {
                // Optional client mod.
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                unavailable(error);
            }
        }
        return settings;
    }

    private static Method rotationMethod() {
        try {
            return Class.forName(PACKAGE + "interfaces.RotationProvider").getMethod("particle_core_setupDefaultBillboardVectors", Camera.class);
        } catch (ClassNotFoundException absent) {
            return null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            LogUtils.getLogger().debug("[Planarium Panorama] Particle Core camera cache unavailable", error);
            return null;
        }
    }

    private static void unavailable(Throwable error) {
        LogUtils.getLogger().debug("[Planarium Panorama] Particle Core settings unavailable", error);
    }
}

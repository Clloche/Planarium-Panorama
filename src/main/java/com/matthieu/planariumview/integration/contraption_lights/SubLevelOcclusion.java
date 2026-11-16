package com.matthieu.planariumview.integration.contraption_lights;

import com.mojang.logging.LogUtils;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.UUID;

/** Native wall-aware lighting owns these sub-levels; projecting jar light over it would bypass their walls. */
public final class SubLevelOcclusion {
    private static final MethodHandle LOOKUP = find(SubLevelOcclusion.class.getClassLoader());
    private static boolean warned;

    private SubLevelOcclusion() { }

    static MethodHandle find(ClassLoader loader) {
        try {
            Class<?> occluders = Class.forName(
                    "xyz.atmerek.contraptionlights.light.occlusion.SubLevelOccluders", false, loader);
            return MethodHandles.publicLookup().unreflect(occluders.getMethod("of", UUID.class))
                    .asType(MethodType.methodType(Object.class, UUID.class));
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            // Optional API introduced in 1.6.0. Older versions retain our physical-position lighting.
            return null;
        } catch (ReflectiveOperationException | LinkageError e) {
            LogUtils.getLogger().warn("[Planarium Panorama] Contraption Lights wall lighting lookup unavailable", e);
            return null;
        }
    }

    /** Read on the client tick, then published with the pose for mesh workers. No version/config guesses. */
    public static boolean controls(UUID body) {
        return controls(LOOKUP, body);
    }

    static boolean controls(MethodHandle lookup, UUID body) {
        if (lookup == null) return false;
        try {
            return (Object) lookup.invokeExact(body) != null;
        } catch (Throwable e) {
            if (!warned) {
                warned = true;
                LogUtils.getLogger().warn("[Planarium Panorama] Contraption Lights wall lighting lookup failed", e);
            }
            return false;
        }
    }
}

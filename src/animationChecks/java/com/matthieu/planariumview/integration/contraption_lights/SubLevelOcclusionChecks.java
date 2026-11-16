package com.matthieu.planariumview.integration.contraption_lights;

import java.lang.invoke.MethodHandle;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/** Exercises the optional bridge against the real mod registry without starting Minecraft. */
public final class SubLevelOcclusionChecks {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        UUID body = UUID.randomUUID(), other = UUID.randomUUID();
        MethodHandle absent = SubLevelOcclusion.find(new ClassLoader(null) { });
        require(absent == null, "No wall API without Contraption Lights (or in pre-1.6 versions)");
        require(!SubLevelOcclusion.controls(absent, body), "Legacy physical-position correction stays enabled");

        if (args.length > 0) {
            try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{Path.of(args[0]).toUri().toURL()},
                    SubLevelOcclusionChecks.class.getClassLoader())) {
                MethodHandle lookup = SubLevelOcclusion.find(loader);
                require(lookup != null, "Real JAR exposes the native occlusion registry");
                Class<?> registry = loader.loadClass("xyz.atmerek.contraptionlights.light.occlusion.SubLevelOccluders");
                Class<?> occluder = loader.loadClass("xyz.atmerek.contraptionlights.light.occlusion.SubLevelOccluders$Occluder");
                Class<?> walls = loader.loadClass("xyz.atmerek.contraptionlights.sable.SubLevelEnclosure$Result");
                var publish = registry.getMethod("publish", List.class);
                // Only registry ownership is needed: no world, enclosure calculation or render state.
                Object entry = occluder.getConstructor(UUID.class, walls, double[].class, double[].class, double[].class)
                        .newInstance(body, null, new double[12], new double[12], new double[6]);
                require(!SubLevelOcclusion.controls(lookup, body), "Unpublished body retains legacy lighting");
                publish.invoke(null, List.of(entry));
                require(SubLevelOcclusion.controls(lookup, body), "Native walls take ownership of this body");
                require(!SubLevelOcclusion.controls(lookup, other), "Other bodies retain their correction");
                publish.invoke(null, List.of());
                require(!SubLevelOcclusion.controls(lookup, body), "Disabling walls restores the correction");
                publish.invoke(null, List.of(entry));
                require(SubLevelOcclusion.controls(lookup, body), "Re-enabling walls takes ownership again");
                registry.getMethod("clear").invoke(null);
                require(!SubLevelOcclusion.controls(lookup, body), "World unload releases ownership");
            }
        }
        System.out.println("Sub-level occlusion bridge checks passed" + (args.length > 0 ? " (real Contraption Lights JAR)" : " (fallback)"));
    }
}

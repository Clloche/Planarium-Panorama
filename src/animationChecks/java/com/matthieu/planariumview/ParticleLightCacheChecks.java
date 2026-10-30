package com.matthieu.planariumview;

import com.matthieu.planariumview.client.scenery.SceneryParticles;
import net.minecraft.client.particle.*;
import net.minecraft.client.Camera;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.objectweb.asm.*;
import java.lang.reflect.*;
import java.util.*;

/** Real Mixin cache injection on a controlled particle; unrelated collision hooks are excluded from this fixture. */
public final class ParticleLightCacheChecks {
    public static class Probe extends Particle {
        public Probe() { super(null, 0, 0, 0); }
        public int getLightColor(float partial) { return 77; }
        public void render(VertexConsumer buffer, Camera camera, float partial) {}
        public ParticleRenderType getRenderType() { return ParticleRenderType.NO_RENDER; }
    }
    private static Field field(Class<?> type, String name) throws Exception {
        Field f = type.getDeclaredField(name); f.setAccessible(true); return f;
    }
    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    @SuppressWarnings("unchecked") public static void main(String[] args) throws Exception {
        var transformer = SpellScopeMixinChecks.transformer("planariumview.particle-light-checks.mixins.json", node -> {
            if (!node.name.endsWith("/ParticleLightMixin")) return;
            // Preserve the production light fields, their initialisers and the exact injection method.
            node.methods.removeIf(m -> !m.name.equals("<init>") && !m.name.equals("planariumview$sceneryLight"));
            for (var annotations : Arrays.asList(node.visibleAnnotations, node.invisibleAnnotations)) {
                if (annotations == null) continue;
                for (var annotation : annotations) if (annotation.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")) {
                    for (int i = 0; i < annotation.values.size(); i += 2)
                        if (annotation.values.get(i).equals("value")) annotation.values.set(i + 1, List.of(org.objectweb.asm.Type.getType(Probe.class)));
                }
            }
        });
        String name = Probe.class.getName();
        byte[] bytes;
        try (var in = Probe.class.getResourceAsStream("/" + name.replace('.', '/') + ".class")) { bytes = in.readAllBytes(); }
        byte[] transformed = transformer.transformClassBytes(name, name, bytes);
        Class<?> type = new ClassLoader(Probe.class.getClassLoader()) {
            Class<?> define(byte[] b) { return defineClass(name, b, 0, b.length); }
        }.define(transformed);
        Particle particle = (Particle) type.getConstructor().newInstance();
        Method getLight = type.getMethod("getLightColor", float.class);
        Map<Particle, Integer> lights = (Map<Particle, Integer>) field(SceneryParticles.class, "LIGHT").get(null);
        Field tick = field(SceneryParticles.class, "tickCount");
        long initialTick = tick.getLong(null);
        try {
            lights.put(particle, 100);
            require((int) getLight.invoke(particle, 0f) == 100, "first light read uses the scenery");
            lights.put(particle, 200);
            for (int frame = 0; frame < 1000; frame++)
                require((int) getLight.invoke(particle, frame / 1000f) == 100, "subsequent frames reuse the same tick without re-reading light");
            tick.setLong(null, initialTick + 1);
            require((int) getLight.invoke(particle, 0f) == 200, "stationary particle refreshes changed light on the next tick");
            lights.remove(particle);
            tick.setLong(null, initialTick + 2);
            require((int) getLight.invoke(particle, 0f) == 77, "untracked particle returns to its vanilla light");
            lights.put(particle, 300);
            require((int) getLight.invoke(particle, 0f) == 77, "missing scenery light is also cached within one tick");
            tick.setLong(null, initialTick + 3);
            require((int) getLight.invoke(particle, 0f) == 300, "tracking is picked up on the next tick");
            SceneryParticles.clear();
            require((int) getLight.invoke(particle, 0f) == 77, "world clear invalidates an already cached light in the current tick");
        } finally { lights.remove(particle); }
        System.out.println("PASS: production particle-light mixin caches repeated frames, refreshes stationary particles and invalidates on clear");
    }
}

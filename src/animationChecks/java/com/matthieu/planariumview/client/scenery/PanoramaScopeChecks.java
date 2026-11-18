package com.matthieu.planariumview.client.scenery;

import com.llamalad7.mixinextras.MixinExtrasBootstrap;
import com.matthieu.planariumview.SpellScopeMixinChecks;
import com.matthieu.planariumview.core.config.Config;
import com.matthieu.planariumview.server.MiniatureLiveChecks;
import com.matthieu.planariumview.server.PlayerSoundCause;
import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.FogType;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

import java.util.Arrays;
import java.util.function.DoubleFunction;

import static com.matthieu.planariumview.MobAnimationIntegrationChecks.fixture;

public final class PanoramaScopeChecks {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void immersion() {
        ResourceLocation first = ResourceLocation.parse("checks:first");
        ResourceLocation second = ResourceLocation.parse("checks:second");
        DoubleFunction<FogType> halfFull = y -> y < 16 ? FogType.WATER : FogType.NONE;
        require(OutsideFluidView.sampleJar(first, 40, y -> FogType.WATER) == FogType.WATER, "first jar immersed");
        require(OutsideFluidView.sampleJar(first, 41, halfFull) == FogType.WATER, "same jar retains immersion at 50%");
        OutsideFluidView.sampleJar(first, 41, y -> { throw new AssertionError("sampled twice in one tick"); });
        require(OutsideFluidView.sampleJar(second, 41, halfFull) == FogType.NONE, "new jar starts dry even at the same tick");
        require(OutsideFluidView.sampleJar(second, 42, y -> FogType.WATER) == FogType.WATER, "second jar immersed");
        OutsideFluidView.clearImmersion();
        require(OutsideFluidView.sampleJar(second, 42, halfFull) == FogType.NONE, "rejoining a jar starts with fresh samples");
    }

    private static void soundScope() {
        RuntimeException failure = new RuntimeException("sound listener failed");
        try {
            PlayerSoundCause.play(true, () -> {
                PlayerSoundCause.play(false, () -> require(PlayerSoundCause.isPlayerCaused(), "nested sound inherits player cause"));
                PlayerSoundCause.play(true, () -> require(PlayerSoundCause.isPlayerCaused(), "nested player scope"));
                require(PlayerSoundCause.isPlayerCaused(), "outer player scope still active");
                throw failure;
            });
            throw new AssertionError("sound exception swallowed");
        } catch (RuntimeException caught) {
            require(caught == failure, "sound exception propagated");
        }
        PlayerSoundCause.play(false, () -> require(!PlayerSoundCause.isPlayerCaused(), "next sound has no player cause"));
    }

    private static int wrapped(IMixinTransformer transformer, String name, String method, String hook) throws Exception {
        String path = "/" + name.replace('.', '/') + ".class";
        ClassNode result = new ClassNode();
        try (var input = PanoramaScopeChecks.class.getResourceAsStream(path)) {
            byte[] transformed = transformer.transformClassBytes(name, name, input.readAllBytes());
            new ClassReader(transformed).accept(result, 0);
        }
        int calls = 0;
        for (var target : result.methods) {
            if (!target.name.equals(method)) continue;
            for (var insn : target.instructions) {
                if (insn instanceof MethodInsnNode call && call.name.contains(hook)) calls++;
            }
        }
        return calls;
    }

    private static void particleFog() throws Exception {
        Minecraft client = Minecraft.getInstance();
        client.gameRenderer = fixture(GameRenderer.class);
        RenderSystem.initRenderThread();
        MiniatureLiveChecks.load(Config.CLIENT_SPEC);
        Config.FOG.set(Config.Fog.OFF);
        RenderSystem.setShaderFogStart(2);
        RenderSystem.setShaderFogEnd(20);
        RenderSystem.setShaderFogShape(FogShape.SPHERE);
        RenderSystem.setShaderFogColor(.1F, .2F, .3F, .4F);
        float[] color = RenderSystem.getShaderFogColor().clone();
        RuntimeException failure = new RuntimeException("particle renderer failed");
        try {
            SceneryFog.markDrawn();
            SceneryFog.renderParticles(true, () -> {
                require(RenderSystem.getShaderFogEnd() == Float.MAX_VALUE, "draw sees the configured scenery fog");
                RenderSystem.setShaderFogShape(FogShape.CYLINDER);
                RenderSystem.setShaderFogColor(1, 1, 1, 1);
                throw failure;
            });
            throw new AssertionError("draw exception swallowed");
        } catch (RuntimeException caught) {
            require(caught == failure, "draw exception propagated");
        } finally {
            Config.CLIENT_SPEC.acceptConfig(null);
            client.gameRenderer = null;
        }
        require(RenderSystem.getShaderFogStart() == 2 && RenderSystem.getShaderFogEnd() == 20
                && RenderSystem.getShaderFogShape() == FogShape.SPHERE && Arrays.equals(color, RenderSystem.getShaderFogColor()),
                "all fog settings restored after a failed particle draw");
    }

    private static void particleProjection() {
        var samples = new java.util.concurrent.atomic.AtomicInteger();
        var frame = new FramedParticle.Frame(() -> {
            samples.incrementAndGet();
            return new org.joml.Matrix4d();
        }, () -> true, 1);
        var camera = new net.minecraft.client.Camera();
        var inner = new net.minecraft.client.particle.Particle(null, 0, 0, 0) {
            @Override public void render(com.mojang.blaze3d.vertex.VertexConsumer buffer,
                                         net.minecraft.client.Camera view, float partialTick) {
                require(view != camera, "framed particle uses its local camera");
            }

            @Override public net.minecraft.client.particle.ParticleRenderType getRenderType() {
                return net.minecraft.client.particle.ParticleRenderType.PARTICLE_SHEET_OPAQUE;
            }
        };
        var first = new FramedParticle(inner, frame);
        var second = new FramedParticle(inner, frame);
        samples.set(0);
        FramedParticle.renderPass(camera, () -> {
            first.render(null, camera, 0);
            try {
                FramedParticle.renderPass(camera, () -> {
                    second.render(null, camera, 0);
                    throw new IllegalStateException("nested draw failed");
                });
            } catch (IllegalStateException expected) {
                require(expected.getMessage().equals("nested draw failed"), "nested draw failure propagated");
            }
            second.render(null, camera, 0);
            require(samples.get() == 2, "particles share the projection and a nested pass restores it");
        });
        FramedParticle.renderPass(camera, () -> first.render(null, camera, 0));
        require(samples.get() == 3, "new pass reads the current frame pose");
    }

    public static void main(String[] args) throws Exception {
        immersion();
        soundScope();
        var transformer = SpellScopeMixinChecks.transformer("planariumview.panorama-scope-checks.mixins.json", node -> {});
        MixinExtrasBootstrap.init();
        require(wrapped(transformer, "net.minecraft.server.level.ServerLevel", "playSeededSound", "planariumview$") == 2,
                "both server sound overloads call the production wrappers");
        require(wrapped(transformer, "net.minecraft.client.particle.ParticleEngine", "render", "planariumview$sceneryFog") == 1,
                "only the NeoForge particle renderer calls the production wrapper");
        require(wrapped(transformer, "net.minecraft.client.particle.ParticleEngine", "tick", "planariumview$serialParticleTick") == 1,
                "serial fallback wraps the particle queue traversal");
        particleFog();
        particleProjection();
        System.out.println("PASS: immersion, sound scopes, particle wrappers, fog cleanup and projection caching");
    }
}

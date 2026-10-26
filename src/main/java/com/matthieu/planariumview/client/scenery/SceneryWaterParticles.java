package com.matthieu.planariumview.client.scenery;

import com.matthieu.planariumview.config.Config;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.IdentityHashMap;
import java.util.Map;

/** The normal particle engine ticks these particles; only their drawing moves before the scenery's water. */
public final class SceneryWaterParticles {
    private static final Map<ParticleRenderType, WaterSheet> SHEETS = new IdentityHashMap<>();
    private static boolean drawing;
    private static long generation;

    private SceneryWaterParticles() {}

    static boolean supports(ParticleOptions options) {
        var type = options.getType();
        return type == ParticleTypes.BUBBLE || type == ParticleTypes.BUBBLE_COLUMN_UP || type == ParticleTypes.CURRENT_DOWN
                || type == ParticleTypes.BUBBLE_POP || type == ParticleTypes.DOLPHIN || type == ParticleTypes.GLOW
                || type == ParticleTypes.SQUID_INK || type == ParticleTypes.GLOW_SQUID_INK;
    }

    private record WaterSheet(ParticleRenderType original) implements ParticleRenderType {
        @Override public BufferBuilder begin(Tesselator tessellator, TextureManager textures) {
            // Later vanilla passes must not draw the same particles again, after the water has written its depth.
            return drawing ? original.begin(tessellator, textures) : null;
        }
        @Override public boolean isTranslucent() { return original.isTranslucent(); }
    }

    static Particle wrap(Particle inner) { return new WaterParticle(inner); }

    /** Called inside the same camera scope as the mobs, after opaque terrain and before translucent terrain. */
    static void render(RenderLevelStageEvent event) {
        if (!Config.sceneryParticles() || FlightView.active()) return;
        Minecraft mc = Minecraft.getInstance();
        boolean previous = drawing;
        drawing = true;
        try {
            // Custom sheets have no guaranteed relative order in ParticleEngine. Keep native opaque-before-alpha order.
            mc.particleEngine.render(mc.gameRenderer.lightTexture(), event.getCamera(),
                    event.getPartialTick().getGameTimeDeltaPartialTick(false), event.getFrustum(),
                    type -> type instanceof WaterSheet && !type.isTranslucent());
            mc.particleEngine.render(mc.gameRenderer.lightTexture(), event.getCamera(),
                    event.getPartialTick().getGameTimeDeltaPartialTick(false), event.getFrustum(),
                    type -> type instanceof WaterSheet && type.isTranslucent());
        } finally {
            drawing = previous;
        }
    }

    static void clear() { generation++; }

    private static final class WaterParticle extends Particle {
        private final Particle inner;
        private final ParticleRenderType sheet;
        private final long born = generation;

        WaterParticle(Particle inner) {
            super(Minecraft.getInstance().level, 0, 0, 0);
            this.inner = inner;
            sheet = SHEETS.computeIfAbsent(inner.getRenderType(), WaterSheet::new);
            hasPhysics = false;
            locate();
            xo = x; yo = y; zo = z;
        }

        private void locate() {
            var box = inner.getBoundingBox();
            x = (box.minX + box.maxX) * .5; y = box.minY; z = (box.minZ + box.maxZ) * .5;
            setBoundingBox(box);
        }

        private boolean active() {
            return born == generation && level == Minecraft.getInstance().level && Config.sceneryParticles();
        }

        @Override public void tick() {
            if (!active()) { inner.remove(); remove(); return; }
            xo = x; yo = y; zo = z;
            if (inner instanceof FramedParticle) inner.tick();
            else {
                SceneryParticles.beginTick(inner);
                try { inner.tick(); }
                finally { SceneryParticles.endTick(inner); }
            }
            if (!inner.isAlive()) remove();
            else locate();
        }

        @Override public ParticleRenderType getRenderType() { return sheet; }

        @Override public void render(VertexConsumer buffer, Camera camera, float partialTick) {
            if (active()) inner.render(buffer, camera, partialTick);
        }
    }
}

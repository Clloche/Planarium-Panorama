package com.matthieu.planariumview;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Eclairs du dehors dans le bocal : le tonnerre (dans la direction de l'eclair), l'eclair du ciel,
 * et l'eclair lui-meme, agrandi comme le decor geant, s'il tombe assez pres pour etre vu.
 */
final class LightningView {
    private static final RandomSource RANDOM = RandomSource.create();
    private static final List<Bolt> BOLTS = new ArrayList<>();

    private static final class Bolt {
        final double dx;
        final double dy;
        final double dz;
        final LightningBolt entity;
        // Meme cycle que LightningBolt : quelques eclats rapides.
        int life = 2;
        int flashes;

        Bolt(double dx, double dy, double dz, LightningBolt entity) {
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
            this.entity = entity;
            this.flashes = RANDOM.nextInt(3) + 1;
        }
    }

    private LightningView() {
    }

    static void init() {
        NeoForge.EVENT_BUS.addListener(LightningView::onClientTick);
        NeoForge.EVENT_BUS.addListener(LightningView::onRender);
    }

    static void receive(LightningPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || !Config.weather() || !level.dimension().location().equals(payload.pocketDim())) {
            return;
        }
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        Vec3 jar = toJar(payload.dx(), payload.dy(), payload.dz());
        Vec3 direction = jar.subtract(camera);
        direction = direction.lengthSqr() < 1.0E-4 ? new Vec3(0, 1, 0) : direction.normalize();
        double distance = Math.sqrt(payload.dx() * payload.dx() + payload.dz() * payload.dz());

        // Le tonnerre n'est pas visible par PlayLevelSoundEvent : il garde sa voie speciale, mais suit le bouton Sons.
        if (Config.outsideSounds()) {
            Vec3 thunder = camera.add(direction.scale(16.0));
            mc.getSoundManager().play(new SimpleSoundInstance(SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER,
                    10000.0F, 0.8F + RANDOM.nextFloat() * 0.2F, RANDOM, thunder.x, thunder.y, thunder.z));
            // Impact : seulement si l'eclair tombe pres du Planarium.
            if (distance <= 32.0) {
                Vec3 impact = camera.add(direction.scale(Math.min(24.0, 4.0 + distance)));
                mc.getSoundManager().play(new SimpleSoundInstance(SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.WEATHER,
                        2.0F, 0.5F + RANDOM.nextFloat() * 0.2F, RANDOM, impact.x, impact.y, impact.z));
            }
        }
        if (SkySync.skyActive(level) && !SkySync.underground(level)) {
            level.setSkyFlashTime(2);
        }

        // L'eclair lui-meme, s'il tombe dans la distance d'affichage du bocal.
        float reach = mc.gameRenderer.getDepthFar() / ClientView.GIANT;
        // Un eclair qui tombe sur le Planarium ou juste a cote passerait a l'interieur du bocal : on ne le dessine pas
        // (on garde le tonnerre, l'impact et l'eclair du ciel).
        boolean onPlanarium = Math.abs(payload.dx() - 0.5) < 2.0 && Math.abs(payload.dz() - 0.5) < 2.0;
        if (distance <= reach && !onPlanarium && !SkySync.underground(level)) {
            LightningBolt entity = EntityType.LIGHTNING_BOLT.create(level);
            if (entity != null) {
                entity.seed = payload.seed();
                entity.setVisualOnly(true);
                BOLTS.add(new Bolt(payload.dx(), payload.dy(), payload.dz(), entity));
            }
        }
    }

    /** Position dans le bocal d'un point du monde d'origine (relatif au Planarium), comme le decor geant. */
    private static Vec3 toJar(double dx, double dy, double dz) {
        return new Vec3((dx - ClientView.ARS_OFFSET) * ClientView.GIANT,
                (dy - ClientView.ARS_OFFSET) * ClientView.GIANT - ClientView.Y_SHIFT,
                (dz - ClientView.ARS_OFFSET) * ClientView.GIANT);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        if (BOLTS.isEmpty()) {
            return;
        }
        if (Minecraft.getInstance().level == null) {
            BOLTS.clear();
            return;
        }
        Iterator<Bolt> it = BOLTS.iterator();
        while (it.hasNext()) {
            Bolt bolt = it.next();
            bolt.life--;
            if (bolt.life < 0) {
                if (bolt.flashes == 0) {
                    it.remove();
                } else if (bolt.life < -RANDOM.nextInt(10)) {
                    bolt.flashes--;
                    bolt.life = 1;
                    bolt.entity.seed = RANDOM.nextLong();
                }
            }
        }
    }

    private static void onRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_WEATHER || BOLTS.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !Config.weather()) {
            return;
        }
        Vec3 camera = event.getCamera().getPosition();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        for (Bolt bolt : BOLTS) {
            if (bolt.life < 0) {
                continue;
            }
            Vec3 jar = toJar(bolt.dx, bolt.dy, bolt.dz);
            PoseStack poseStack = new PoseStack();
            poseStack.mulPose(event.getModelViewMatrix());
            poseStack.translate(jar.x - camera.x, jar.y - camera.y, jar.z - camera.z);
            poseStack.scale(ClientView.GIANT, ClientView.GIANT, ClientView.GIANT);
            try {
                EntityRenderer<? super LightningBolt> renderer = mc.getEntityRenderDispatcher().getRenderer(bolt.entity);
                renderer.render(bolt.entity, 0.0F, partialTick, poseStack, buffers, 0xF000F0);
            } catch (Exception ignored) {
                // Un eclair qui ne se dessine pas ne doit pas casser le rendu.
            }
        }
        buffers.endBatch(RenderType.lightning());
    }
}

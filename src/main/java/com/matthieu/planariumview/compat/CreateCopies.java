package com.matthieu.planariumview.compat;

import com.hollingsworth.arsnouveau.client.renderer.PlanariumRenderingWorld;
import com.matthieu.planariumview.client.sound.MachineSounds;
import com.matthieu.planariumview.config.Config;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.UnaryOperator;

/** Client-only Create previews. Never tick or reattach a block entity from the real world. */
public final class CreateCopies {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<PlanariumRenderingWorld, Seen> VISIBLE = new WeakHashMap<>();
    private static int ticks;
    private static Context context;
    private static Class<?> smart;
    private static boolean checked;

    public record Context(PlanariumRenderingWorld world, UnaryOperator<Vec3> source, Vec3 camera, boolean ticking) {}
    private static final class Seen { int rendered, ticked = -1; }
    private static final class Hooks {
        final Method markVirtual, tick;
        boolean failed;
        Hooks(Class<?> type) throws ReflectiveOperationException {
            markVirtual = type.getMethod("markVirtual");
            tick = type.getMethod("tick");
        }
    }
    private static final ClassValue<Hooks> HOOKS = new ClassValue<>() {
        @Override protected Hooks computeValue(Class<?> type) {
            try { return new Hooks(type); }
            catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        }
    };

    private CreateCopies() {}

    private static boolean supported(BlockEntity entity) {
        if (!checked) {
            checked = true;
            try { smart = Class.forName("com.simibubi.create.foundation.blockEntity.SmartBlockEntity"); }
            catch (ClassNotFoundException | LinkageError ignored) { }
        }
        return smart != null && smart.isInstance(entity);
    }

    /** Called before onLoad, so Flywheel cannot register a preview at a real-world position. */
    public static void attach(BlockEntity entity, PlanariumRenderingWorld world, Level fallback) {
        if (Config.enabled() && supported(entity)) {
            try {
                HOOKS.get(entity.getClass()).markVirtual.invoke(entity);
                entity.setLevel(world);
                return;
            } catch (ReflectiveOperationException | RuntimeException e) {
                LOGGER.debug("[Planarium Panorama] Create preview attachment failed", e);
            }
        }
        entity.setLevel(fallback);
    }

    public static Context context() { return context; }
    public static Vec3 camera() { return context == null ? null : context.camera(); }
    public static boolean ticking() { return context != null && context.ticking(); }

    public static void beginTick() { ticks++; }

    public static void tick(PlanariumRenderingWorld world, UnaryOperator<Vec3> source) {
        if (world == null || !Config.enabled()) return;
        Seen seen = VISIBLE.computeIfAbsent(world, ignored -> new Seen());
        if (seen.ticked == ticks) return;
        seen.ticked = ticks;
        Context previous = context;
        context = new Context(world, source, null, true);
        try {
            com.matthieu.planariumview.client.scenery.CopyParticles.capture(world, () -> {
                for (BlockEntity entity : world.blockEntityMap.values()) {
                    if (!supported(entity) || entity.isRemoved()) continue;
                    // Ponder powers whistles unconditionally in virtual mode; a snapshot must keep its redstone state.
                    if (entity.getClass().getName().endsWith(".WhistleBlockEntity")
                            && !entity.getBlockState().getOptionalValue(
                                    net.minecraft.world.level.block.state.properties.BlockStateProperties.POWERED).orElse(false)) continue;
                    Hooks hooks = HOOKS.get(entity.getClass());
                    if (hooks.failed) continue;
                    attach(entity, world, world);
                    try {
                        hooks.tick.invoke(entity);
                        if (source != null) MachineSounds.burner(entity);
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        hooks.failed = true;
                        LOGGER.warn("[Planarium Panorama] Create preview animation skipped for {}", entity.getClass().getName(), e);
                    }
                }
            });
        } finally { context = previous; }
    }

    /** Miniature animation remains active with scenery sounds and particles turned off. */
    public static void tickVisible() {
        for (var entry : java.util.List.copyOf(VISIBLE.entrySet())) {
            if (ticks - entry.getValue().rendered <= 2) tick(entry.getKey(), null);
        }
        VISIBLE.entrySet().removeIf(entry -> ticks - entry.getValue().rendered > 20);
    }

    public static void soundTick(PlanariumRenderingWorld world, UnaryOperator<Vec3> source, Runnable action) {
        Context previous = context;
        context = new Context(world, source, null, true);
        try { action.run(); }
        finally { context = previous; }
    }

    public static <T extends BlockEntity> void render(BlockEntityRenderer<T> renderer, T entity,
            PlanariumRenderingWorld world, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        if (world == null || !Config.enabled() || !supported(entity)) {
            renderer.render(entity, partialTick, pose, buffers, light, overlay);
            return;
        }
        attach(entity, world, world);
        VISIBLE.computeIfAbsent(world, ignored -> new Seen()).rendered = ticks;
        if (world instanceof com.matthieu.planariumview.client.scenery.LitRenderingWorld lit) {
            Vec3 eye = net.minecraft.client.Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
            var at = entity.getBlockPos();
            var frame = new org.joml.Matrix4d().translation(eye.x, eye.y, eye.z)
                    .mul(new org.joml.Matrix4d(pose.last().pose())).translate(-at.getX(), -at.getY(), -at.getZ());
            double scale = com.matthieu.planariumview.client.scenery.LitRenderingWorld.isGiant(lit)
                    ? com.matthieu.planariumview.client.scenery.ClientView.GIANT : com.matthieu.planariumview.client.scenery.ClientView.ARS_SCALE;
            com.matthieu.planariumview.client.scenery.CopyParticles.seen(lit, frame, scale, null);
        }
        Vector3f local = new Matrix4f(pose.last().pose()).invert().transformPosition(new Vector3f());
        Vec3 camera = new Vec3(local.x + entity.getBlockPos().getX(), local.y + entity.getBlockPos().getY(),
                local.z + entity.getBlockPos().getZ());
        Context previous = context;
        context = new Context(world, null, camera, false);
        // Aeronautics draws its flame immediately and changes GL state instead of using the supplied buffers.
        boolean flame = entity.getClass().getName().endsWith(".HotAirBurnerBlockEntity");
        boolean depth = flame && GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean cull = flame && GL11.glIsEnabled(GL11.GL_CULL_FACE);
        var shader = flame ? RenderSystem.getShader() : null;
        try { renderer.render(entity, partialTick, pose, buffers, light, overlay); }
        finally {
            context = previous;
            if (flame) {
                if (depth) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
                if (cull) RenderSystem.enableCull(); else RenderSystem.disableCull();
                if (shader != null) RenderSystem.setShader(() -> shader);
            }
        }
    }

    public static void clear() { VISIBLE.clear(); context = null; }
}

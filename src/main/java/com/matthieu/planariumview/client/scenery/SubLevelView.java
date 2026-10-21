package com.matthieu.planariumview.client.scenery;

import com.hollingsworth.arsnouveau.client.renderer.LiquidBlockVertexConsumer;
import com.hollingsworth.arsnouveau.client.renderer.tile.PlanariumRenderer;
import com.hollingsworth.arsnouveau.client.renderer.tile.PlanariumRenderer.StructureRenderData;
import com.hollingsworth.arsnouveau.client.renderer.world.CulledStatePos;
import com.hollingsworth.arsnouveau.common.block.tile.PlanariumTile;
import com.matthieu.planariumview.compat.ColorCompat;
import com.matthieu.planariumview.compat.SwingingLanternsCompat;
import com.matthieu.planariumview.client.mini.MiniOutsideLight;
import com.matthieu.planariumview.config.Config;
import com.matthieu.planariumview.network.SubLevelPayload;
import com.matthieu.planariumview.network.SubLevelTransforms;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Arrays;
import java.util.LinkedHashMap;
import net.minecraft.world.level.LightLayer;

/** Sable-independent snapshots, drawn in their own interpolated reference frames. */
public final class SubLevelView {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<ResourceLocation, Scene> OUTSIDE = new HashMap<>();
    private static final Map<ResourceLocation, Scene> MINIATURES = new HashMap<>();
    private static final List<RenderType> LAYERS = List.of(RenderType.solid(), RenderType.cutout(),
            RenderType.cutoutMipped(), RenderType.translucent(), RenderType.tripwire());
    private static final double O = -ClientView.ARS_OFFSET * (double) ClientView.GIANT;
    private static MultiBufferSource.BufferSource specialBuffers;

    private static final class Pose {
        Matrix4d from, to;
        long received;

        void receive(Matrix4d matrix) {
            from = to == null ? new Matrix4d(matrix) : current();
            to = matrix;
            received = System.nanoTime();
        }

        Matrix4d current() {
            if (to == null) return new Matrix4d();
            double alpha = Math.min(1.0, (System.nanoTime() - received) / 100_000_000.0);
            Vector3d p = from.getTranslation(new Vector3d()).lerp(to.getTranslation(new Vector3d()), alpha);
            Quaterniond q = from.getUnnormalizedRotation(new Quaterniond()).normalize()
                    .slerp(to.getUnnormalizedRotation(new Quaterniond()).normalize(), alpha);
            Vector3d s = from.getScale(new Vector3d()).lerp(to.getScale(new Vector3d()), alpha);
            return new Matrix4d().translationRotateScale(p, q, s);
        }
    }

    private static final class Scene {
        final Pose anchor = new Pose();
        final Map<String, Part> parts = new HashMap<>();
    }

    private static final class Part implements AutoCloseable {
        final Pose pose = new Pose();
        CompoundTag pending, source;
        Mesh mesh;
        boolean realLight, color;
        final LinkedHashMap<MiniKey, MiniMesh> minis = new LinkedHashMap<>(4, 0.75F, true);

        void prepare() {
            if (pending != null || source != null && (realLight != Config.realLighting() || color != Config.coloredLight())) {
                close();
                if (pending != null) source = pending;
                pending = null;
                realLight = Config.realLighting();
                color = Config.coloredLight();
            }
        }

        Mesh mesh() {
            prepare();
            if (mesh == null && source != null) {
                try {
                    mesh = new Mesh(source, null, null, null);
                } catch (RuntimeException e) {
                    LOGGER.warn("[Planarium Panorama] Sub-level section could not be rendered", e);
                }
            }
            return mesh;
        }

        Mesh mini(PlanariumTile tile) {
            prepare();
            if (source == null || tile.getLevel() == null) return null;
            MiniKey key = MiniKey.of(tile);
            MiniMesh cached = minis.get(key);
            long now = tile.getLevel().getGameTime();
            StructureRenderData pocket = PlanariumRenderer.structureRenderData.get(tile.key);
            if (cached != null && Math.abs(now - cached.checked) < 20 && cached.pocket == pocket) return cached.mesh;
            int[] environment = Config.realLighting() ? MiniOutsideLight.measure(tile.getLevel(), tile.getBlockPos()) : null;
            Matrix4d lightPose = pose.current();
            if (cached != null && cached.pocket == pocket && Arrays.equals(environment, cached.environment)
                    && cached.pose.equals(lightPose, 1.0e-5)) {
                cached.checked = now;
                return cached.mesh;
            }
            try {
                // Give this jar's ambient light to the complete pocket, then sample it in the moving section's frame.
                MiniOutsideLight.apply(pocket, tile.getLevel(), tile.getBlockPos());
                Mesh replacement = new Mesh(source, tile, pocket, lightPose);
                if (cached != null) cached.mesh.close();
                minis.put(key, new MiniMesh(replacement, pocket, environment, lightPose, now));
                if (minis.size() > 4) minis.remove(minis.firstEntry().getKey()).mesh.close();
                return replacement;
            } catch (RuntimeException e) {
                LOGGER.warn("[Planarium Panorama] Sub-level miniature could not be rendered", e);
                return cached == null ? null : cached.mesh;
            }
        }

        boolean ready(PlanariumTile tile) {
            return tile == null ? mesh != null : minis.containsKey(MiniKey.of(tile));
        }

        @Override
        public void close() {
            if (mesh != null) mesh.close();
            mesh = null;
            minis.values().forEach(value -> value.mesh.close());
            minis.clear();
        }
    }

    private record MiniKey(ResourceLocation dimension, BlockPos pos) {
        static MiniKey of(PlanariumTile tile) {
            return new MiniKey(tile.getLevel().dimension().location(), tile.getBlockPos().immutable());
        }
    }

    private static final class MiniMesh {
        final Mesh mesh;
        final StructureRenderData pocket;
        final int[] environment;
        final Matrix4d pose;
        long checked;

        MiniMesh(Mesh mesh, StructureRenderData pocket, int[] environment, Matrix4d pose, long checked) {
            this.mesh = mesh;
            this.pocket = pocket;
            this.environment = environment;
            this.pose = pose;
            this.checked = checked;
        }
    }

    private SubLevelView() {
    }

    public static void receive(SubLevelPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !Config.enabled()) return;
        if (!payload.miniature() && !mc.level.dimension().location().equals(payload.dimension())) return;
        if (!Config.subLevelViews()) {
            // A moving jar still needs its reference frame for the ordinary terrain panorama.
            if (!payload.miniature() && payload.scene().contains("anchor", Tag.TAG_LIST)) {
                OUTSIDE.computeIfAbsent(payload.dimension(), ignored -> new Scene()).anchor
                        .receive(SubLevelTransforms.read(payload.scene(), "anchor"));
            }
            return;
        }
        Map<ResourceLocation, Scene> scenes = payload.miniature() ? MINIATURES : OUTSIDE;
        Scene scene = scenes.computeIfAbsent(payload.dimension(), ignored -> new Scene());
        CompoundTag tag = payload.scene();
        if (tag.contains("geometry", Tag.TAG_COMPOUND)) {
            String id = tag.getString("id");
            if (scene.parts.size() < 256 || scene.parts.containsKey(id)) {
                Part part = scene.parts.computeIfAbsent(id, ignored -> new Part());
                CompoundTag geometry = tag.getCompound("geometry");
                if (!geometry.equals(part.source)) part.pending = geometry;
            }
        }
        if (tag.contains("frames", Tag.TAG_LIST)) {
            var frames = tag.getList("frames", Tag.TAG_COMPOUND);
            if (frames.size() > 256) throw new IllegalArgumentException("Too many sub-level sections");
            Set<String> visible = new HashSet<>();
            scene.anchor.receive(SubLevelTransforms.read(tag, "anchor"));
            for (int i = 0; i < frames.size(); i++) {
                CompoundTag frame = frames.getCompound(i);
                String id = frame.getString("id");
                visible.add(id);
                scene.parts.computeIfAbsent(id, ignored -> new Part()).pose.receive(SubLevelTransforms.read(frame, "pose"));
            }
            scene.parts.entrySet().removeIf(entry -> {
                if (visible.contains(entry.getKey())) return false;
                entry.getValue().close();
                return true;
            });
        }
    }

    /** Repositions an already-built terrain mesh around a moving jar. */
    public static void terrain(PoseStack stack, BlockPos captureCenter) {
        Scene scene = outside();
        if (scene == null || scene.anchor.to == null) return;
        double scale = ClientView.GIANT;
        Matrix4d correction = new Matrix4d().translation(O, O - ClientView.Y_SHIFT, O).scale(scale)
                .mul(scene.anchor.current().invert()).translate(captureCenter.getX(), captureCenter.getY(), captureCenter.getZ())
                .scale(1.0 / scale).translate(-O, -O + ClientView.Y_SHIFT, -O);
        stack.mulPose(new Matrix4f(correction));
    }

    private static Scene outside() {
        var level = Minecraft.getInstance().level;
        return level == null ? null : OUTSIDE.get(level.dimension().location());
    }

    public static void drawOutside(RenderLevelStageEvent event, BlockPos captureCenter, boolean special) {
        Scene scene = outside();
        if (scene == null || !Config.subLevelViews()) return;
        PoseStack pose = new PoseStack();
        if (!special) pose.mulPose(event.getModelViewMatrix());
        Vec3 camera = event.getCamera().getPosition();
        pose.translate(-camera.x, -camera.y, -camera.z);
        pose.translate(O, O - ClientView.Y_SHIFT, O);
        pose.scale(ClientView.GIANT, ClientView.GIANT, ClientView.GIANT);
        Matrix4d inverse = scene.anchor.to == null
                ? new Matrix4d().translation(-captureCenter.getX(), -captureCenter.getY(), -captureCenter.getZ())
                : scene.anchor.current().invert();
        draw(scene, pose, inverse, event.getProjectionMatrix(), special,
                event.getPartialTick().getGameTimeDeltaPartialTick(false), null, null);
    }

    public static void mini(PlanariumTile tile, float partialTick, PoseStack pose, MultiBufferSource buffers) {
        if (tile.key == null || !Config.subLevelViews()) return;
        Scene scene = MINIATURES.get(tile.key.location());
        if (scene == null) return;
        pose.pushPose();
        try {
            pose.translate(ClientView.ARS_OFFSET, ClientView.ARS_OFFSET, ClientView.ARS_OFFSET);
            pose.scale(ClientView.ARS_SCALE, ClientView.ARS_SCALE, ClientView.ARS_SCALE);
            pose.translate(0, 25, 0);
            PoseStack model = new PoseStack();
            model.mulPose(RenderSystem.getModelViewMatrix());
            model.mulPose(pose.last().pose());
            draw(scene, model, new Matrix4d(), RenderSystem.getProjectionMatrix(), false, partialTick, null, tile);
            draw(scene, pose, new Matrix4d(), RenderSystem.getProjectionMatrix(), true, partialTick, buffers, tile);
        } finally {
            pose.popPose();
        }
    }

    private static void draw(Scene scene, PoseStack pose, Matrix4d inverse, Matrix4f projection, boolean special,
                              float partialTick, MultiBufferSource supplied, PlanariumTile tile) {
        if (special && supplied == null && specialBuffers == null) {
            specialBuffers = MultiBufferSource.immediate(new ByteBufferBuilder(262144));
        }
        MultiBufferSource buffers = supplied != null ? supplied : specialBuffers;
        int built = 0;
        for (Part part : scene.parts.values()) {
            if (part.pose.to == null) continue;
            if (!part.ready(tile) && built++ >= 2) continue;
            Mesh mesh = tile == null ? part.mesh() : part.mini(tile);
            if (mesh == null) continue;
            pose.pushPose();
            try {
                pose.mulPose(new Matrix4f(new Matrix4d(inverse).mul(part.pose.current())));
                if (special) mesh.special(pose, buffers, partialTick);
                else mesh.draw(pose.last().pose(), projection);
            } finally {
                pose.popPose();
            }
        }
        if (special && supplied == null) specialBuffers.endBatch();
    }

    private static final class Mesh implements AutoCloseable {
        final StructureRenderData data;
        final Map<RenderType, VertexBuffer> buffers = new HashMap<>();
        final Map<RenderType, MeshData.SortState> sorting = new HashMap<>();

        Mesh(CompoundTag tag, PlanariumTile tile, StructureRenderData pocket, Matrix4d lightPose) {
            var level = Minecraft.getInstance().level;
            if (level == null) throw new IllegalStateException("No client world");
            StructureTemplate template = new StructureTemplate();
            template.load(level.holderLookup(net.minecraft.core.registries.Registries.BLOCK), tag);
            data = LitRenderingWorld.asGiant(() -> new StructureRenderData(new StructureTemplate(), 0L));
            data.statePosCache = ClientView.statesOf(template);
            if (data.statePosCache.size() > 4096) throw new IllegalArgumentException("Oversized sub-level section");
            StructureTemplate border = new StructureTemplate();
            border.load(level.holderLookup(net.minecraft.core.registries.Registries.BLOCK), tag.getCompound("neighbors"));
            ArrayList<CulledStatePos> neighbors = new ArrayList<>();
            for (CulledStatePos state : ClientView.statesOf(border)) {
                neighbors.add(new CulledStatePos(state.state, state.pos.offset(-1, -1, -1), null));
            }
            neighbors.removeIf(state -> state.pos.getX() >= 0 && state.pos.getX() < 16
                    && state.pos.getY() >= 0 && state.pos.getY() < 16 && state.pos.getZ() >= 0 && state.pos.getZ() < 16);
            neighbors.addAll(data.statePosCache);
            LitRenderingWorld world = LitRenderingWorld.giant(level, neighbors);
            if (Config.realLighting()) {
                byte[] light = tag.getByteArray("light");
                if (tile != null && pocket != null && pocket.fakeRenderingWorld instanceof LitRenderingWorld fullPocket) {
                    light = miniatureLight(light, fullPocket, lightPose);
                }
                world.setLight(light, 18);
            }
            if (Config.biomeColors()) {
                ResourceLocation biome = ResourceLocation.tryParse(tag.getString("biome"));
                if (biome != null) level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BIOME)
                        .getHolder(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.BIOME, biome))
                        .ifPresent(world::setTintBiome);
            }
            if (tile != null && Config.biomeColors()) world.setTintBiome(level.getBiome(tile.getBlockPos()));
            data.fakeRenderingWorld = world;
            try {
                ColorCompat.begin(world);
                tessellate();
                for (var entry : data.bufferBuilders.entrySet()) {
                    MeshData mesh = entry.getValue().build();
                    if (mesh == null) continue;
                    if (entry.getKey() == RenderType.translucent()) {
                        sorting.put(entry.getKey(), mesh.sortQuads(data.getByteBuffer(entry.getKey()), VertexSorting.DISTANCE_TO_ORIGIN));
                    }
                    VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
                    buffers.put(entry.getKey(), buffer);
                    buffer.bind();
                    buffer.upload(mesh);
                }
                GiantSprites.collectMini(data);
            } catch (RuntimeException e) {
                close();
                throw e;
            } finally {
                VertexBuffer.unbind();
                ColorCompat.end();
            }
        }

        private static byte[] miniatureLight(byte[] stored, LitRenderingWorld pocket, Matrix4d pose) {
            if (stored.length != 18 * 18 * 18) return stored;
            byte[] light = stored.clone();
            Vector3d point = new Vector3d();
            for (int x = 0; x < 18; x++) {
                for (int y = 0; y < 18; y++) {
                    for (int z = 0; z < 18; z++) {
                        pose.transformPosition(point.set(x - 0.5, y - 0.5, z - 0.5));
                        // Ars's ordinary pocket template starts one block above the dimension floor.
                        BlockPos at = BlockPos.containing(point.x, point.y - 1, point.z);
                        int index = (x * 18 + y) * 18 + z;
                        int value = Byte.toUnsignedInt(light[index]);
                        int sky = Math.min(value >> 4, pocket.getBrightness(LightLayer.SKY, at));
                        int block = Math.max(value & 15, pocket.getBrightness(LightLayer.BLOCK, at));
                        light[index] = (byte) (sky << 4 | block);
                    }
                }
            }
            return light;
        }

        private void tessellate() {
            var dispatcher = Minecraft.getInstance().getBlockRenderer();
            RandomSource random = RandomSource.create();
            PoseStack pose = new PoseStack();
            for (CulledStatePos state : data.statePosCache) {
                if (state.state.isAir()) continue;
                PlanariumRenderer.updateCulling(state, data.fakeRenderingWorld);
                if (state.shouldSkipRender()) continue;
                pose.pushPose();
                try {
                    pose.translate(state.pos.getX(), state.pos.getY(), state.pos.getZ());
                    var fluid = state.state.getFluidState();
                    if (!fluid.isEmpty()) {
                        RenderType layer = ItemBlockRenderTypes.getRenderLayer(fluid);
                        dispatcher.renderLiquid(state.pos, data.fakeRenderingWorld,
                                new LiquidBlockVertexConsumer(builder(layer), pose, state.pos), state.state, fluid);
                    }
                    if (!PlanariumRenderer.isModelRender(state.state)) continue;
                    var model = dispatcher.getBlockModel(state.state);
                    for (RenderType layer : model.getRenderTypes(state.state, random, ModelData.EMPTY)) {
                        VertexConsumer target = ColorCompat.building()
                                ? new FaceHueConsumer(builder(layer), data.fakeRenderingWorld, state.pos) : builder(layer);
                        PlanariumRenderer.tesselateBlock(state, dispatcher.getModelRenderer(), data.fakeRenderingWorld,
                                model, state.state, state.pos, pose, target, random, state.state.getSeed(state.pos),
                                OverlayTexture.NO_OVERLAY, ModelData.EMPTY, layer);
                    }
                } finally {
                    pose.popPose();
                }
            }
        }

        private BufferBuilder builder(RenderType type) {
            return data.bufferBuilders.computeIfAbsent(type,
                    layer -> new BufferBuilder(data.getByteBuffer(layer), layer.mode(), layer.format()));
        }

        void draw(Matrix4f model, Matrix4f projection) {
            GiantSprites.markMini(data);
            for (RenderType layer : LAYERS) {
                VertexBuffer buffer = buffers.get(layer);
                if (buffer == null) continue;
                layer.setupRenderState();
                try {
                    buffer.bind();
                    MeshData.SortState sort = sorting.get(layer);
                    if (sort != null) {
                        Vector3f camera = new Matrix4f(model).invert().transformPosition(new Vector3f());
                        data.getByteBuffer(layer).clear();
                        buffer.uploadIndexBuffer(sort.buildSortedIndexBuffer(data.getByteBuffer(layer), VertexSorting.byDistance(camera)));
                    }
                    buffer.drawWithShader(model, projection, RenderSystem.getShader());
                } finally {
                    VertexBuffer.unbind();
                    layer.clearRenderState();
                }
            }
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        void special(PoseStack pose, MultiBufferSource buffers, float partialTick) {
            var dispatcher = Minecraft.getInstance().getBlockEntityRenderDispatcher();
            for (BlockEntity entity : data.fakeRenderingWorld.blockEntityMap.values()) {
                BlockPos at = entity.getBlockPos();
                if (at.getX() < 0 || at.getX() > 15 || at.getY() < 0 || at.getY() > 15 || at.getZ() < 0 || at.getZ() > 15) continue;
                if (entity instanceof PlanariumTile tile) tile.key = null;
                BlockEntityRenderer renderer = dispatcher.getRenderer(entity);
                if (renderer == null || SwingingLanternsCompat.skip(renderer)) continue;
                pose.pushPose();
                try {
                    pose.translate(at.getX(), at.getY(), at.getZ());
                    int light = LevelRenderer.getLightColor(data.fakeRenderingWorld, at);
                    renderer.render(entity, partialTick, pose, HueKeepingBuffers.of(buffers, light), light, OverlayTexture.NO_OVERLAY);
                } catch (RuntimeException e) {
                    LOGGER.debug("[Planarium Panorama] Sub-level block entity skipped", e);
                } finally {
                    pose.popPose();
                }
            }
        }

        @Override
        public void close() {
            buffers.values().forEach(VertexBuffer::close);
            data.vertexBuffers.values().forEach(VertexBuffer::close);
            data.builders.values().forEach(ByteBufferBuilder::close);
            buffers.clear();
            sorting.clear();
        }
    }

    public static void clearOutside() {
        OUTSIDE.values().forEach(scene -> scene.parts.values().forEach(Part::close));
        OUTSIDE.clear();
    }

    /** Release the copies while preserving a moving jar's terrain reference frame. */
    public static void clearStructures() {
        OUTSIDE.values().forEach(scene -> {
            scene.parts.values().forEach(Part::close);
            scene.parts.clear();
        });
        MINIATURES.values().forEach(scene -> scene.parts.values().forEach(Part::close));
        MINIATURES.clear();
    }

    public static void clear() {
        clearOutside();
        MINIATURES.values().forEach(scene -> scene.parts.values().forEach(Part::close));
        MINIATURES.clear();
    }
}

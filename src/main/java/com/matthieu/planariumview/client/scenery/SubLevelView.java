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
    private static int buildsThisFrame;

    static void beginFrame() { buildsThisFrame = 0; }

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
        long revision, lightingRevision = -1;
        Map<Long, SubLevelLighting.Section> lighting = Map.of();

        void changed(CompoundTag geometry) {
            revision++;
            if (geometry == null) return;
            int[] origin = geometry.getIntArray("origin");
            Part changed = null;
            for (Part part : parts.values()) {
                CompoundTag tag = part.pending != null ? part.pending : part.source;
                if (tag != null && Arrays.equals(origin, tag.getIntArray("origin"))) {
                    changed = part;
                    break;
                }
            }
            for (Part part : parts.values()) {
                CompoundTag tag = part.pending != null ? part.pending : part.source;
                if (tag != null && (SubLevelLighting.neighbors(origin, tag.getIntArray("origin"))
                        || changed != null && SubLevelLighting.nearby(part.pose.to, changed.pose.to))) {
                    part.revision = revision;
                }
            }
        }

        Map<Long, SubLevelLighting.Section> lighting() {
            if (lightingRevision == revision) return lighting;
            Map<Long, SubLevelLighting.Section> next = new HashMap<>();
            for (Part part : parts.values()) {
                CompoundTag tag = part.pending != null ? part.pending : part.source;
                if (tag == null) continue;
                if (part.colorTag != tag) {
                    part.colorData = new SubLevelLighting.Section(tag);
                    part.colorTag = tag;
                }
                next.put(net.minecraft.core.SectionPos.of(part.colorData.origin).asLong(), part.colorData);
            }
            lighting = Map.copyOf(next);
            lightingRevision = revision;
            return lighting;
        }

        SubLevelLighting.Projected projected(Part own) {
            lighting();
            List<SubLevelLighting.Frame> frames = new ArrayList<>();
            for (Part part : parts.values()) {
                if (part.colorData != null && part.pose.to != null) {
                    frames.add(new SubLevelLighting.Frame(part.colorData, new Matrix4d(part.pose.to)));
                }
            }
            int[] origin = own.source.getIntArray("origin");
            return SubLevelLighting.project(own.source.getByteArray("light"),
                    new BlockPos(origin[0], origin[1], origin[2]), own.pose.to, frames);
        }
    }

    private static final class Part implements AutoCloseable {
        final Scene scene;
        final Pose pose = new Pose();
        CompoundTag pending, source;
        CompoundTag colorTag;
        SubLevelLighting.Section colorData;
        long revision, generation, preparedRevision = -1;
        Mesh mesh;
        boolean realLight, color;
        final LinkedHashMap<MiniKey, MiniMesh> minis = new LinkedHashMap<>(4, 0.75F, true);

        Part(Scene scene) { this.scene = scene; }

        void prepare() {
            if (pending != null || source != null && (preparedRevision != revision
                    || realLight != Config.realLighting() || color != Config.coloredLight())) {
                if (pending != null) source = pending;
                pending = null;
                realLight = Config.realLighting();
                color = Config.coloredLight();
                preparedRevision = revision;
                generation++;
            }
        }

        Mesh mesh() {
            prepare();
            if ((mesh == null || mesh.generation != generation) && source != null) {
                try {
                    Mesh replacement = new Mesh(source, null, null, null, scene.lighting(), scene.projected(this), generation, mesh);
                    if (mesh != null) mesh.close(mesh.data.fakeRenderingWorld != replacement.data.fakeRenderingWorld);
                    mesh = replacement;
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
            if (cached != null && cached.mesh.generation == generation
                    && Math.abs(now - cached.checked) < 20 && cached.pocket == pocket) return cached.mesh;
            int[] environment = Config.realLighting() ? MiniOutsideLight.measure(tile.getLevel(), tile.getBlockPos()) : null;
            Matrix4d lightPose = pose.current();
            if (cached != null && cached.mesh.generation == generation
                    && cached.pocket == pocket && Arrays.equals(environment, cached.environment)
                    && cached.pose.equals(lightPose, 1.0e-5)) {
                cached.checked = now;
                return cached.mesh;
            }
            try {
                // Give this jar's ambient light to the complete pocket, then sample it in the moving section's frame.
                MiniOutsideLight.apply(pocket, tile.getLevel(), tile.getBlockPos());
                Mesh replacement = new Mesh(source, tile, pocket, lightPose, scene.lighting(), scene.projected(this), generation,
                        cached == null ? null : cached.mesh);
                if (cached != null) cached.mesh.close(cached.mesh.data.fakeRenderingWorld != replacement.data.fakeRenderingWorld);
                minis.put(key, new MiniMesh(replacement, pocket, environment, lightPose, now));
                if (minis.size() > 4) minis.remove(minis.firstEntry().getKey()).mesh.close();
                return replacement;
            } catch (RuntimeException e) {
                LOGGER.warn("[Planarium Panorama] Sub-level miniature could not be rendered", e);
                return cached == null ? null : cached.mesh;
            }
        }

        Mesh cached(PlanariumTile tile) {
            if (tile == null) return mesh;
            MiniMesh mini = minis.get(MiniKey.of(tile));
            return mini == null ? null : mini.mesh;
        }

        boolean needsBuild(PlanariumTile tile) {
            prepare();
            Mesh cached = cached(tile);
            if (cached == null || cached.generation != generation) return source != null;
            if (tile == null) return false;
            MiniMesh mini = minis.get(MiniKey.of(tile));
            return mini.pocket != PlanariumRenderer.structureRenderData.get(tile.key)
                    || Math.abs(tile.getLevel().getGameTime() - mini.checked) >= 20;
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
                Part part = scene.parts.computeIfAbsent(id, ignored -> new Part(scene));
                CompoundTag geometry = tag.getCompound("geometry");
                if (!geometry.equals(part.source) && !geometry.equals(part.pending)) {
                    part.pending = geometry;
                    scene.changed(geometry);
                }
            }
        }
        if (!tag.contains("geometry", Tag.TAG_COMPOUND) && tag.contains("light", Tag.TAG_BYTE_ARRAY)) {
            // Same blocks, new light (a moving structure): rebuild this section with the new light table only.
            Part part = scene.parts.get(tag.getString("id"));
            CompoundTag base = part == null ? null : part.pending != null ? part.pending : part.source;
            if (base != null && !java.util.Arrays.equals(base.getByteArray("light"), tag.getByteArray("light"))) {
                CompoundTag geometry = base.copy();
                geometry.putByteArray("light", tag.getByteArray("light"));
                part.pending = geometry;
                scene.changed(geometry);
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
                scene.parts.computeIfAbsent(id, ignored -> new Part(scene)).pose.receive(SubLevelTransforms.read(frame, "pose"));
            }
            scene.parts.entrySet().removeIf(entry -> {
                if (visible.contains(entry.getKey())) return false;
                Part removed = entry.getValue();
                scene.changed(removed.pending != null ? removed.pending : removed.source);
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

    /** Unscaled flight vertices: sample the moving anchor once for all terrain sections in this frame. */
    static void terrainBlocks(PoseStack stack, BlockPos origin) {
        Scene scene = outside();
        if (scene == null || scene.anchor.to == null) return;
        Matrix4d transform = new Matrix4d().translation(O, O - ClientView.Y_SHIFT, O).scale(ClientView.GIANT)
                .mul(scene.anchor.current().invert()).translate(origin.getX(), origin.getY(), origin.getZ());
        stack.mulPose(new Matrix4f(transform));
    }

    /** Vrai si le bocal a un cadre de reference (Planarium sur une structure Sable). */
    static boolean hasAnchor() {
        Scene scene = outside();
        return scene != null && scene.anchor.to != null;
    }

    static BlockPos anchorPosition() {
        Scene scene = outside();
        if (scene == null || scene.anchor.to == null) return null;
        Matrix4d anchor = scene.anchor.current();
        return BlockPos.containing(anchor.m30(), anchor.m31(), anchor.m32());
    }

    private static Scene outside() {
        var level = Minecraft.getInstance().level;
        return level == null ? null : OUTSIDE.get(level.dimension().location());
    }

    /** Captured terrain coordinates -> current jar coordinates, also when the jar moves. */
    public static Matrix4d sourceFrame(BlockPos origin, int radius) {
        Scene scene = outside();
        return scene == null || scene.anchor.to == null || origin == null
                ? new Matrix4d().translation(-radius, -radius, -radius)
                : scene.anchor.current().invert().translate(origin.getX(), origin.getY(), origin.getZ());
    }

    /** Only the outside scene contributes sound; miniatures stay silent. */
    public static void soundWorlds(java.util.function.BiConsumer<LitRenderingWorld, Matrix4d> consumer) {
        Scene scene = outside();
        if (scene == null || !Config.subLevelViews()) return;
        Matrix4d inverse = scene.anchor.current().invert();
        for (Part part : scene.parts.values()) {
            if (part.mesh == null || part.pose.to == null) continue;
            if (part.mesh.data.fakeRenderingWorld instanceof LitRenderingWorld world) {
                consumer.accept(world, new Matrix4d(inverse).mul(part.pose.current()));
            }
        }
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
        for (Part part : scene.parts.values()) {
            if (part.pose.to == null) continue;
            boolean rebuild = part.needsBuild(tile);
            Mesh mesh;
            if (rebuild && buildsThisFrame >= 2) {
                // Keep drawing and ticking the previous copy until this section gets its turn.
                mesh = part.cached(tile);
            } else {
                if (rebuild) buildsThisFrame++;
                mesh = tile == null ? part.mesh() : part.mini(tile);
            }
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
        final Map<BlockPos, Integer> entityLight = new HashMap<>();
        ColorCompat.Scope colors;
        final double particleScale;
        final long generation;
        final CompoundTag geometry;
        final boolean realLight = Config.realLighting();
        final Map<RenderType, VertexBuffer> buffers = new HashMap<>();
        final Map<RenderType, MeshData.SortState> sorting = new HashMap<>();

        Mesh(CompoundTag tag, PlanariumTile tile, StructureRenderData pocket, Matrix4d lightPose,
             Map<Long, SubLevelLighting.Section> surrounding, SubLevelLighting.Projected projected,
             long generation, Mesh previous) {
            this.generation = generation;
            this.geometry = tag;
            particleScale = tile == null ? ClientView.GIANT : ClientView.ARS_SCALE;
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
            // Neighbor states provide shapes and light occlusion, without creating extra animated block entities.
            LitRenderingWorld world = previous != null && previous.realLight == realLight
                    && SubLevelLighting.sameContents(previous.geometry, tag)
                    ? (LitRenderingWorld) previous.data.fakeRenderingWorld
                    : LitRenderingWorld.giant(level, data.statePosCache);
            world.positions.clear();
            for (CulledStatePos state : neighbors) world.positions.put(state.pos, state.state);
            if (Config.realLighting()) {
                byte[] light = projected.light;
                if (tile != null && pocket != null && pocket.fakeRenderingWorld instanceof LitRenderingWorld fullPocket) {
                    light = miniatureLight(light, fullPocket, lightPose);
                }
                world.setLight(light, 18);
            } else world.setLight(null, 18);
            world.setTintBiome(null);
            if (Config.biomeColors()) {
                ResourceLocation biome = ResourceLocation.tryParse(tag.getString("biome"));
                if (biome != null) level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BIOME)
                        .getHolder(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.BIOME, biome))
                        .ifPresent(world::setTintBiome);
            }
            if (tile != null && Config.biomeColors()) world.setTintBiome(level.getBiome(tile.getBlockPos()));
            data.fakeRenderingWorld = world;
            try {
                int[] origin = tag.getIntArray("origin");
                ColorCompat.begin(origin.length == 3 ? new SubLevelLighting(world,
                        new BlockPos(origin[0], origin[1], origin[2]), surrounding, projected) : world);
                colors = ColorCompat.capture();
                for (BlockEntity entity : world.blockEntityMap.values()) {
                    BlockPos pos = entity.getBlockPos();
                    int packed = LevelRenderer.getLightColor(world, pos);
                    // La case d'un bloc plein (moteur, helice...) vaut souvent 0 dans le plot de Sable : ces rendus
                    // prendraient alors une lumiere noire. Comme pour les faces des blocs, la lumiere des cases autour.
                    int sky = (packed >> 20) & 15, block = (packed >> 4) & 15;
                    for (net.minecraft.core.Direction side : net.minecraft.core.Direction.values()) {
                        BlockPos next = pos.relative(side);
                        sky = Math.max(sky, world.getBrightness(LightLayer.SKY, next));
                        block = Math.max(block, world.getBrightness(LightLayer.BLOCK, next));
                    }
                    packed = (packed & ~0x00F000F0) | block << 4 | sky << 20;
                    if ((packed & ~0x00F000F0) == 0 && (packed & 0xF0) != 0) packed |= FaceHueConsumer.hueAround(world, pos);
                    entityLight.put(pos, packed);
                }
                SceneryParticles.readHues(world, data.statePosCache, false, 0, 0);
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
                close(previous == null || previous.data.fakeRenderingWorld != world);
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
            if (data.fakeRenderingWorld instanceof LitRenderingWorld lit) {
                Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
                CopyParticles.seen(lit, new Matrix4d().translation(camera.x, camera.y, camera.z)
                        .mul(new Matrix4d(pose.last().pose())), particleScale, data.statePosCache);
            }
            ColorCompat.use(colors);
            try {
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
                        int light = entityLight.getOrDefault(at, 0x00F000F0);
                        com.matthieu.planariumview.compat.CreateCopies.render(renderer, entity, data.fakeRenderingWorld,
                                partialTick, pose, HueKeepingBuffers.of(buffers, light), light, OverlayTexture.NO_OVERLAY);
                    } catch (RuntimeException e) {
                        LOGGER.debug("[Planarium Panorama] Sub-level block entity skipped", e);
                    } finally {
                        pose.popPose();
                    }
                }
            } finally { ColorCompat.end(); }
        }

        @Override
        public void close() {
            close(true);
        }

        private void close(boolean releaseWorld) {
            if (releaseWorld) CopyParticles.forget(data.fakeRenderingWorld);
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

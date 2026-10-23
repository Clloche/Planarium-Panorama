package com.matthieu.planariumview.server;

import com.hollingsworth.arsnouveau.common.block.tile.PlanariumTile;
import com.matthieu.planariumview.compat.SubLevelCompat;
import com.matthieu.planariumview.config.Config;
import com.matthieu.planariumview.network.SubLevelPayload;
import com.matthieu.planariumview.network.SubLevelTransforms;
import com.matthieu.planariumview.network.OutsideFluids;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Matrix4d;
import org.joml.Vector3d;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Captures occupied sections separately from poses; moving sections are rebuilt only if their captured data changes. */
public final class SubLevelServer {
    private static final int MAX_PARTS = 256;
    /**
     * Fake origins of contraption sections: far below any world. Each contraption gets its own x range, as wide as its
     * blocks plus a gap (sections closer than 16 blocks count as neighbors on the client, see SubLevelLighting).
     */
    private static final int CONTRAPTION_Y = -1_000_000;
    /** Server time per send call for timed section refreshes (send runs every 2 ticks for each viewer). */
    private static final long REFRESH_BUDGET_NANOS = 2_000_000L;
    private static final int CONTRAPTION_GAP = 64;
    private static final Map<Key, Scene> SCENES = new HashMap<>();
    private static final Map<ResourceKey<Level>, Miniature> MINIATURES = new HashMap<>();
    private static final Set<UUID> DISABLED = new HashSet<>();
    /** Interior of a jar, in pocket coordinates (the miniature's capture area). */
    private static final AABB JAR = new AABB(-1, 0, -1, 33, 34, 33);
    /**
     * Saved Sable structures load a little after their chunks: a pocket is declared empty only after this long
     * without any structure (and rechecked every second meanwhile).
     */
    private static final long EMPTY_AFTER = 200;
    /** Pockets found without structures, with the Ars template version at that time (rechecked when it changes). */
    private static final Map<ResourceKey<Level>, Long> EMPTY = new HashMap<>();
    /** Last dimension change (or login) of each player: geometry sent soon after it is repeated (see send). */
    private static final Map<UUID, Long> DIMENSION_CHANGED = new HashMap<>();
    private static final long RESEND_WINDOW = 200;
    private static final TicketType<ResourceLocation> MINIATURE_TICKET =
            TicketType.create("planariumview_miniature", ResourceLocation::compareTo);

    private static final class Miniature {
        final ServerLevel level;
        final Map<UUID, Long> viewers = new HashMap<>();
        /** Last tick a structure was seen in this pocket (or the creation tick). */
        long lastBody;

        Miniature(ServerLevel level) {
            this.level = level;
            this.lastBody = level.getServer().getTickCount();
            // Sable loads saved bodies only when their physical chunks are block ticking.
            // Four overlapping tickets cover the jar and its one-block collision margin.
            for (int x = 0; x <= 1; x++) for (int z = 0; z <= 1; z++) {
                level.getChunkSource().addRegionTicket(MINIATURE_TICKET, new ChunkPos(x, z), 2,
                        level.dimension().location());
            }
        }

        void close() {
            for (int x = 0; x <= 1; x++) for (int z = 0; z <= 1; z++) {
                level.getChunkSource().removeRegionTicket(MINIATURE_TICKET, new ChunkPos(x, z), 2,
                        level.dimension().location());
            }
        }
    }

    private record Key(UUID player, ResourceLocation dimension, boolean miniature) {
    }

    private static final class Scene {
        final Map<String, Part> parts = new HashMap<>();
        final Map<UUID, Object> pinned = new HashMap<>();
        ServerLevel level;
        ResourceLocation source;
        boolean sent;
        /** Last poses sent: an identical list is not sent again before a one-second safety resend. */
        ListTag lastFrames;
        Matrix4d lastAnchor;
        long lastFramesAt = Long.MIN_VALUE / 2;
        /** Create contraptions seen on this scene's structures: their reserved range of fake origins. */
        final Map<UUID, Slot> slots = new HashMap<>();

        /**
         * Offset added to this contraption's local x for its fake origins. Its range [start, end) never overlaps another
         * contraption's range (with the gap); it is reserved again if the contraption grew past it.
         */
        int offsetX(ContraptionCapture.Moving moving) {
            // Section-aligned extent, with the one-block border of the neighbor tables.
            int min = Math.floorDiv(moving.minX(), 16) * 16 - 16, max = Math.floorDiv(moving.maxX(), 16) * 16 + 32;
            Slot slot = slots.get(moving.id());
            if (slot != null && slot.offset + min >= slot.start && slot.offset + max <= slot.end) return slot.offset;
            slots.remove(moving.id());
            int width = max - min + CONTRAPTION_GAP;
            java.util.List<Slot> taken = new java.util.ArrayList<>(slots.values());
            taken.sort(java.util.Comparator.comparingInt(Slot::start));
            int start = 0;
            for (Slot other : taken) {
                if (start + width <= other.start) break;
                start = Math.max(start, other.end);
            }
            Slot reserved = new Slot(start, start + width, start + CONTRAPTION_GAP / 2 - min);
            slots.put(moving.id(), reserved);
            return reserved.offset;
        }
    }

    private record Slot(int start, int end, int offset) {
    }

    private record Refresh(long captured, Runnable capture) {
    }

    /** Oldest captures go first, so a large scene cannot keep refreshing its first sections forever. */
    private static final class RefreshBudget {
        private final java.util.List<Refresh> pending = new java.util.ArrayList<>();

        void add(long captured, Runnable capture) {
            pending.add(new Refresh(captured, capture));
        }

        void run() {
            pending.sort(java.util.Comparator.comparingLong(Refresh::captured));
            long start = System.nanoTime();
            for (Refresh refresh : pending) {
                refresh.capture().run();
                if (System.nanoTime() - start >= REFRESH_BUDGET_NANOS) break;
            }
        }
    }

    private static final class Part {
        BlockPos origin;
        AABB worldBounds;
        boolean dirty = true;
        long captured = Long.MIN_VALUE / 2;
        CompoundTag previous;
        /** previous without its light: block changes and light changes are sent separately. */
        CompoundTag previousShape;
        Matrix4d capturedPose;
        /** Fake origin of a contraption section at its last capture (moves if its contraption's range moved). */
        BlockPos capturedOrigin;
        /** Last successfully captured contents: a failed capture must be retried on the next update. */
        long capturedSignature;
        long resendAt;
        int resends;
    }

    private SubLevelServer() {
    }

    public static void setEnabled(UUID player, boolean enabled) {
        boolean changed = enabled ? DISABLED.remove(player) : DISABLED.add(player);
        if (changed) forget(player);
    }

    public static void reset(UUID player) {
        forget(player);
        DISABLED.remove(player);
        DIMENSION_CHANGED.remove(player);
    }

    private static boolean enabled(UUID player) {
        return ServerView.enabled(player) && !DISABLED.contains(player);
    }

    /** Called by loaded jars, including jars on Sable plots whose stored position is far from their viewer. */
    public static void seen(PlanariumTile tile) {
        if (tile.key == null || !(tile.getLevel() instanceof ServerLevel level)
                || level.getGameTime() % 20 != 0 || !SubLevelCompat.available()) return;
        ServerLevel pocket = level.getServer().getLevel(tile.key);
        if (pocket == null) return;
        // An empty pocket stays unloaded until Ars updates its template, or a structure shows up in it anyway.
        Long empty = EMPTY.get(tile.key);
        if (empty != null) {
            if (empty == templateVersion(tile.key) && SubLevelCompat.bodies(pocket, JAR).isEmpty()) return;
            EMPTY.remove(tile.key);
        }
        var center = SubLevelCompat.worldPosition(level, net.minecraft.world.phys.Vec3.atCenterOf(tile.getBlockPos()));
        long expires = level.getServer().getTickCount() + 60L;
        for (ServerPlayer player : level.players()) {
            if (!enabled(player.getUUID()) || player.distanceToSqr(center) > 64 * 64
                    || !player.connection.hasChannel(SubLevelPayload.TYPE)) continue;
            MINIATURES.computeIfAbsent(tile.key, ignored -> new Miniature(pocket))
                    .viewers.put(player.getUUID(), expires);
        }
    }

    public static void updateMiniatures(MinecraftServer server) {
        long now = server.getTickCount();
        MINIATURES.entrySet().removeIf(entry -> {
            Miniature miniature = entry.getValue();
            miniature.viewers.entrySet().removeIf(viewer -> {
                ServerPlayer player = server.getPlayerList().getPlayer(viewer.getKey());
                if (viewer.getValue() > now && player != null && enabled(viewer.getKey())) return false;
                SCENES.remove(new Key(viewer.getKey(), entry.getKey().location(), true));
                return true;
            });
            if (miniature.viewers.isEmpty()) {
                miniature.close();
                return true;
            }
            if (now % 20 == 0) {
                if (!SubLevelCompat.bodies(miniature.level, JAR).isEmpty()) {
                    miniature.lastBody = now;
                } else if (now - miniature.lastBody >= EMPTY_AFTER) {
                    // No structure in this jar: release the pocket. Clear the viewers' cached copies first.
                    EMPTY.put(entry.getKey(), templateVersion(entry.getKey()));
                    for (UUID viewer : miniature.viewers.keySet()) {
                        SCENES.remove(new Key(viewer, entry.getKey().location(), true));
                        ServerPlayer player = server.getPlayerList().getPlayer(viewer);
                        if (player != null && player.connection.hasChannel(SubLevelPayload.TYPE)) {
                            frames(player, entry.getKey().location(), true, new ListTag(), new Matrix4d(), now, miniature.level);
                        }
                    }
                    miniature.close();
                    return true;
                }
            }
            miniature.level.resetEmptyTime();
            for (UUID viewer : miniature.viewers.keySet()) {
                ServerPlayer player = server.getPlayerList().getPlayer(viewer);
                if (player != null) send(player, entry.getKey().location(), miniature.level,
                        new AABB(-1, 0, -1, 33, 34, 33), new Matrix4d(), true, null, false);
            }
            return false;
        });
    }

    private static long templateVersion(ResourceKey<Level> pocket) {
        var entry = PlanariumTile.dimManager.entries.get(pocket);
        return entry == null ? Long.MIN_VALUE : entry.lastUpdated();
    }

    /** The client is (re)loading a world: packets sent now can be lost, so recent geometry is repeated. */
    public static void dimensionChanged(UUID player, long tick) {
        DIMENSION_CHANGED.put(player, tick);
        for (var entry : SCENES.entrySet()) {
            if (!entry.getKey().player().equals(player)) continue;
            for (Part part : entry.getValue().parts.values()) {
                if (part.previous == null) continue;
                part.resends = 3;
                part.resendAt = tick + 20;
            }
        }
    }

    public static void dirty(ServerLevel source, BlockPos pos) {
        for (Scene scene : SCENES.values()) {
            if (!source.dimension().location().equals(scene.source)) continue;
            for (Part part : scene.parts.values()) {
                if (part.origin != null && (new AABB(part.origin).inflate(17).contains(pos.getX(), pos.getY(), pos.getZ())
                        || part.worldBounds != null && part.worldBounds.inflate(15).contains(pos.getX(), pos.getY(), pos.getZ()))) {
                    part.dirty = true;
                }
            }
        }
    }

    public static void send(ServerPlayer player, ResourceLocation dimension, ServerLevel source, AABB area,
                            Matrix4d anchor, boolean miniature, BlockPos hidden, boolean active) {
        if (!SubLevelCompat.available() || !player.connection.hasChannel(SubLevelPayload.TYPE)) return;
        long now = source.getServer().getTickCount();
        if (!enabled(player.getUUID())) {
            // OFF removes structure captures, but the terrain must still follow a jar carried by a sub-level.
            boolean movingJar = hidden != null && (anchor.m30() != hidden.getX()
                    || anchor.m31() != hidden.getY() || anchor.m32() != hidden.getZ());
            if (!miniature && (movingJar || now % 20 == 0)) {
                frames(player, dimension, false, new ListTag(), anchor, now, source);
            }
            return;
        }
        Key key = new Key(player.getUUID(), dimension, miniature);
        Scene scene = SCENES.computeIfAbsent(key, ignored -> new Scene());
        scene.level = source;
        scene.source = source.dimension().location();
        int blockSeconds = Config.BLOCK_UPDATE_SECONDS.get();
        long gap = Math.max(5, blockSeconds) * 20L;
        long backup = Config.REFRESH_SECONDS.get() > 0 ? Math.max(60, Config.REFRESH_SECONDS.get()) * 20L : Long.MAX_VALUE;
        Set<String> visible = new HashSet<>();
        ListTag frames = new ListTag();
        Set<UUID> pinned = new HashSet<>();
        // Geometry is sent after the list of visible sections: the client first frees the sections that left
        // (it holds at most 256), then the new geometry always finds its section. See SubLevelView.receive.
        java.util.List<Runnable> geometryPackets = new java.util.ArrayList<>();
        Set<UUID> seenContraptions = new HashSet<>();
        // Timed refreshes share a small budget per call; deferred sections stay due. New sections never wait.
        RefreshBudget refreshBudget = new RefreshBudget();
        for (SubLevelCompat.Body body : SubLevelCompat.bodies(source, area)) {
            if (active) {
                pinned.add(body.id());
                if (scene.pinned.get(body.id()) != body.handle()) {
                    Object previous = scene.pinned.get(body.id());
                    if (previous != null) SubLevelCompat.pin(source, previous, player.getUUID(), false);
                    SubLevelCompat.pin(source, body.handle(), player.getUUID(), true);
                    scene.pinned.put(body.id(), body.handle());
                }
            }
            for (var chunk : body.chunks()) {
                for (int sectionIndex = 0; sectionIndex < chunk.getSectionsCount(); sectionIndex++) {
                    if (frames.size() >= MAX_PARTS) break;
                    var section = chunk.getSection(sectionIndex);
                    if (section.hasOnlyAir()) continue;
                    BlockPos origin = new BlockPos(chunk.getPos().getMinBlockX(),
                            source.getSectionYFromSectionIndex(sectionIndex) << 4, chunk.getPos().getMinBlockZ());
                    AABB localBox = new AABB(origin.getX(), origin.getY(), origin.getZ(),
                            origin.getX() + 16, origin.getY() + 16, origin.getZ() + 16);
                    AABB worldBox = SubLevelTransforms.bounds(body.transform(), localBox);
                    if (!worldBox.intersects(area)) continue;
                    String id = body.id() + "/" + SectionPos.of(origin).asLong();
                    visible.add(id);
                    Part part = scene.parts.computeIfAbsent(id, ignored -> new Part());
                    part.origin = origin;
                    part.worldBounds = worldBox;
                    if (part.capturedPose != null && !part.capturedPose.equals(body.transform())) part.dirty = true;
                    boolean due = now - part.captured >= backup || blockSeconds > 0 && part.dirty && now - part.captured >= gap;
                    Runnable refresh = () -> {
                        exchange(part, capture(source, body, origin, hidden), now, player, dimension, miniature, id, geometryPackets);
                        part.capturedPose = new Matrix4d(body.transform());
                    };
                    if (part.previous == null) refresh.run();
                    else if (due) refreshBudget.add(part.captured, refresh);
                    CompoundTag frame = new CompoundTag();
                    frame.putString("id", id);
                    frame.put("pose", SubLevelTransforms.write(body.at(origin)));
                    frames.add(frame);
                }
            }
            // Create contraptions on this structure (propellers, bearings...): their blocks left the plot.
            for (ContraptionCapture.Moving moving : ContraptionCapture.find(source, body)) {
                seenContraptions.add(moving.id());
                int offsetX = scene.offsetX(moving);
                String groupId = body.id() + "/c" + moving.id();
                Matrix4d group = new Matrix4d(body.transform()).mul(moving.toPlot()).translate(0.5, 0.5, 0.5);
                for (long section : moving.sections()) {
                    if (frames.size() >= MAX_PARTS) break;
                    BlockPos local = new BlockPos(SectionPos.x(section) << 4, SectionPos.y(section) << 4, SectionPos.z(section) << 4);
                    Matrix4d pose = new Matrix4d(body.transform()).mul(moving.toPlot())
                            .translate(local.getX(), local.getY(), local.getZ());
                    AABB worldBox = SubLevelTransforms.bounds(pose, new AABB(0, 0, 0, 16, 16, 16));
                    if (!worldBox.intersects(area)) continue;
                    String id = body.id() + "/c" + moving.id() + "/" + section;
                    visible.add(id);
                    Part part = scene.parts.computeIfAbsent(id, ignored -> new Part());
                    // Unique origin far below the world: never mistaken for a real section of the plot on the client.
                    BlockPos origin = local.offset(offsetX, CONTRAPTION_Y, 0);
                    part.origin = origin;
                    part.worldBounds = worldBox;
                    // The pose changes every update while it turns: only block changes (or the backup) recapture it.
                    boolean urgent = part.previous == null || part.capturedSignature != moving.signature()
                            || !origin.equals(part.capturedOrigin);
                    boolean due = now - part.captured >= backup || blockSeconds > 0 && part.dirty && now - part.captured >= gap;
                    Runnable refresh = () -> {
                        exchange(part, ContraptionCapture.capture(source, body, moving, local, origin),
                                now, player, dimension, miniature, id, geometryPackets);
                        part.capturedOrigin = origin;
                        part.capturedSignature = moving.signature();
                    };
                    if (urgent) refresh.run();
                    else if (due) refreshBudget.add(part.captured, refresh);
                    CompoundTag frame = new CompoundTag();
                    frame.putString("id", id);
                    frame.put("pose", SubLevelTransforms.write(pose));
                    // Shared frame of the whole contraption, centered on its pivot (the middle of its anchor block):
                    // the client interpolates this one rotation, so its sections turn together along the arc instead
                    // of each corner cutting its own chord (the sails wobbled apart). Older clients use "pose".
                    frame.putString("group", groupId);
                    frame.put("group_pose", SubLevelTransforms.write(group));
                    frame.putIntArray("local", new int[]{local.getX(), local.getY(), local.getZ()});
                    frames.add(frame);
                }
            }
        }
        refreshBudget.run();
        for (String id : visible) {
            resend(scene.parts.get(id), now, player, dimension, miniature, id, geometryPackets);
        }
        scene.slots.keySet().retainAll(seenContraptions);
        scene.pinned.entrySet().removeIf(entry -> {
            if (pinned.contains(entry.getKey())) return false;
            SubLevelCompat.pin(source, entry.getValue(), player.getUUID(), false);
            return true;
        });
        scene.parts.keySet().retainAll(visible);
        // A returning viewer can still have a cached miniature after its server scene expired.
        // Repeat empty snapshots once per second, also covering packets lost during a dimension change.
        boolean quiet = !scene.sent && frames.isEmpty() && now % 20 != 0 && (miniature || anchor.equals(new Matrix4d().translation(
                anchor.m30(), anchor.m31(), anchor.m32())));
        // Nothing moved: the client already has this complete list. Resent once per second in case one was lost.
        boolean unchanged = frames.equals(scene.lastFrames) && anchor.equals(scene.lastAnchor) && now - scene.lastFramesAt < 20;
        if (!quiet && !unchanged) {
            frames(player, dimension, miniature, frames, anchor, now, source);
            scene.sent = !frames.isEmpty();
            scene.lastFrames = frames;
            scene.lastAnchor = new Matrix4d(anchor);
            scene.lastFramesAt = now;
        }
        // A skipped list means the client already knows every visible section, including those in these packets.
        geometryPackets.forEach(Runnable::run);
    }

    /** Sends a fresh capture: all of it when its blocks changed, only its light table otherwise. */
    private static void exchange(Part part, CompoundTag geometry, long now, ServerPlayer player, ResourceLocation dimension,
                                 boolean miniature, String id, java.util.List<Runnable> geometryPackets) {
        part.captured = now;
        part.dirty = false;
        CompoundTag shape = geometry.copy();
        shape.remove("light");
        if (part.previous == null || !shape.equals(part.previousShape)) {
            geometryPackets.add(() -> geometry(player, dimension, miniature, id, geometry));
            // Repeated only while the client may still be loading a world (see dimensionChanged).
            Long changed = DIMENSION_CHANGED.get(player.getUUID());
            part.resends = changed != null && now - changed < RESEND_WINDOW ? 3 : 0;
            part.resendAt = now + 20;
        } else if (!Arrays.equals(geometry.getByteArray("light"), part.previous.getByteArray("light"))) {
            // Same blocks, new light (a moving structure): only the light table is sent.
            byte[] light = geometry.getByteArray("light");
            geometryPackets.add(() -> light(player, dimension, miniature, id, light));
        }
        part.previous = geometry;
        part.previousShape = shape;
    }

    private static void resend(Part part, long now, ServerPlayer player, ResourceLocation dimension, boolean miniature,
                               String id, java.util.List<Runnable> geometryPackets) {
        if (part.resends > 0 && now >= part.resendAt) {
            // The first packet can arrive while the client is still changing dimensions.
            CompoundTag repeated = part.previous;
            geometryPackets.add(() -> geometry(player, dimension, miniature, id, repeated));
            part.resends--;
            part.resendAt = now + 20;
        }
    }

    private static void frames(ServerPlayer player, ResourceLocation dimension, boolean miniature, ListTag frames,
                               Matrix4d anchor, long tick, ServerLevel source) {
        CompoundTag packet = new CompoundTag();
        packet.put("frames", frames);
        packet.put("anchor", SubLevelTransforms.write(anchor));
        packet.putLong("tick", tick);
        // An optional NBT field preserves the existing scene protocol for older clients and servers.
        if (!miniature) packet.put("fluids", OutsideFluids.capture(source, anchor));
        PacketDistributor.sendToPlayer(player, new SubLevelPayload(dimension, miniature, packet));
    }

    private static void geometry(ServerPlayer player, ResourceLocation dimension, boolean miniature, String id, CompoundTag geometry) {
        CompoundTag packet = new CompoundTag();
        packet.putString("id", id);
        packet.put("geometry", geometry);
        PacketDistributor.sendToPlayer(player, new SubLevelPayload(dimension, miniature, packet));
    }

    private static void light(ServerPlayer player, ResourceLocation dimension, boolean miniature, String id, byte[] light) {
        CompoundTag packet = new CompoundTag();
        packet.putString("id", id);
        packet.putByteArray("light", light);
        PacketDistributor.sendToPlayer(player, new SubLevelPayload(dimension, miniature, packet));
    }

    private static CompoundTag capture(ServerLevel source, SubLevelCompat.Body body, BlockPos origin, BlockPos hidden) {
        StructureTemplate template = new StructureTemplate();
        template.fillFromWorld(source, origin, new Vec3i(16, 16, 16), false, Blocks.AIR);
        CompoundTag geometry = template.save(new CompoundTag());
        ListTag blocks = geometry.getList("blocks", net.minecraft.nbt.Tag.TAG_COMPOUND);
        if (hidden != null) {
            BlockPos relative = hidden.subtract(origin);
            blocks.removeIf(value -> {
                ListTag pos = ((CompoundTag) value).getList("pos", net.minecraft.nbt.Tag.TAG_INT);
                return pos.size() == 3 && pos.getInt(0) == relative.getX() && pos.getInt(2) == relative.getZ()
                        && (pos.getInt(1) == relative.getY() || pos.getInt(1) == relative.getY() + 1);
            });
        }
        // Include neighbors for face culling at section boundaries, including waterlogged models.
        StructureTemplate border = new StructureTemplate();
        border.fillFromWorld(source, origin.offset(-1, -1, -1), new Vec3i(18, 18, 18), false, Blocks.AIR);
        geometry.put("neighbors", border.save(new CompoundTag()));
        byte[] light = new byte[18 * 18 * 18];
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        Vector3d point = new Vector3d();
        for (int x = -1; x <= 16; x++) for (int y = -1; y <= 16; y++) for (int z = -1; z <= 16; z++) {
            at.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
            body.transform().transformPosition(point.set(at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5));
            BlockPos world = BlockPos.containing(point.x, point.y, point.z);
            int sky = Math.min(source.getBrightness(LightLayer.SKY, at), source.getBrightness(LightLayer.SKY, world));
            int block = Math.max(source.getBrightness(LightLayer.BLOCK, at), source.getBrightness(LightLayer.BLOCK, world));
            light[((x + 1) * 18 + y + 1) * 18 + z + 1] = (byte) (sky << 4 | block);
        }
        geometry.putByteArray("light", light);
        body.transform().transformPosition(point.set(origin.getX() + 8, origin.getY() + 8, origin.getZ() + 8));
        source.getBiome(BlockPos.containing(point.x, point.y, point.z)).unwrapKey().ifPresent(
                key -> geometry.putString("biome", key.location().toString()));
        geometry.putIntArray("origin", new int[]{origin.getX(), origin.getY(), origin.getZ()});
        return geometry;
    }

    public static void forgetOutside(UUID player) {
        forget(player, true);
    }

    public static void forget(UUID player) {
        forget(player, false);
        MINIATURES.entrySet().removeIf(entry -> {
            Miniature miniature = entry.getValue();
            miniature.viewers.remove(player);
            if (!miniature.viewers.isEmpty()) return false;
            miniature.close();
            return true;
        });
    }

    private static void forget(UUID player, boolean outsideOnly) {
        SCENES.entrySet().removeIf(entry -> {
            if (!entry.getKey().player().equals(player) || outsideOnly && entry.getKey().miniature()) return false;
            Scene scene = entry.getValue();
            scene.pinned.values().forEach(body -> SubLevelCompat.pin(scene.level, body, player, false));
            return true;
        });
    }

    public static void clear() {
        SCENES.forEach((key, scene) -> scene.pinned.values().forEach(
                body -> SubLevelCompat.pin(scene.level, body, key.player(), false)));
        SCENES.clear();
        MINIATURES.values().forEach(Miniature::close);
        MINIATURES.clear();
        DISABLED.clear();
        EMPTY.clear();
        DIMENSION_CHANGED.clear();
    }
}

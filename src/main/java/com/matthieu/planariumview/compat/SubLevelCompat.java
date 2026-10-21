package com.matthieu.planariumview.compat;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Optional Sable bridge, also used by Create Aeronautics. No Sable classes are linked by the addon. */
public final class SubLevelCompat {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean initialized;
    private static boolean available;
    private static Method container, all, containing, pose, bake, bounds, plot, localBounds, uniqueId, removed;
    private static Method loadedChunks, chunk, minX, minY, minZ, maxX, maxY, maxZ, worldBounds;
    private static Object visitorTicket;
    private static Method addTicket, removeTicket, allTickets, infoTickets, ticketType, ticketKey;
    private static Method trackingPlayers;

    public record Body(Object handle, UUID id, Matrix4d transform, AABB bounds, BlockPos min, BlockPos max,
                       List<LevelChunk> chunks) {
        public Matrix4d at(BlockPos origin) {
            return new Matrix4d(transform).translate(origin.getX(), origin.getY(), origin.getZ());
        }
    }

    private SubLevelCompat() {
    }

    public static synchronized boolean available() {
        if (!initialized) {
            initialized = true;
            try {
                Class<?> containers = Class.forName("dev.ryanhcode.sable.api.sublevel.SubLevelContainer");
                Class<?> bodies = Class.forName("dev.ryanhcode.sable.sublevel.SubLevel");
                Class<?> plots = Class.forName("dev.ryanhcode.sable.sublevel.plot.LevelPlot");
                Class<?> poses = Class.forName("dev.ryanhcode.sable.companion.math.Pose3dc");
                Class<?> boxes = Class.forName("dev.ryanhcode.sable.companion.math.BoundingBox3ic");
                container = containers.getMethod("getContainer", Level.class);
                all = containers.getMethod("getAllSubLevels");
                containing = containers.getMethod("getPlot", net.minecraft.world.level.ChunkPos.class);
                pose = bodies.getMethod("logicalPose");
                bake = poses.getMethod("bakeIntoMatrix", Matrix4d.class);
                bounds = bodies.getMethod("boundingBox");
                worldBounds = Class.forName("dev.ryanhcode.sable.companion.math.BoundingBox3dc").getMethod("toMojang");
                plot = bodies.getMethod("getPlot");
                uniqueId = bodies.getMethod("getUniqueId");
                removed = bodies.getMethod("isRemoved");
                localBounds = plots.getMethod("getBoundingBox");
                loadedChunks = plots.getMethod("getLoadedChunks");
                chunk = Class.forName("dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder").getMethod("getChunk");
                minX = boxes.getMethod("minX"); minY = boxes.getMethod("minY"); minZ = boxes.getMethod("minZ");
                maxX = boxes.getMethod("maxX"); maxY = boxes.getMethod("maxY"); maxZ = boxes.getMethod("maxZ");
                Class<?> server = Class.forName("dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer");
                Class<?> serverBody = Class.forName("dev.ryanhcode.sable.sublevel.ServerSubLevel");
                trackingPlayers = serverBody.getMethod("getTrackingPlayers");
                Class<?> tickets = Class.forName("dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType");
                visitorTicket = tickets.getMethod("create", net.minecraft.resources.ResourceLocation.class,
                        com.mojang.serialization.Codec.class).invoke(null,
                        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("planariumview", "visitor"),
                        com.mojang.serialization.Codec.STRING);
                addTicket = server.getMethod("addForceLoadTicket", serverBody, tickets, Object.class);
                removeTicket = server.getMethod("removeForceLoadTicket", serverBody, tickets, Object.class);
                allTickets = server.getMethod("getAllTickets");
                infoTickets = Class.forName("dev.ryanhcode.sable.api.sublevel.ticket.SubLevelTicketInfo").getMethod("tickets");
                Class<?> ticket = Class.forName("dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicket");
                ticketType = ticket.getMethod("type");
                ticketKey = ticket.getMethod("key");
                available = true;
                LOGGER.info("[Planarium Panorama] Sable sub-level support enabled");
            } catch (ClassNotFoundException e) {
                if (!"dev.ryanhcode.sable.api.sublevel.SubLevelContainer".equals(e.getMessage())) {
                    LOGGER.warn("[Planarium Panorama] Sable API class is missing; sub-level support disabled", e);
                }
            } catch (ReflectiveOperationException | LinkageError e) {
                LOGGER.warn("[Planarium Panorama] Unsupported Sable API; sub-level support disabled", e);
            }
        }
        return available;
    }

    public static List<Body> bodies(Level level, AABB area) {
        if (!available()) return List.of();
        try {
            Object owner = container.invoke(null, level);
            if (owner == null) return List.of();
            List<Body> result = new ArrayList<>();
            for (Object handle : (Iterable<?>) all.invoke(owner)) {
                if ((boolean) removed.invoke(handle)) continue;
                AABB box = (AABB) worldBounds.invoke(bounds.invoke(handle));
                if (!box.intersects(area)) continue;
                Body body = read(handle, box);
                if (body != null) result.add(body);
            }
            return result;
        } catch (ReflectiveOperationException | LinkageError e) {
            disable(e);
            return List.of();
        }
    }

    /** Plot coordinates are storage coordinates; the pose projects them into the actual world. */
    public static Matrix4d anchor(Level level, BlockPos pos) {
        Matrix4d transform = new Matrix4d();
        if (available()) {
            try {
                Object owner = container.invoke(null, level);
                Object value = owner == null ? null : containing.invoke(owner, new net.minecraft.world.level.ChunkPos(pos));
                if (value != null) {
                    Object body = value.getClass().getMethod("getSubLevel").invoke(value);
                    bake.invoke(pose.invoke(body), transform);
                }
            } catch (ReflectiveOperationException | LinkageError e) {
                disable(e);
            }
        }
        return transform.translate(pos.getX(), pos.getY(), pos.getZ());
    }

    public static Vec3 worldPosition(Level level, Vec3 position) {
        BlockPos block = BlockPos.containing(position);
        org.joml.Vector3d point = anchor(level, block).transformPosition(new org.joml.Vector3d(
                position.x - block.getX(), position.y - block.getY(), position.z - block.getZ()));
        return new Vec3(point.x, point.y, point.z);
    }

    public static void pin(net.minecraft.server.level.ServerLevel level, Object body, UUID player, boolean active) {
        if (active ? !available() : removeTicket == null) return;
        try {
            Object owner = container.invoke(null, level);
            if (owner != null) (active ? addTicket : removeTicket).invoke(owner, body, visitorTicket, player.toString());
        } catch (ReflectiveOperationException | LinkageError e) {
            disable(e);
        }
    }

    /** Sable 2.0.4 sends removals for former viewers to their new dimension, where plot IDs can be reused. */
    public static void forgetTracking(net.minecraft.server.level.ServerLevel previous, UUID player) {
        if (!available()) return;
        try {
            Object owner = container.invoke(null, previous);
            if (owner == null) return;
            for (Object body : (Iterable<?>) all.invoke(owner)) {
                ((Collection<?>) trackingPlayers.invoke(body)).remove(player);
            }
        } catch (ReflectiveOperationException | LinkageError e) {
            disable(e);
        }
    }

    /** Visitor tickets belong to a session, including after a crash; remove stale ones before accepting players. */
    public static void clearVisitorTickets(net.minecraft.server.level.ServerLevel level) {
        if (!available()) return;
        try {
            Object owner = container.invoke(null, level);
            if (owner == null) return;
            var tickets = (java.util.Map<?, ?>) allTickets.invoke(owner);
            for (Object handle : (Iterable<?>) all.invoke(owner)) {
                Object info = tickets.get(uniqueId.invoke(handle));
                if (info == null) continue;
                for (Object ticket : new ArrayList<>((Collection<?>) infoTickets.invoke(info))) {
                    if (visitorTicket.equals(ticketType.invoke(ticket))) {
                        removeTicket.invoke(owner, handle, visitorTicket, ticketKey.invoke(ticket));
                    }
                }
            }
            boolean changed = false;
            for (Object info : tickets.values()) {
                var set = (Collection<?>) infoTickets.invoke(info);
                for (Object ticket : new ArrayList<>(set)) {
                    if (visitorTicket.equals(ticketType.invoke(ticket))) changed |= set.remove(ticket);
                }
            }
            if (changed) {
                Object saved = Class.forName("dev.ryanhcode.sable.sublevel.storage.SubLevelTicketsSavedData")
                        .getMethod("getOrLoad", net.minecraft.server.level.ServerLevel.class).invoke(null, level);
                ((net.minecraft.world.level.saveddata.SavedData) saved).setDirty();
            }
        } catch (ReflectiveOperationException | LinkageError e) {
            disable(e);
        }
    }

    /** Searches both normal terrain and storage plots without loading unrelated chunks. */
    public static BlockPos findPlanarium(Level level, net.minecraft.resources.ResourceKey<Level> pocket, Vec3 near,
                                         double maxDistance) {
        BlockPos best = null;
        double distance = maxDistance * maxDistance;
        for (Body body : bodies(level, new AABB(near, near).inflate(maxDistance))) {
            for (LevelChunk loaded : body.chunks()) {
                for (var entity : loaded.getBlockEntities().values()) {
                    if (entity instanceof com.hollingsworth.arsnouveau.common.block.tile.PlanariumTile tile
                            && pocket.equals(tile.key)) {
                        org.joml.Vector3d point = body.transform().transformPosition(new org.joml.Vector3d(
                                tile.getBlockPos().getX(), tile.getBlockPos().getY(), tile.getBlockPos().getZ()));
                        double candidate = point.distanceSquared(near.x, near.y, near.z);
                        if (candidate < distance) {
                            distance = candidate;
                            best = tile.getBlockPos().immutable();
                        }
                    }
                }
            }
        }
        return best;
    }

    private static Body read(Object handle, AABB box) throws ReflectiveOperationException {
        Object storage = plot.invoke(handle);
        Object local = localBounds.invoke(storage);
        if (local == null) return null;
        List<LevelChunk> chunks = new ArrayList<>();
        for (Object holder : (Collection<?>) loadedChunks.invoke(storage)) {
            chunks.add((LevelChunk) chunk.invoke(holder));
        }
        Matrix4d matrix = new Matrix4d();
        bake.invoke(pose.invoke(handle), matrix);
        return new Body(handle, (UUID) uniqueId.invoke(handle), matrix, box,
                new BlockPos((int) minX.invoke(local), (int) minY.invoke(local), (int) minZ.invoke(local)),
                new BlockPos((int) maxX.invoke(local), (int) maxY.invoke(local), (int) maxZ.invoke(local)), chunks);
    }

    private static void disable(Throwable error) {
        if (available) LOGGER.warn("[Planarium Panorama] Sable bridge failed; sub-level support disabled", error);
        available = false;
    }
}

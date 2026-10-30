package com.matthieu.planariumview.client.scenery;

import com.hollingsworth.arsnouveau.client.renderer.tile.PlanariumRenderer.StructureRenderData;
import com.matthieu.planariumview.config.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.IdentityHashMap;
import java.util.Map;

/** Render-only occlusion of outside mobs. No entity ticks, effects, sounds or network state are changed. */
final class SceneryMobOcclusion {
    private static StructureRenderData source;
    private static int version;
    private static VoxelOcclusion visibility;
    private static Vec3 camera;

    static void prepare(StructureRenderData data, Matrix4f sceneryToCamera) {
        if (!Config.outsideMobs() || FlightView.active() || !SceneryMeshes.pendingSections.isEmpty()
                || !SceneryMeshes.failedSections.isEmpty()) {
            clear();
            return;
        }
        if (source != data || version != SceneryPatcher.sceneryVersion) {
            clear();
            VoxelOcclusion.Grid grid = new VoxelOcclusion.Grid(SceneryState.currentRadius * 2 + 1);
            Map<BlockState, Boolean> solidModels = new IdentityHashMap<>();
            // Only the captured terrain that is actually drawn. Sable meshes and the hidden Planarium cells
            // are absent from this list, and the far ring never supplies occluders.
            for (var entry : data.statePosCache) {
                try {
                    if (occluder(entry.state, data.fakeRenderingWorld, entry.pos)
                            && solidModels.computeIfAbsent(entry.state, SceneryMobOcclusion::solidModel)) {
                        grid.set(entry.pos.getX(), entry.pos.getY(), entry.pos.getZ());
                    }
                } catch (RuntimeException | LinkageError ignored) {
                    // A modded block whose shape/model cannot be inspected is never an occluder.
                }
            }
            visibility = new VoxelOcclusion(grid);
            source = data;
            version = SceneryPatcher.sceneryVersion;
        }
        camera = camera(sceneryToCamera);
        visibility.beginFrame();
    }

    static Vec3 camera(Matrix4f sceneryToCamera) {
        Vector3f point = new Matrix4f(sceneryToCamera).invert().transformPosition(new Vector3f());
        return new Vec3(point.x, point.y, point.z);
    }

    static boolean occluder(BlockState state, BlockGetter world, BlockPos pos) {
        return state.getRenderShape() == RenderShape.MODEL && !state.hasBlockEntity()
                && !(state.getBlock() instanceof LeavesBlock) && !(state.getBlock() instanceof SlabBlock)
                && !(state.getBlock() instanceof StairBlock) && state.isSolidRender(world, pos);
    }

    private static boolean solidModel(BlockState state) {
        var model = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
        boolean solid = false;
        for (var layer : model.getRenderTypes(state, RandomSource.create(0), ModelData.EMPTY)) {
            if (layer != RenderType.solid()) return false;
            solid = true;
        }
        return solid;
    }

    static boolean hidden(Entity entity, AABB bounds) {
        // Custom renderers may extend far beyond their hitbox. Keep a generous render-box margin at the caller.
        // Name tags and leashes can remain visible even when the entire body is behind a wall.
        return visibility != null && entity instanceof LivingEntity && !entity.isCurrentlyGlowing()
                && !entity.hasCustomName() && !entity.shouldShowName() && !(entity instanceof Mob mob && mob.isLeashed())
                && visibility.hidden(entity, camera, bounds);
    }

    static void clear() {
        visibility = null;
        source = null;
        camera = null;
    }
}

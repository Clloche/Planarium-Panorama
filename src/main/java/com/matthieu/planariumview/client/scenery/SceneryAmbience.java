package com.matthieu.planariumview.client.scenery;

import com.hollingsworth.arsnouveau.client.renderer.world.CulledStatePos;
import com.matthieu.planariumview.client.mini.MiniParticles;
import com.matthieu.planariumview.client.scenery.entities.OutsideMobs;
import com.matthieu.planariumview.client.sound.OutsideSoundClient;
import com.matthieu.planariumview.compat.puddle_flood.PuddlesCompat;
import com.matthieu.planariumview.config.Config;
import com.matthieu.planariumview.network.OutsideViewPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.matthieu.planariumview.client.scenery.ClientView.liveArea;
import static com.matthieu.planariumview.client.scenery.ClientView.onSceneryParticle;
import static com.matthieu.planariumview.client.scenery.ClientView.sceneryStates;
import static com.matthieu.planariumview.client.scenery.ClientView.sceneryVersion;
import static com.matthieu.planariumview.client.scenery.ClientView.sceneryWorld;
import static com.matthieu.planariumview.client.scenery.ClientView.viewRadius;
import static com.matthieu.planariumview.client.scenery.ClientView.visibleSceneryPos;
import static com.matthieu.planariumview.client.scenery.SceneryState.currentDim;
import static com.matthieu.planariumview.client.scenery.SceneryState.currentRadius;
import static com.matthieu.planariumview.client.scenery.SceneryState.lastInfo;
import static com.matthieu.planariumview.client.scenery.SceneryState.renderData;

/** Sons et particules d'ambiance des faux mondes, avec leurs caches de blocs et de feux de camp. */
final class SceneryAmbience {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(ClientView.class);

    private SceneryAmbience() {
    }

    private static final net.minecraft.util.RandomSource AMBIENT_RANDOM = net.minecraft.util.RandomSource.create();
    /** Blocs dont l'ambiance a leve une erreur dans le faux monde : on ne les essaie plus. */
    private static final Set<net.minecraft.world.level.block.Block> AMBIENT_BROKEN = new HashSet<>();

    /**
     * Sons d'ambiance des blocs du decor (feu qui crepite, lave, eau qui coule, portail...). Le serveur ne les joue
     * jamais : comme Minecraft autour du joueur, on tire chaque tick des blocs au hasard autour du Planarium dans le
     * faux monde du decor, et on leur fait jouer leur ambiance (les particules sont ignorees).
     */
    static void tickSceneryAmbience(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        com.matthieu.planariumview.client.jar.SubLevelLight.tick();
        OutsideFluidView.tick();
        Minecraft mc = Minecraft.getInstance();
        // Cascades du decor avec les sons de Dynamic Surroundings (integration DS et sons d'ambiance du decor).
        // En vol, le decor normal est cache (voir FlightView) : pas de sons, flaques ni particules a son ancienne place.
        boolean insideJar = mc.level != null && currentDim != null && mc.level.dimension().location().equals(currentDim);
        boolean inJar = insideJar && !FlightView.active();
        com.matthieu.planariumview.client.sound.MachineSounds.tick(inJar ? sceneryWorld() : null, currentRadius,
                lastInfo == null ? null : lastInfo.origin(), insideJar);
        CopyParticles.tick();
        if (!mc.isPaused()) {
            PuddlesCompat.tick(inJar && Config.enabled() ? sceneryWorld() : null, lastInfo);
        }
        com.matthieu.planariumview.client.sound.SceneryWaterfalls.tick(
                !mc.isPaused() && inJar && Config.sceneryAmbience() && Config.outsideAmbience() ? sceneryWorld() : null,
                currentRadius);
        if (!mc.isPaused()) {
            OutsideMobs.tick();
            OutsideMobs.tickSounds(inJar && lastInfo != null ? lastInfo.origin() : null, currentRadius);
            OutsideSoundClient.tick();
            SceneryParticles.tick();
            if (Config.sceneryParticles()) {
                MiniParticles.tick();
            }
        }
        boolean sounds = Config.sceneryAmbience();
        boolean particles = Config.sceneryParticles();
        if (mc.level == null || mc.isPaused() || renderData == null || currentDim == null || (!sounds && !particles)
                || !inJar
                || !(renderData.fakeRenderingWorld instanceof LitRenderingWorld lit)) {
            return;
        }
        int radius = currentRadius;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        LitRenderingWorld.captureAmbientSounds(sounds);
        LitRenderingWorld.captureSceneryParticles(particles
                ? (world, options, x, y, z, vx, vy, vz) -> onSceneryParticle(options, x, y, z, vx, vy, vz) : null);
        try {
            // Comme ClientLevel.animateTick : 667 tirages a 16 blocs et 667 a 32 blocs du point d'ecoute.
            windChecks = particles;
            for (int i = 0; i < 667; i++) {
                ambientTick(lit, radius, 16, pos);
                ambientTick(lit, radius, 32, pos);
            }
            windChecks = false;
            if (particles) {
                SceneryBubbles.tick(lit, radius);
            }
            if (particles) {
                campfireSmoke(lit, radius);
                int view = viewRadius();
                com.matthieu.planariumview.compat.ars_nouveau.ArsParticles.tick(lit, at -> visibleSceneryPos(at, radius, view));
            }
        } finally {
            LitRenderingWorld.captureAmbientSounds(false);
            LitRenderingWorld.captureSceneryParticles(null);
        }
    }

    private static void ambientTick(LitRenderingWorld lit, int radius, int range, BlockPos.MutableBlockPos pos) {
        int x = radius + AMBIENT_RANDOM.nextInt(range) - AMBIENT_RANDOM.nextInt(range);
        int y = radius + AMBIENT_RANDOM.nextInt(range) - AMBIENT_RANDOM.nextInt(range);
        int z = radius + AMBIENT_RANDOM.nextInt(range) - AMBIENT_RANDOM.nextInt(range);
        // Le Planarium lui-meme et le bloc au-dessus ne font pas partie du decor.
        if (x == radius && z == radius && (y == radius || y == radius + 1)) {
            return;
        }
        pos.set(x, y, z);
        BlockState state = lit.getBlockState(pos);
        // Ambiance du biome et rafales de Windy, y compris dans l'air : ne pas passer par animateBlock.
        if (windChecks && !state.isCollisionShapeFullBlock(lit, pos)) {
            SceneryAmbientParticles.tick(lit, pos, AMBIENT_RANDOM);
            OutsideViewPayload info = lastInfo;
            com.matthieu.planariumview.compat.windy.WindyCompat.tryWind(lit, pos.immutable(), radius,
                    info != null ? info.origin() : null, AMBIENT_RANDOM);
        }
        if (state.isAir() || AMBIENT_BROKEN.contains(state.getBlock())) {
            return;
        }
        // Jets de bulles de Dynamic Surroundings (integration DS).
        if (windChecks && Config.outsideAmbience()) {
            SceneryBubbles.tryStart(lit, pos, state);
            OutsideViewPayload info = lastInfo;
            com.matthieu.planariumview.compat.dynamic_surroundings.DsFirefliesCompat.animate(lit, pos, state, AMBIENT_RANDOM,
                    info != null ? info.origin() : BlockPos.ZERO);
        }
        animateBlock(lit, pos.immutable(), state);
    }

    /** Vrai pendant le tirage des blocs du decor si les particules du decor sont actives (biomes et vent). */
    private static boolean windChecks;

    /**
     * Ambiance d'un bloc d'un faux monde (decor geant ou miniature), comme ClientLevel.doAnimateTick : celle du bloc,
     * de son liquide, et les gouttes sous un plafond de liquide.
     */
    static void animateBlock(LitRenderingWorld lit, BlockPos at, BlockState state) {
        if (state.isAir() || AMBIENT_BROKEN.contains(state.getBlock())) {
            return;
        }
        boolean leaves = false;
        try {
            // Feuilles de Falling Leaves (integration).
            leaves = com.matthieu.planariumview.compat.falling_leaves.FallingLeavesCompat.animate(lit, at, state, AMBIENT_RANDOM);
            state.getBlock().animateTick(state, lit, at, AMBIENT_RANDOM);
            FluidState fluid = state.getFluidState();
            if (!fluid.isEmpty()) {
                // Sable annule les particules de WaterFluid quand le monde n'a pas son conteneur d'occlusion.
                // Le decor n'en a pas : reproduire la branche de particules vanilla directement dans ce monde.
                // L'eau courante conserve ses sons natifs ; les fluides des mods gardent leur animateTick.
                boolean vanillaWater = fluid.getType() == net.minecraft.world.level.material.Fluids.WATER
                        || fluid.getType() == net.minecraft.world.level.material.Fluids.FLOWING_WATER;
                if (vanillaWater && (fluid.isSource() || fluid.getValue(net.minecraft.world.level.material.FlowingFluid.FALLING))) {
                    if (AMBIENT_RANDOM.nextInt(10) == 0) {
                        lit.addParticle(net.minecraft.core.particles.ParticleTypes.UNDERWATER,
                                at.getX() + AMBIENT_RANDOM.nextDouble(), at.getY() + AMBIENT_RANDOM.nextDouble(),
                                at.getZ() + AMBIENT_RANDOM.nextDouble(), 0, 0, 0);
                    }
                } else {
                    fluid.animateTick(lit, at, AMBIENT_RANDOM);
                }
                // Gouttes sous un plafond de lave ou d'eau (ClientLevel.doAnimateTick, pas le bloc lui-meme).
                net.minecraft.core.particles.ParticleOptions drip = fluid.getDripParticle();
                if (drip != null && AMBIENT_RANDOM.nextInt(10) == 0) {
                    boolean sturdy = state.isFaceSturdy(lit, at, net.minecraft.core.Direction.DOWN);
                    BlockPos below = at.below();
                    dripUnder(lit, below, lit.getBlockState(below), drip, sturdy);
                }
            }
        } catch (Throwable t) {
            if (leaves && t instanceof ClassCastException) {
                // Falling Leaves sur un feuillage (voir LeavesBlockMixin) : ses feuilles sont deja faites.
                return;
            }
            // Un bloc modde qui suppose un vrai monde : on l'ignore pour le reste de la session.
            AMBIENT_BROKEN.add(state.getBlock());
            LOGGER.debug("[Planarium Panorama] Ambiance du bloc {} ignoree dans le decor", state.getBlock(), t);
        }
    }

    /** Comme ClientLevel.trySpawnDripParticles : une goutte qui pend sous le bloc sous le liquide. */
    private static void dripUnder(LitRenderingWorld lit, BlockPos pos, BlockState state,
                                  net.minecraft.core.particles.ParticleOptions drip, boolean sturdy) {
        if (!state.getFluidState().isEmpty()) {
            return;
        }
        net.minecraft.world.phys.shapes.VoxelShape shape = state.getCollisionShape(lit, pos);
        double top = shape.max(net.minecraft.core.Direction.Axis.Y);
        if (top < 1.0) {
            if (sturdy) {
                dripAt(lit, pos.getX(), pos.getX() + 1, pos.getZ(), pos.getZ() + 1, pos.getY() + 1 - 0.05, drip);
            }
        } else if (!state.is(net.minecraft.tags.BlockTags.IMPERMEABLE)) {
            double bottom = shape.min(net.minecraft.core.Direction.Axis.Y);
            if (bottom > 0.0) {
                dripUnder(lit, pos, shape, pos.getY() + bottom - 0.05, drip);
            } else {
                BlockPos below = pos.below();
                BlockState under = lit.getBlockState(below);
                if (under.getCollisionShape(lit, below).max(net.minecraft.core.Direction.Axis.Y) < 1.0
                        && under.getFluidState().isEmpty()) {
                    dripUnder(lit, pos, shape, pos.getY() - 0.05, drip);
                }
            }
        }
    }

    private static void dripUnder(LitRenderingWorld lit, BlockPos pos, net.minecraft.world.phys.shapes.VoxelShape shape,
                                  double y, net.minecraft.core.particles.ParticleOptions drip) {
        dripAt(lit, pos.getX() + shape.min(net.minecraft.core.Direction.Axis.X),
                pos.getX() + shape.max(net.minecraft.core.Direction.Axis.X),
                pos.getZ() + shape.min(net.minecraft.core.Direction.Axis.Z),
                pos.getZ() + shape.max(net.minecraft.core.Direction.Axis.Z), y, drip);
    }

    private static void dripAt(LitRenderingWorld lit, double x1, double x2, double z1, double z2, double y,
                               net.minecraft.core.particles.ParticleOptions drip) {
        lit.addParticle(drip, net.minecraft.util.Mth.lerp(AMBIENT_RANDOM.nextDouble(), x1, x2), y,
                net.minecraft.util.Mth.lerp(AMBIENT_RANDOM.nextDouble(), z1, z2), 0.0, 0.0, 0.0);
    }

    private static int campfireVersion = -1;
    private static List<BlockPos> campfires = List.of();

    /**
     * Fumee des feux de camp allumes du decor. Minecraft la fait chaque tick depuis le feu de camp lui-meme
     * (CampfireBlockEntity.particleTick), pas depuis les blocs tires au hasard : on refait la meme chose.
     */
    private static void campfireSmoke(LitRenderingWorld lit, int radius) {
        if (campfireVersion != sceneryVersion()) {
            campfireVersion = sceneryVersion();
            campfires = findCampfires(sceneryStates());
        }
        int view = viewRadius();
        for (BlockPos at : campfires) {
            if (!liveArea(at.getX() - radius, at.getZ() - radius, view)) {
                continue;
            }
            campfireTick(lit, at, lit.getBlockState(at));
        }
    }

    /** Positions des feux de camp parmi ces blocs (allumes ou non). */
    static List<BlockPos> findCampfires(Iterable<CulledStatePos> states) {
        List<BlockPos> found = new ArrayList<>();
        for (CulledStatePos statePos : states) {
            BlockState state = statePos.state;
            if (state != null && state.getBlock() instanceof net.minecraft.world.level.block.CampfireBlock
                    && state.hasProperty(net.minecraft.world.level.block.CampfireBlock.LIT)) {
                found.add(statePos.pos.immutable());
            }
        }
        return found;
    }

    /** Un tick de fumee d'un feu de camp allume, comme CampfireBlockEntity.particleTick. */
    static void campfireTick(LitRenderingWorld lit, BlockPos at, BlockState state) {
        if (!(state.getBlock() instanceof net.minecraft.world.level.block.CampfireBlock)
                || !state.getValue(net.minecraft.world.level.block.CampfireBlock.LIT)
                || AMBIENT_RANDOM.nextFloat() >= 0.11F) {
            return;
        }
        boolean signal = state.getValue(net.minecraft.world.level.block.CampfireBlock.SIGNAL_FIRE);
        int count = AMBIENT_RANDOM.nextInt(2) + 2;
        try {
            for (int i = 0; i < count; i++) {
                net.minecraft.world.level.block.CampfireBlock.makeParticles(lit, at, signal, false);
            }
        } catch (Throwable t) {
            LOGGER.debug("[Planarium Panorama] Fumee de feu de camp ignoree dans le decor", t);
        }
    }
}

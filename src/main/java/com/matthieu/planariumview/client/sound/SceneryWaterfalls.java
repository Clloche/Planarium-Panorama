package com.matthieu.planariumview.client.sound;

import com.matthieu.planariumview.client.scenery.LitRenderingWorld;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.SupportType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Cascades du decor geant avec les sons de Dynamic Surroundings. DS remplace le bruit de l'eau qui coule de Minecraft par
 * ses propres sons de cascade, mais seulement pour les vraies cascades autour du joueur : on refait la meme chose pour les
 * cascades du decor, comme si on ecoutait a la place du Planarium. Memes regles que DS (WaterfallColumn) : de l'eau qui
 * tombe arrive sur un bloc d'eau source ou un bloc plein, avec de la place sur un cote ; la hauteur de la chute choisit le
 * son, et seules les cascades qui comptent le plus jouent.
 */
public final class SceneryWaterfalls {
    /** Portee des sons de cascade de DS (attenuation_distance de ses sons). */
    private static final double RANGE = 22.0;
    private static final double RANGE_SQR = RANGE * RANGE;
    /** Nombre de cascades entendues en meme temps. */
    private static final int MAX_SOUNDS = 8;
    private static final int MAX_STRENGTH = 10;
    private static final RandomSource RANDOM = RandomSource.create();
    /** Son de DS pour chaque hauteur de chute (comme WaterfallSounds de DS). */
    private static final String[] TIERS = {"waterfall.0", "waterfall.1", "waterfall.2", "waterfall.3", "waterfall.3",
            "waterfall.4", "waterfall.4", "waterfall.5", "waterfall.5", "waterfall.5", "waterfall.5"};

    /** Le scan initial est partage entre les ticks pour ne pas bloquer une image. */
    private static final long WORK_BUDGET_NANOS = 1_000_000L;
    private static final int MAX_SCAN_STEPS_PER_TICK = 2048;
    private static final int MAX_DIRTY_STEPS_PER_TICK = 512;
    private static final List<Offset> SCAN_OFFSETS = buildScanOffsets();

    private static final Map<Long, Loop> PLAYING = new HashMap<>();
    /** Cascades trouvees, indexees par leur position dans le faux monde. */
    private static final Map<Long, Waterfall> FOUND = new HashMap<>();
    /** Positions a reevaluer apres les changements partiels du decor, dedoublonnees. */
    private static final ArrayDeque<Long> DIRTY_QUEUE = new ArrayDeque<>();
    private static final Set<Long> DIRTY_SET = new HashSet<>();

    private static LitRenderingWorld activeWorld;
    private static int activeRadius;
    private static int scanCursor;
    private static boolean scanInProgress;
    private static boolean reconcilePending;

    private SceneryWaterfalls() {
    }

    /**
     * Chaque tick client. Un nouveau faux monde ou rayon demande un scan complet time-slice; les deltas de blocs
     * actualisent ensuite seulement les cascades voisines (voir blocksChanged).
     */
    public static void tick(LitRenderingWorld world, int radius) {
        if (world == null) {
            stopAll();
            return;
        }
        if (world != activeWorld || radius != activeRadius) {
            boolean radiusChanged = activeWorld != null && radius != activeRadius;
            beginScan(world, radius, radiusChanged);
        }

        if (scanInProgress) {
            processScan();
        }
        if (!scanInProgress) {
            processDirtyCandidates();
            if (reconcilePending && DIRTY_QUEUE.isEmpty()) {
                reconcilePending = false;
                reconcilePlayingSounds();
            }
        }
    }

    /**
     * Signale un delta apres sa pleine application au faux monde. Une cascade peut dependre du bloc modifie, de ses
     * voisins horizontaux, du bloc de support et d'une colonne d'eau de 10 blocs au-dessus.
     */
    public static void blocksChanged(LitRenderingWorld world, int radius, Iterable<BlockPos> changedBlocks) {
        if (world == null || world != activeWorld || radius != activeRadius || changedBlocks == null) {
            return;
        }
        boolean added = false;
        for (BlockPos changed : changedBlocks) {
            if (changed == null) {
                continue;
            }
            added |= enqueueCandidate(changed);
            added |= enqueueCandidate(changed.below());
            added |= enqueueCandidate(changed.above());
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                added |= enqueueCandidate(changed.relative(direction));
            }
            for (int y = 1; y <= MAX_STRENGTH; y++) {
                added |= enqueueCandidate(changed.below(y));
            }
        }
        reconcilePending |= added;
    }

    public static void stopAll() {
        stopPlayingSounds();
        FOUND.clear();
        DIRTY_QUEUE.clear();
        DIRTY_SET.clear();
        activeWorld = null;
        activeRadius = 0;
        scanCursor = 0;
        scanInProgress = false;
        reconcilePending = false;
    }

    private static void beginScan(LitRenderingWorld world, int radius, boolean radiusChanged) {
        // Un changement de rayon deplace toutes les sources relatives au Planarium; ne gardons pas les anciennes directions.
        if (radiusChanged) {
            stopPlayingSounds();
        }
        activeWorld = world;
        activeRadius = radius;
        scanCursor = 0;
        scanInProgress = true;
        reconcilePending = true;
        FOUND.clear();
        DIRTY_QUEUE.clear();
        DIRTY_SET.clear();
    }

    private static void processScan() {
        long start = System.nanoTime();
        int processed = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        while (scanCursor < SCAN_OFFSETS.size() && processed < MAX_SCAN_STEPS_PER_TICK
                && (processed == 0 || System.nanoTime() - start < WORK_BUDGET_NANOS)) {
            Offset offset = SCAN_OFFSETS.get(scanCursor++);
            int x = activeRadius + offset.x();
            int y = activeRadius + offset.y();
            int z = activeRadius + offset.z();
            pos.set(x, y, z);
            Waterfall waterfall = findAt(activeWorld, activeRadius, pos, offset.distance());
            if (waterfall != null) {
                FOUND.put(waterfall.key(), waterfall);
            }
            processed++;
        }
        scanInProgress = scanCursor < SCAN_OFFSETS.size();
    }

    private static void processDirtyCandidates() {
        long start = System.nanoTime();
        int processed = 0;
        while (!DIRTY_QUEUE.isEmpty() && processed < MAX_DIRTY_STEPS_PER_TICK
                && (processed == 0 || System.nanoTime() - start < WORK_BUDGET_NANOS)) {
            long key = DIRTY_QUEUE.removeFirst();
            DIRTY_SET.remove(key);
            BlockPos pos = BlockPos.of(key);
            double dx = pos.getX() - activeRadius;
            double dy = pos.getY() - activeRadius;
            double dz = pos.getZ() - activeRadius;
            double distanceSqr = dx * dx + dy * dy + dz * dz;
            if (distanceSqr >= RANGE_SQR) {
                FOUND.remove(key);
            } else {
                Waterfall waterfall = findAt(activeWorld, activeRadius, pos, Math.sqrt(distanceSqr));
                if (waterfall == null) {
                    FOUND.remove(key);
                } else {
                    FOUND.put(key, waterfall);
                }
            }
            processed++;
        }
    }

    private static boolean enqueueCandidate(BlockPos pos) {
        double dx = pos.getX() - activeRadius;
        double dy = pos.getY() - activeRadius;
        double dz = pos.getZ() - activeRadius;
        if (dx * dx + dy * dy + dz * dz >= RANGE_SQR) {
            return false;
        }
        long key = pos.asLong();
        if (!DIRTY_SET.add(key)) {
            return false;
        }
        DIRTY_QUEUE.addLast(key);
        return true;
    }

    private static void reconcilePlayingSounds() {
        PriorityQueue<Waterfall> top = new PriorityQueue<>(Comparator.comparingDouble(Waterfall::weight));
        for (Waterfall waterfall : FOUND.values()) {
            if (top.size() < MAX_SOUNDS) {
                top.add(waterfall);
            } else if (waterfall.weight() > top.peek().weight()) {
                top.poll();
                top.add(waterfall);
            }
        }
        Map<Long, Waterfall> wanted = new HashMap<>();
        for (Waterfall waterfall : top) {
            wanted.put(waterfall.key(), waterfall);
        }

        PLAYING.entrySet().removeIf(entry -> {
            Waterfall waterfall = wanted.get(entry.getKey());
            Loop loop = entry.getValue();
            if (waterfall == null || waterfall.strength() != loop.strength || loop.isStopped()) {
                loop.end();
                return true;
            }
            return false;
        });
        for (Waterfall waterfall : wanted.values()) {
            if (PLAYING.containsKey(waterfall.key())) {
                continue;
            }
            Loop loop = new Loop(waterfall);
            PLAYING.put(waterfall.key(), loop);
            Minecraft.getInstance().getSoundManager().play(loop);
        }
    }

    private static void stopPlayingSounds() {
        PLAYING.values().forEach(Loop::end);
        PLAYING.clear();
    }

    private static List<Offset> buildScanOffsets() {
        List<Offset> offsets = new ArrayList<>();
        int reach = (int) Math.ceil(RANGE);
        for (int x = -reach; x <= reach; x++) {
            for (int z = -reach; z <= reach; z++) {
                for (int y = -reach; y <= reach; y++) {
                    double distanceSqr = (double) x * x + (double) y * y + (double) z * z;
                    if (distanceSqr < RANGE_SQR) {
                        offsets.add(new Offset(x, y, z, Math.sqrt(distanceSqr)));
                    }
                }
            }
        }
        return List.copyOf(offsets);
    }

    private record Offset(int x, int y, int z, double distance) {
    }

    private record Waterfall(long key, double dx, double dy, double dz, int strength, double distance) {
        double weight() {
            double loudness = strength + 2;
            return loudness * loudness / Math.max(1.0, distance * distance);
        }
    }

    private static Waterfall findAt(LitRenderingWorld world, int radius, BlockPos pos, double distance) {
        try {
            BlockState state = world.getBlockState(pos);
            if (!state.getFluidState().is(FluidTags.WATER) || !isLandingSite(world, pos)) {
                return null;
            }
            int strength = strength(world, pos);
            if (strength <= 0) {
                return null;
            }
            return new Waterfall(pos.asLong(), pos.getX() - radius, pos.getY() - radius, pos.getZ() - radius,
                    strength, distance);
        } catch (Exception ignored) {
            // bloc particulier : ignore
            return null;
        }
    }

    private static boolean isFalling(FluidState fluid) {
        return fluid.hasProperty(FlowingFluid.FALLING) && fluid.getValue(FlowingFluid.FALLING);
    }

    private static boolean isLandingSite(LitRenderingWorld world, BlockPos pos) {
        if (!isFalling(world.getFluidState(pos.above()))) {
            return false;
        }
        boolean spreads = false;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockState side = world.getBlockState(pos.relative(direction));
            int amount = side.getFluidState().getAmount();
            if (side.isAir() || (amount > 0 && amount < FluidState.AMOUNT_FULL)) {
                spreads = true;
                break;
            }
        }
        if (!spreads) {
            return false;
        }
        BlockPos below = pos.below();
        BlockState under = world.getBlockState(below);
        return under.getFluidState().isSource() || under.isFaceSturdy(world, below, Direction.UP, SupportType.FULL);
    }

    private static int strength(LitRenderingWorld world, BlockPos pos) {
        BlockPos.MutableBlockPos at = pos.mutable();
        int count = 0;
        while (count < MAX_STRENGTH && isFalling(world.getFluidState(at.move(0, 1, 0)))) {
            count++;
        }
        return count;
    }

    /** Son en boucle d'une cascade : vient de sa direction dans le decor, plus faible de loin. */
    private static final class Loop extends AbstractTickableSoundInstance {
        final int strength;
        private final double dx;
        private final double dy;
        private final double dz;
        private final float level;

        Loop(Waterfall waterfall) {
            super(SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath("dsurround",
                    TIERS[Mth.clamp(waterfall.strength(), 0, TIERS.length - 1)])), SoundSource.AMBIENT, RANDOM);
            this.strength = waterfall.strength();
            this.dx = waterfall.dx();
            this.dy = waterfall.dy();
            this.dz = waterfall.dz();
            this.level = (float) (1.0 - waterfall.distance() / RANGE) * OutsideSoundClient.AMBIENCE_VOLUME;
            this.looping = true;
            this.delay = 0;
            this.pitch = 0.8F + RANDOM.nextFloat() * 0.4F;
            this.volume = this.level;
            this.attenuation = SoundInstance.Attenuation.NONE;
            place();
        }

        private void place() {
            Vec3 at = OutsideSoundClient.playbackPosition(this.dx, this.dy, this.dz);
            this.x = at.x;
            this.y = at.y;
            this.z = at.z;
        }

        @Override
        public void tick() {
            // La camera bouge : le son reste dans la direction de la cascade.
            place();
        }

        void end() {
            this.stop();
        }
    }
}

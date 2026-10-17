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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Cascades du decor geant avec les sons de Dynamic Surroundings. DS remplace le bruit de l'eau qui coule de Minecraft par
 * ses propres sons de cascade, mais seulement pour les vraies cascades autour du joueur : on refait la meme chose pour les
 * cascades du decor, comme si on ecoutait a la place du Planarium. Memes regles que DS (WaterfallColumn) : de l'eau qui
 * tombe arrive sur un bloc d'eau source ou un bloc plein, avec de la place sur un cote ; la hauteur de la chute choisit
 * le son, et seules les cascades qui comptent le plus jouent. Recherchees seulement quand le decor change.
 */
public final class SceneryWaterfalls {
    /** Portee des sons de cascade de DS (attenuation_distance de ses sons). */
    private static final double RANGE = 22.0;
    /** Nombre de cascades entendues en meme temps. */
    private static final int MAX_SOUNDS = 8;
    private static final int MAX_STRENGTH = 10;
    private static final RandomSource RANDOM = RandomSource.create();
    /** Son de DS pour chaque hauteur de chute (comme WaterfallSounds de DS). */
    private static final String[] TIERS = {"waterfall.0", "waterfall.1", "waterfall.2", "waterfall.3", "waterfall.3",
            "waterfall.4", "waterfall.4", "waterfall.5", "waterfall.5", "waterfall.5", "waterfall.5"};

    private static final Map<Long, Loop> PLAYING = new HashMap<>();
    /** Decor deja parcouru (voir tick). */
    private static Object scannedWorld;
    private static int scannedVersion = Integer.MIN_VALUE;
    private static int scannedRadius;

    private SceneryWaterfalls() {
    }

    /**
     * Chaque tick client. {@code world} : le decor geant, ou null pour tout arreter. {@code version} change a chaque
     * changement du decor : les cascades ne sont recherchees qu'a ce moment-la (le decor ne bouge pas entre deux).
     */
    public static void tick(LitRenderingWorld world, int radius, int version) {
        if (world == null) {
            stopAll();
            scannedWorld = null;
            return;
        }
        if (world == scannedWorld && version == scannedVersion && radius == scannedRadius) {
            return;
        }
        scannedWorld = world;
        scannedVersion = version;
        scannedRadius = radius;
        List<Waterfall> found = scan(world, radius);
        found.sort((a, b) -> Double.compare(b.weight(), a.weight()));
        Map<Long, Waterfall> wanted = new HashMap<>();
        for (int i = 0; i < Math.min(MAX_SOUNDS, found.size()); i++) {
            wanted.put(found.get(i).key(), found.get(i));
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

    public static void stopAll() {
        if (PLAYING.isEmpty()) {
            return;
        }
        PLAYING.values().forEach(Loop::end);
        PLAYING.clear();
        scannedWorld = null;
    }

    private record Waterfall(long key, double dx, double dy, double dz, int strength, double distance) {
        double weight() {
            double loudness = strength + 2;
            return loudness * loudness / Math.max(1.0, distance * distance);
        }
    }

    /** Cascades a portee du Planarium dans le decor (positions du gabarit, Planarium au rayon {@code radius}). */
    private static List<Waterfall> scan(LitRenderingWorld world, int radius) {
        List<Waterfall> found = new ArrayList<>();
        int reach = (int) Math.ceil(RANGE);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = radius - reach; x <= radius + reach; x++) {
            for (int z = radius - reach; z <= radius + reach; z++) {
                for (int y = radius - reach; y <= radius + reach; y++) {
                    double dx = x - radius;
                    double dy = y - radius;
                    double dz = z - radius;
                    double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (distance >= RANGE) {
                        continue;
                    }
                    pos.set(x, y, z);
                    try {
                        BlockState state = world.getBlockState(pos);
                        if (!state.getFluidState().is(FluidTags.WATER) || !isLandingSite(world, pos)) {
                            continue;
                        }
                        int strength = strength(world, pos);
                        if (strength > 0) {
                            found.add(new Waterfall(pos.asLong(), dx, dy, dz, strength, distance));
                        }
                    } catch (Exception ignored) {
                        // bloc particulier : ignore
                    }
                }
            }
        }
        return found;
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

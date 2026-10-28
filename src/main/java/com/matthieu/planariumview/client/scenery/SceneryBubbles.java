package com.matthieu.planariumview.client.scenery;

import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.SupportType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Jets de bulles de Dynamic Surroundings dans l'eau du decor geant, avec les memes regles que DS (UnderwaterBubbleProducer
 * et BubbleJetEffect) : un bloc d'eau sur un fond plein, une petite chance a chaque bloc tire au hasard, puis des bulles
 * de Minecraft qui montent pendant quelques secondes, plus vite et plus longtemps sous plus d'eau.
 */
final class SceneryBubbles {
    /** Chance de DS (dsconfigs/blocks.json, "bubble_column"). */
    private static final double CHANCE = 0.0005;
    private static final int MAX_JETS = 64;
    private static final int FREQUENCY = 3;
    private static final RandomSource RANDOM = RandomSource.create();
    private static final List<Jet> JETS = new ArrayList<>();

    private SceneryBubbles() {
    }

    private static final class Jet {
        final BlockPos pos;
        final int strength;
        final int ageLimit;
        int age;

        Jet(BlockPos pos, int strength) {
            this.pos = pos;
            this.strength = strength;
            this.ageLimit = (RANDOM.nextInt(strength) + 2) * 20;
        }
    }

    /** Un bloc du decor tire au hasard pour l'ambiance : peut-etre un nouveau jet de bulles. */
    static void tryStart(LitRenderingWorld world, BlockPos pos, BlockState state) {
        if (!state.getFluidState().is(FluidTags.WATER) || RANDOM.nextDouble() >= CHANCE || JETS.size() >= MAX_JETS) {
            return;
        }
        BlockPos below = pos.below();
        if (!world.getBlockState(below).isFaceSturdy(world, below, Direction.UP, SupportType.FULL)) {
            return;
        }
        for (Jet jet : JETS) {
            if (jet.pos.equals(pos)) {
                return;
            }
        }
        // Force : l'eau au-dessus (au plus 10 blocs, comme DS).
        int strength = 0;
        BlockPos.MutableBlockPos at = pos.mutable();
        while (strength < 10 && world.getBlockState(at).getFluidState().is(FluidTags.WATER)) {
            strength++;
            at.move(0, 1, 0);
        }
        if (strength > 0) {
            JETS.add(new Jet(pos.immutable(), strength));
        }
    }

    /** Chaque tick : les jets en cours font monter leurs bulles. */
    static void tick(LitRenderingWorld world, int radius) {
        if (JETS.isEmpty()) {
            return;
        }
        if (world == null) {
            JETS.clear();
            return;
        }
        SceneryParticles.inScenery(world, radius, SceneryHooks.sceneryFrame(), space -> {
            Iterator<Jet> it = JETS.iterator();
            while (it.hasNext()) {
                Jet jet = it.next();
                if (jet.age % FREQUENCY == 0 && SceneryParticles.allowed()) {
                    int light = LightTexture.pack(world.getBrightness(LightLayer.BLOCK, jet.pos),
                            world.getBrightness(LightLayer.SKY, jet.pos));
                    SceneryParticles.spawn(space, ParticleTypes.BUBBLE, jet.pos.getX() + 0.5, jet.pos.getY() + 0.1,
                            jet.pos.getZ() + 0.5, 0.0, 0.5 + jet.strength / 10.0, 0.0, light);
                }
                if (++jet.age >= jet.ageLimit) {
                    it.remove();
                }
            }
        });
    }

    static void clear() {
        JETS.clear();
    }
}

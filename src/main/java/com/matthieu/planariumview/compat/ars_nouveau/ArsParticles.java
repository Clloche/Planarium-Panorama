package com.matthieu.planariumview.compat.ars_nouveau;

import com.hollingsworth.arsnouveau.client.particle.GlowParticleData;
import com.hollingsworth.arsnouveau.client.particle.ParticleColor;
import com.hollingsworth.arsnouveau.client.particle.ParticleGlow;
import com.hollingsworth.arsnouveau.client.particle.ParticleLine;
import com.hollingsworth.arsnouveau.client.particle.ParticleLineData;
import com.hollingsworth.arsnouveau.client.particle.ParticleSparkle;
import com.hollingsworth.arsnouveau.client.particle.ParticleUtil;
import com.hollingsworth.arsnouveau.common.block.tile.ImbuementTile;
import com.hollingsworth.arsnouveau.common.block.tile.LightTile;
import com.hollingsworth.arsnouveau.common.block.tile.ParticleTile;
import com.hollingsworth.arsnouveau.common.block.tile.SconceTile;
import com.hollingsworth.arsnouveau.common.block.tile.WhirlisprigTile;
import com.matthieu.planariumview.client.scenery.LitRenderingWorld;
import com.matthieu.planariumview.client.scenery.SceneryParticles;
import com.mojang.logging.LogUtils;
import net.minecraft.client.particle.Particle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Predicate;

/** Effets clients d'Ars dans les copies du decor et de la miniature, sans simulation des machines. */
public final class ArsParticles {
    private static final ParticleColor WHIRLISPRIG_COLOR = new ParticleColor(50, 255, 20);
    private static final ParticleColor IMBUEMENT_COLOR = new ParticleColor(255, 25, 180);
    /** Une configuration d'effet invalide ne doit pas faire echouer chaque tick ni retenir son ancien monde. */
    private static final Set<BlockEntity> FAILED = Collections.newSetFromMap(new WeakHashMap<>());
    /** Une copie peut servir a plusieurs Planariums : avancer son emetteur une fois, puis rejouer dans chaque vue. */
    private static final Map<BlockEntity, List<Emission>> EMISSIONS = new IdentityHashMap<>();

    private record Emission(ParticleOptions options, double x, double y, double z, double vx, double vy, double vz) {
        void replay(LitRenderingWorld world) {
            world.addParticle(options, x, y, z, vx, vy, vz);
        }
    }

    private ArsParticles() {
    }

    /** A appeler pendant la capture des particules du faux monde, une fois par tick et par vue. */
    public static void tick(LitRenderingWorld world, Predicate<BlockPos> visible) {
        if (!world.isClientSide) return;
        // Seulement les blocs-entites copies : pas de recherche dans les chunks ni de scan du volume du decor.
        for (BlockEntity copy : world.blockEntityMap.values()) {
            if (!(copy instanceof LightTile || copy instanceof SconceTile || copy instanceof ParticleTile
                    || copy instanceof WhirlisprigTile || copy instanceof ImbuementTile)) continue;
            BlockPos pos = copy.getBlockPos();
            if (copy.isRemoved() || FAILED.contains(copy) || !visible.test(pos)) continue;
            try {
                List<Emission> emissions = EMISSIONS.get(copy);
                if (emissions == null) {
                    List<Emission> captured = new ArrayList<>();
                    LitRenderingWorld.withSceneryParticles((source, options, x, y, z, vx, vy, vz) ->
                            captured.add(new Emission(options, x, y, z, vx, vy, vz)), () -> {
                        if (copy instanceof WhirlisprigTile flower) {
                            whirlisprig(world, pos, flower.progress);
                        } else if (copy instanceof ImbuementTile chamber) {
                            imbuement(world, pos, chamber.draining);
                        } else {
                            light(world, copy, pos);
                        }
                    });
                    EMISSIONS.put(copy, captured);
                    emissions = captured;
                }
                for (Emission emission : emissions) emission.replay(world);
            } catch (Exception | LinkageError error) {
                FAILED.add(copy);
                LogUtils.getLogger().debug("[Planarium Panorama] Effet Ars ignore pour {} en {}",
                        copy.getClass().getName(), pos, error);
            }
        }
    }

    private static void light(LitRenderingWorld world, BlockEntity copy, BlockPos pos) {
        // Ars attache normalement ses copies au vrai monde. Sconce/Magelight lisent ce champ dans tick().
        // Le changer seulement pendant l'emission garantit que toutes les particules passent par notre capture.
        Level previous = copy.getLevel();
        copy.setLevel(world);
        try {
            if (copy instanceof LightTile light) {
                light.tick(world, copy.getBlockState(), pos);
            } else if (copy instanceof SconceTile sconce) {
                sconce.tick();
            } else if (copy instanceof ParticleTile particle) {
                particle.tick(world, copy.getBlockState(), pos);
            }
        } finally {
            copy.setLevel(previous);
        }
    }

    private static void whirlisprig(LitRenderingWorld world, BlockPos pos, int progress) {
        for (int i = 0; i < progress / 20; i++) {
            world.addParticle(GlowParticleData.createData(WHIRLISPRIG_COLOR),
                    pos.getX() + 0.5 + ParticleUtil.inRange(-0.1, 0.1),
                    pos.getY() + 1.3 + ParticleUtil.inRange(-0.1, 0.1),
                    pos.getZ() + 0.5 + ParticleUtil.inRange(-0.1, 0.1), 0, 0, 0);
        }
    }

    private static void imbuement(LitRenderingWorld world, BlockPos pos, boolean draining) {
        // Seulement l'emission du tick client d'Ars : pas de getRecipeNow() dans le faux monde.
        int baseAge = draining ? 20 : 40;
        int bound = draining ? 3 : 6;
        int count = draining ? 2 : 1;
        float size = (float) (draining ? ParticleUtil.inRange(0.1, 0.2) : ParticleUtil.inRange(0.05, 0.15));
        if (world.random.nextInt(bound) != 0) return;
        Vec3 center = pos.getCenter();
        for (int i = 0; i < count; i++) {
            Vec3 start = center.add(ParticleUtil.pointInSphere());
            world.addParticle(ParticleLineData.createData(IMBUEMENT_COLOR, size, baseAge + world.random.nextInt(20)),
                    start.x, start.y, start.z, center.x, center.y, center.z);
        }
    }

    /** Classes dont la taille et le mouvement sont adaptes ci-dessous. Les autres styles gardent le filtre miniature. */
    public static boolean supported(Particle particle) {
        Class<?> type = particle.getClass();
        return type == ParticleGlow.class || type == ParticleLine.class || type == ParticleSparkle.class;
    }

    /** Apres Particle.scale : conserver aussi la taille reutilisee par les ticks d'Ars. */
    public static void scale(Particle particle, SceneryParticles.Space space) {
        float scale = (float) space.scale();
        if (particle.getClass() == ParticleGlow.class) {
            ((ParticleGlow) particle).initScale *= scale;
        } else if (particle.getClass() == ParticleLine.class) {
            ParticleLine line = (ParticleLine) particle;
            line.initScale *= scale;
            // initX/Y/Z viennent deja du point de naissance transforme par SceneryParticles.spawn.
            // Les trois arguments de vitesse d'une ParticleLine sont en realite sa destination dans le gabarit.
            line.destX = (float) (line.destX * space.scale() + space.ox());
            line.destY = (float) (line.destY * space.scale() + space.oy());
            line.destZ = (float) (line.destZ * space.scale() + space.oz());
        } else if (particle.getClass() == ParticleSparkle.class) {
            ((ParticleSparkle) particle).initScale *= scale;
        }
    }

    public static void clear() {
        beginTick();
        FAILED.clear();
    }

    public static void beginTick() {
        EMISSIONS.clear();
    }
}

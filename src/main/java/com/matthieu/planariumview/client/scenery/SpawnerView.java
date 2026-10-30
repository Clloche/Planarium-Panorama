package com.matthieu.planariumview.client.scenery;

import com.matthieu.planariumview.mixin.BaseSpawnerAccessor;
import com.matthieu.planariumview.mixin.TrialSpawnerDataAccessor;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BaseSpawner;
import net.minecraft.world.level.block.TrialSpawnerBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.entity.TrialSpawnerBlockEntity;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Predicate;

/**
 * Spawners du decor geant et des miniatures : le mob qui tourne dans la cage, et leur fumee et leurs flammes. Ambiance
 * seulement : rien n'est active dans le monde d'origine, aucun mob n'apparait, aucune epreuve n'est inventee.
 *
 * <ul>
 * <li>Spawner classique : comme vanilla cote client, il s'anime si celui qui regarde est a sa portee. Ici, celui qui
 * regarde est le Planarium (decor geant) ou le centre du bocal (miniature), en blocs du monde copie.</li>
 * <li>Trial spawner : son etat copie (bloc) decide, avec son propre traitement client (particules et sons d'ambiance ;
 * le faux monde du decor fait entendre les sons, celui de la miniature les ignore).</li>
 * </ul>
 *
 * Une copie peut servir a plusieurs Planariums (meme bocal) : elle avance une fois par tick, puis ses emissions sont
 * rejouees dans chaque vue, comme ArsParticles. Les rotations sont gardees d'une copie a la suivante (nouvel
 * instantane) pour ne pas repartir de zero.
 */
public final class SpawnerView {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final RandomSource RANDOM = RandomSource.create();
    /** Delai visuel quand le compteur copie arrive a zero : sans le serveur, il ne serait jamais remis a neuf. */
    private static final int RESET_DELAY = 400;

    private record Emission(ParticleOptions options, double x, double y, double z, double vx, double vy, double vz) {
    }

    private record Saved(double spin, int delay, boolean trial) {
    }

    /** Emissions de chaque copie pendant ce tick (rejouees si la copie sert encore dans une autre vue). */
    private static final Map<BlockEntity, List<Emission>> EMISSIONS = new IdentityHashMap<>();
    private static final Set<BlockEntity> FAILED = Collections.newSetFromMap(new WeakHashMap<>());
    /** Rotation par vue ({@code scope}) et position, pour la reprendre dans la copie d'un nouvel instantane. */
    private static final Map<String, Map<BlockPos, Saved>> SAVED =
            new java.util.LinkedHashMap<>(16, 0.75F, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Map<BlockPos, Saved>> eldest) {
                    return size() > 64;
                }
            };
    /** Copies deja reprises depuis SAVED. */
    private static final Set<BlockEntity> RESTORED = Collections.newSetFromMap(new WeakHashMap<>());

    private SpawnerView() {
    }

    /**
     * Un tick d'une vue. {@code viewer} : point d'observation (coordonnees du monde copie), {@code visible} : positions
     * dont on montre les particules. A appeler pendant la capture des particules et sons de cette vue.
     */
    public static void tick(LitRenderingWorld world, String scope, Vec3 viewer, Predicate<BlockPos> visible) {
        tick(world, world.blockEntityMap.values(), scope, viewer, visible);
    }

    /** Les effets peuvent utiliser un autre faux monde ; les compteurs appartiennent aux copies dessinees. */
    public static void tick(LitRenderingWorld world, Iterable<BlockEntity> copies, String scope, Vec3 viewer,
                            Predicate<BlockPos> visible) {
        Map<BlockPos, Saved> saved = SAVED.computeIfAbsent(scope, ignored -> new HashMap<>());
        Set<BlockPos> present = new java.util.HashSet<>();
        for (BlockEntity copy : copies) {
            if (!(copy instanceof SpawnerBlockEntity || copy instanceof TrialSpawnerBlockEntity)) continue;
            if (copy.isRemoved() || FAILED.contains(copy)) continue;
            BlockPos pos = copy.getBlockPos();
            present.add(pos);
            if (!visible.test(pos)) continue;
            try {
                List<Emission> emissions = EMISSIONS.get(copy);
                if (emissions == null) {
                    restore(copy, saved.get(pos));
                    List<Emission> captured = new ArrayList<>();
                    LitRenderingWorld.withSceneryParticles((source, options, x, y, z, vx, vy, vz) ->
                            captured.add(new Emission(options, x, y, z, vx, vy, vz)), () -> {
                        if (copy instanceof SpawnerBlockEntity spawner) {
                            classic(world, pos, spawner.getSpawner(), viewer);
                        } else if (copy instanceof TrialSpawnerBlockEntity trial) {
                            trial.getTrialSpawner().tickClient(world, pos,
                                    copy.getBlockState().getOptionalValue(TrialSpawnerBlock.OMINOUS).orElse(false));
                        }
                    });
                    saved.put(pos.immutable(), save(copy));
                    EMISSIONS.put(copy, captured);
                    emissions = captured;
                }
                for (Emission emission : emissions) {
                    world.addParticle(emission.options(), emission.x(), emission.y(), emission.z(),
                            emission.vx(), emission.vy(), emission.vz());
                }
            } catch (Exception | LinkageError error) {
                FAILED.add(copy);
                LOGGER.debug("[Planarium Panorama] Spawner copie ignore en {}", pos, error);
            }
        }
        saved.keySet().retainAll(present);
    }

    /** BaseSpawner.clientTick, avec le point d'observation de la vue au lieu des joueurs du monde (vide pour une copie). */
    private static void classic(LitRenderingWorld world, BlockPos pos, BaseSpawner spawner, Vec3 viewer) {
        BaseSpawnerAccessor access = (BaseSpawnerAccessor) spawner;
        double spin = access.planariumview$getSpin();
        access.planariumview$setOSpin(spin);
        int range = access.planariumview$getRequiredPlayerRange();
        if (viewer == null || viewer.distanceToSqr(pos.getCenter()) > (double) range * range) return;
        if (spawner.getOrCreateDisplayEntity(world, pos) == null) return;
        double x = pos.getX() + RANDOM.nextDouble();
        double y = pos.getY() + RANDOM.nextDouble();
        double z = pos.getZ() + RANDOM.nextDouble();
        world.addParticle(ParticleTypes.SMOKE, x, y, z, 0, 0, 0);
        world.addParticle(ParticleTypes.FLAME, x, y, z, 0, 0, 0);
        int delay = access.planariumview$getSpawnDelay();
        delay = delay > 0 ? delay - 1 : RESET_DELAY;
        access.planariumview$setSpawnDelay(delay);
        access.planariumview$setSpin((spin + 1000.0F / (delay + 200.0F)) % 360.0);
    }

    private static Saved save(BlockEntity copy) {
        if (copy instanceof SpawnerBlockEntity spawner) {
            BaseSpawnerAccessor access = (BaseSpawnerAccessor) spawner.getSpawner();
            return new Saved(access.planariumview$getSpin(), access.planariumview$getSpawnDelay(), false);
        }
        TrialSpawnerDataAccessor data = (TrialSpawnerDataAccessor) ((TrialSpawnerBlockEntity) copy).getTrialSpawner().getData();
        return new Saved(data.planariumview$getSpin(), 0, true);
    }

    /** Nouvelle copie (nouvel instantane) : reprend la rotation de la precedente au meme endroit. */
    private static void restore(BlockEntity copy, Saved saved) {
        if (!RESTORED.add(copy) || saved == null || saved.trial() != (copy instanceof TrialSpawnerBlockEntity)) return;
        if (copy instanceof SpawnerBlockEntity spawner) {
            BaseSpawnerAccessor access = (BaseSpawnerAccessor) spawner.getSpawner();
            access.planariumview$setSpin(saved.spin());
            access.planariumview$setOSpin(saved.spin());
            access.planariumview$setSpawnDelay(saved.delay());
        } else if (copy instanceof TrialSpawnerBlockEntity trial) {
            TrialSpawnerDataAccessor data = (TrialSpawnerDataAccessor) trial.getTrialSpawner().getData();
            data.planariumview$setSpin(saved.spin());
            data.planariumview$setOSpin(saved.spin());
        }
    }

    /** Debut de chaque tick client (voir SceneryParticles.tick). */
    public static void beginTick() {
        EMISSIONS.clear();
    }

    /** Sortie du bocal, deconnexion : plus de rotations gardees ni de copies retenues. */
    public static void clear() {
        EMISSIONS.clear();
        FAILED.clear();
        SAVED.clear();
        RESTORED.clear();
    }
}

package com.matthieu.planariumview.client.mini;

import com.matthieu.planariumview.client.jar.JarWalls;
import com.matthieu.planariumview.client.scenery.ClientView;
import com.matthieu.planariumview.client.scenery.LitRenderingWorld;
import com.matthieu.planariumview.client.scenery.SceneryParticles;
import com.matthieu.planariumview.config.Config;

import com.hollingsworth.arsnouveau.client.renderer.tile.PlanariumRenderer;
import com.hollingsworth.arsnouveau.client.renderer.tile.PlanariumRenderer.StructureRenderData;
import com.hollingsworth.arsnouveau.client.renderer.world.CulledStatePos;
import com.hollingsworth.arsnouveau.common.block.tile.PlanariumTile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Particules des miniatures : comme le decor geant (SceneryParticles), mais 32 fois plus petites, dans la miniature
 * du bocal qu'Ars dessine dans le Planarium. Seulement pour les Planariums dessines recemment et proches du joueur.
 */
public final class MiniParticles {
    private static final RandomSource RANDOM = RandomSource.create();
    /** Planariums dessines : position -> (bocal, lumiere a cet endroit, tick du dernier dessin). */
    private static final Map<BlockPos, Seen> SEEN = new HashMap<>();
    /** Faux monde de chaque miniature, pour y faire jouer l'ambiance des blocs. */
    private static final Map<StructureRenderData, Mini> MINIS = Collections.synchronizedMap(new WeakHashMap<>());
    /**
     * Densite approximative des animations pres d'un joueur vanilla. Le nombre de tirages depend du volume occupe,
     * pour ne pas faire jouer une torche seule des centaines de fois par tick dans un bocal presque vide.
     */
    private static final double PICKS_PER_BLOCK = 0.1;
    /** Budget historique par miniature : ne pas le doubler quand le bocal est plein. Ce n'est pas un quota de particules. */
    private static final int MAX_PICKS = 667;
    /**
     * Interieur entier du bocal, dans le gabarit d'Ars qui commence en (0, 1, 0). La fumee doit pouvoir monter dans
     * l'air au-dessus des blocs presents ; la boite utilisee pour les tirages n'est pas une limite de deplacement.
     */
    private static final AABB PARTICLE_BOUNDS = new AABB(JarWalls.MIN + 1, JarWalls.MIN_Y + 1, JarWalls.MIN + 1,
            JarWalls.MAX, JarWalls.MAX_Y, JarWalls.MAX).move(0.0, -1.0, 0.0);
    /** Distance maximale entre le joueur et le Planarium. */
    private static final double RANGE = 24.0;
    /** Centre de l'interieur du bocal, dans le gabarit (y du gabarit = y du bocal - 1). */
    private static final net.minecraft.world.phys.Vec3 MINI_VIEWER = new net.minecraft.world.phys.Vec3(16.0, 15.0, 16.0);
    private static final int MAX_MINIS = 4;
    private static long ticks;

    private record Seen(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> key, int light, long tick) {
    }

    private record Mini(LitRenderingWorld world, AABB sampleBounds, List<BlockPos> campfires) {
    }

    private MiniParticles() {
    }

    /** Chaque fois qu'Ars dessine un Planarium (voir PlanariumRendererMixin). */
    public static void seen(PlanariumTile tile, int packedLight) {
        if (tile == null || tile.key == null || !Config.enabled() || !Config.sceneryParticles()) {
            return;
        }
        SEEN.put(tile.getBlockPos().immutable(), new Seen(tile.key, packedLight, ticks));
    }

    /** Chaque tick client (hors pause). */
    public static void tick() {
        ticks++;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || SEEN.isEmpty()) {
            SEEN.clear();
            return;
        }
        net.minecraft.world.phys.Vec3 eye = mc.player.getEyePosition();
        List<Map.Entry<BlockPos, Seen>> near = new ArrayList<>();
        SEEN.entrySet().removeIf(entry -> ticks - entry.getValue().tick() > 20);
        for (Map.Entry<BlockPos, Seen> entry : SEEN.entrySet()) {
            if (entry.getKey().getCenter().distanceToSqr(eye) <= RANGE * RANGE) {
                near.add(entry);
            }
        }
        near.sort(java.util.Comparator.comparingDouble((Map.Entry<BlockPos, Seen> entry) -> entry.getKey().getCenter().distanceToSqr(eye)));
        int done = 0;
        for (Map.Entry<BlockPos, Seen> entry : near) {
            if (done++ >= MAX_MINIS) {
                break;
            }
            StructureRenderData data = PlanariumRenderer.structureRenderData.get(entry.getValue().key());
            if (data == null || data.statePosCache == null) {
                continue;
            }
            Mini mini = MINIS.computeIfAbsent(data, MiniParticles::create);
            if (mini != null) {
                animate(mc, entry.getKey(), entry.getValue(), data, mini);
            }
        }
    }

    private static Mini create(StructureRenderData data) {
        try {
            LitRenderingWorld world = data.fakeRenderingWorld instanceof LitRenderingWorld lit ? lit
                    : new LitRenderingWorld(Minecraft.getInstance().level, data.statePosCache);
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (CulledStatePos statePos : data.statePosCache) {
                BlockPos pos = statePos.pos;
                minX = Math.min(minX, pos.getX());
                minY = Math.min(minY, pos.getY());
                minZ = Math.min(minZ, pos.getZ());
                maxX = Math.max(maxX, pos.getX());
                maxY = Math.max(maxY, pos.getY());
                maxZ = Math.max(maxZ, pos.getZ());
            }
            if (minX > maxX) {
                return null;
            }
            return new Mini(world, new AABB(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1),
                    ClientView.findCampfires(data.statePosCache));
        } catch (Exception e) {
            return null;
        }
    }

    /** Repere des particules d'une miniature : 32 fois plus petites, dans le Planarium a {@code tile}. */
    private static SceneryParticles.Space space(BlockPos tile, Seen seen, StructureRenderData data, Mini mini) {
        float scale = ClientView.ARS_SCALE;
        // Lumiere : celle du bocal (avec sa couleur) si la vraie lumiere de la miniature est connue, sans depasser le
        // ciel de l'endroit du Planarium ; sinon celle de l'endroit du Planarium.
        LitRenderingWorld lit = Config.realLighting() && data.fakeRenderingWorld instanceof LitRenderingWorld world ? world : null;
        int tileLight = seen.light();
        int tileBlock = LightTexture.block(tileLight);
        int tileSky = LightTexture.sky(tileLight);
        java.util.function.IntUnaryOperator adjust = lit == null ? packed -> tileLight : packed -> {
            int block = Math.max((packed >> 4) & 15, tileBlock);
            int sky = Math.min((packed >> 20) & 15, tileSky);
            return (packed & ~0x00F000F0) | block << 4 | sky << 20;
        };
        return new SceneryParticles.Space(lit != null ? lit : mini.world(), scale,
                tile.getX() + ClientView.ARS_OFFSET, tile.getY() + ClientView.ARS_OFFSET + 26 * scale,
                tile.getZ() + ClientView.ARS_OFFSET, PARTICLE_BOUNDS, lit != null, adjust);
    }

    private static void animate(Minecraft mc, BlockPos tile, Seen seen, StructureRenderData data, Mini mini) {
        int tileLight = seen.light();
        SceneryParticles.Space space = space(tile, seen, data, mini);
        LitRenderingWorld.captureSceneryParticles((world, options, x, y, z, vx, vy, vz) -> {
            if (options == null || !SceneryParticles.allowed()) {
                return;
            }
            SceneryParticles.spawn(space, options, x, y, z, vx, vy, vz, tileLight);
        });
        SceneryParticles.animatingMini(true);
        try {
            AABB bounds = mini.sampleBounds();
            int sizeX = (int) bounds.getXsize();
            int sizeY = (int) bounds.getYsize();
            int sizeZ = (int) bounds.getZsize();
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            // Densite stable dans les petites boites, plafonnee au budget de la miniature.
            double expected = PICKS_PER_BLOCK * sizeX * sizeY * sizeZ;
            int picks = Math.min(MAX_PICKS, (int) expected + (RANDOM.nextDouble() < expected - (int) expected ? 1 : 0));
            for (int i = 0; i < picks; i++) {
                pos.set((int) bounds.minX + RANDOM.nextInt(sizeX), (int) bounds.minY + RANDOM.nextInt(sizeY),
                        (int) bounds.minZ + RANDOM.nextInt(sizeZ));
                BlockState state = mini.world().getBlockState(pos);
                if (!state.isAir()) {
                    ClientView.animateBlock(mini.world(), pos.immutable(), state);
                    com.matthieu.planariumview.compat.dynamic_surroundings.DsFirefliesCompat.animate(mini.world(), pos, state, RANDOM, tile);
                }
            }
            for (BlockPos at : mini.campfires()) {
                ClientView.campfireTick(mini.world(), at, mini.world().getBlockState(at));
            }
            com.matthieu.planariumview.compat.ars_nouveau.ArsParticles.tick(mini.world(),
                    at -> PARTICLE_BOUNDS.contains(at.getCenter()));
            // Spawners du bocal, vus depuis son centre (en blocs du bocal) : voir SpawnerView.
            if (data.fakeRenderingWorld != null) {
                com.matthieu.planariumview.client.scenery.SpawnerView.tick(mini.world(),
                        data.fakeRenderingWorld.blockEntityMap.values(), "mini:" + seen.key().location(),
                        MINI_VIEWER, at -> PARTICLE_BOUNDS.contains(at.getCenter()));
            }
        } finally {
            LitRenderingWorld.captureSceneryParticles(null);
            SceneryParticles.animatingMini(false);
        }
    }

    /**
     * Particule d'un mob de la miniature (effets de potion, portail des endermen, dauphins, encre...), position et
     * vitesse dans le gabarit du bocal, comme les mobs vivants (voir MiniEntities). Seulement pour un Planarium dessine
     * recemment et proche, dans le budget de particules de Minecraft.
     */
    public static void mobParticle(BlockPos tile, ParticleOptions options, double x, double y, double z,
                                   double vx, double vy, double vz) {
        SceneryParticles.Space space = mobSpace(tile);
        if (space != null && options != null && SceneryParticles.allowed()) {
            SceneryParticles.spawn(space, options, x, y, z, vx, vy, vz, SEEN.get(tile).light());
        }
    }

    /** Les vingt nuages d'un mob qui disparait apres sa mort, comme vanilla (LivingEntity.makePoofParticles). */
    public static void mobDeath(BlockPos tile, double x, double y, double z, float width, float height) {
        SceneryParticles.Space space = mobSpace(tile);
        if (space == null) return;
        int light = SEEN.get(tile).light();
        for (int i = 0; i < 20; i++) {
            if (!SceneryParticles.allowed()) continue;
            SceneryParticles.spawn(space, ParticleTypes.POOF, x + (RANDOM.nextDouble() * 2 - 1) * width,
                    y + RANDOM.nextDouble() * height, z + (RANDOM.nextDouble() * 2 - 1) * width,
                    RANDOM.nextGaussian() * 0.02, RANDOM.nextGaussian() * 0.02, RANDOM.nextGaussian() * 0.02, light);
        }
    }

    private static SceneryParticles.Space mobSpace(BlockPos tile) {
        if (tile == null || !Config.sceneryParticles()) return null;
        Seen seen = SEEN.get(tile);
        if (seen == null) return null;
        StructureRenderData data = PlanariumRenderer.structureRenderData.get(seen.key());
        if (data == null || data.statePosCache == null) return null;
        Mini mini = MINIS.computeIfAbsent(data, MiniParticles::create);
        return mini == null ? null : space(tile, seen, data, mini);
    }

    /** Pendant la construction de la miniature (Contraption Lights lit son faux monde) : voir SceneryParticles.readHues. */
    public static void readHues(StructureRenderData data) {
        if (Config.sceneryParticles() && Config.realLighting() && data.statePosCache != null
                && data.fakeRenderingWorld instanceof LitRenderingWorld lit) {
            SceneryParticles.readHues(lit, data.statePosCache, false, 0, 0);
        }
    }

    /**
     * Vrai si ce point est dans un Planarium dont on fait les particules de miniature. Rien du vrai monde n'y nait (le
     * bloc est plein) : une particule qui y apparait vient d'un bloc de la miniature, par un chemin qu'on ne voit pas.
     */
    public static boolean covers(double x, double y, double z) {
        return !SEEN.isEmpty() && SEEN.containsKey(BlockPos.containing(x, y, z));
    }

    public static void clear() {
        SEEN.clear();
        MINIS.clear();
    }
}

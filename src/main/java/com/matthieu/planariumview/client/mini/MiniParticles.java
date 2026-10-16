package com.matthieu.planariumview.client.mini;

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
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LightLayer;
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
    /** Blocs tires au hasard par miniature et par tick (Minecraft en tire 2 x 667 autour du joueur). */
    private static final int PICKS = 667;
    /** Distance maximale entre le joueur et le Planarium. */
    private static final double RANGE = 24.0;
    private static final int MAX_MINIS = 4;
    private static long ticks;

    private record Seen(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> key, int light, long tick) {
    }

    private record Mini(LitRenderingWorld world, AABB bounds, List<BlockPos> campfires) {
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

    private static void animate(Minecraft mc, BlockPos tile, Seen seen, StructureRenderData data, Mini mini) {
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
        SceneryParticles.Space space = new SceneryParticles.Space(lit != null ? lit : mini.world(), scale,
                tile.getX() + ClientView.ARS_OFFSET, tile.getY() + ClientView.ARS_OFFSET + 26 * scale,
                tile.getZ() + ClientView.ARS_OFFSET, mini.bounds(), lit != null, adjust);
        LitRenderingWorld.captureSceneryParticles((world, options, x, y, z, vx, vy, vz) -> {
            if (options == null || !SceneryParticles.allowed()) {
                return;
            }
            SceneryParticles.spawn(space, options, x, y, z, vx, vy, vz, tileLight);
        });
        SceneryParticles.animatingMini(true);
        try {
            AABB bounds = mini.bounds();
            int sizeX = (int) bounds.getXsize();
            int sizeY = (int) bounds.getYsize();
            int sizeZ = (int) bounds.getZsize();
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int i = 0; i < PICKS; i++) {
                pos.set((int) bounds.minX + RANDOM.nextInt(sizeX), (int) bounds.minY + RANDOM.nextInt(sizeY),
                        (int) bounds.minZ + RANDOM.nextInt(sizeZ));
                BlockState state = mini.world().getBlockState(pos);
                if (!state.isAir()) {
                    ClientView.animateBlock(mini.world(), pos.immutable(), state);
                }
            }
            for (BlockPos at : mini.campfires()) {
                ClientView.campfireTick(mini.world(), at, mini.world().getBlockState(at));
            }
        } finally {
            LitRenderingWorld.captureSceneryParticles(null);
            SceneryParticles.animatingMini(false);
        }
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

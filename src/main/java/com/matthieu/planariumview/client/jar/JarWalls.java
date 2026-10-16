package com.matthieu.planariumview.client.jar;

import com.hollingsworth.arsnouveau.common.block.DimBoundary;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

/**
 * Les parois du bocal laissent entrer la lumiere des blocs qui entourent le Planarium.
 *
 * Chaque paroi devient une source de lumiere aussi forte que la lumiere mesuree du meme cote du Planarium
 * (par exemple 13 du cote d'une torche). Minecraft propage ensuite cette lumiere normalement :
 * plus claire pres de la paroi eclairee, plus sombre de l'autre cote, avec les ombres des blocs.
 *
 * Le bocal (dimension d'Ars) va de -1 a 32 en X et Z, et de 0 a 32 en Y. Comme Ars le dessine dans le Planarium,
 * le bas du bocal (Y &lt; 7) correspond au bloc du Planarium, et le haut au bloc juste au-dessus.
 */
public final class JarWalls {
    /** Lumiere emise par une paroi (0 = aucune). Ajoutee au bloc ars_nouveau:dim_boundary. */
    public static final IntegerProperty LIGHT = IntegerProperty.create("planariumview_light", 0, 15);

    public static final int MIN = -1;
    public static final int MAX = 32;
    public static final int MIN_Y = 0;
    public static final int MAX_Y = 32;
    /** Sous ce Y, la paroi correspond au bloc du Planarium ; au-dessus, au bloc juste au-dessus. */
    public static final int SPLIT_Y = 7;

    /** Faces : ouest, est, nord, sud (chacune en bas puis en haut), puis plancher et plafond. */
    public static final int FACES = 10;
    private static final Direction[] SIDES = {Direction.WEST, Direction.EAST, Direction.NORTH, Direction.SOUTH};

    private JarWalls() {
    }

    /** Position dans le monde d'origine dont la lumiere eclaire chaque face. */
    public static BlockPos[] samplePositions(BlockPos planarium) {
        BlockPos above = planarium.above();
        BlockPos[] positions = new BlockPos[FACES];
        for (int i = 0; i < SIDES.length; i++) {
            positions[i * 2] = planarium.relative(SIDES[i]);
            positions[i * 2 + 1] = above.relative(SIDES[i]);
        }
        positions[8] = planarium.below();
        positions[9] = above.above();
        return positions;
    }

    /** Lumiere des blocs mesuree pour chaque face. */
    public static int[] measure(ServerLevel level, BlockPos planarium) {
        BlockPos[] positions = samplePositions(planarium);
        int[] light = new int[FACES];
        for (int i = 0; i < FACES; i++) {
            light[i] = level.getBrightness(LightLayer.BLOCK, positions[i]);
        }
        return light;
    }

    /**
     * Pour chaque face, le bloc lumineux qui l'eclaire le plus (pour la couleur de Contraption Lights).
     * On cherche dans un cube de 15 blocs autour du Planarium. Retourne l'identifiant du bloc, ou -1.
     */
    public static int[] findSources(net.minecraft.world.level.Level level, BlockPos planarium) {
        BlockPos[] positions = samplePositions(planarium);
        int[] best = new int[FACES];
        int[] states = new int[FACES];
        java.util.Arrays.fill(states, -1);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int r = 15;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r + 1; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    pos.set(planarium.getX() + dx, planarium.getY() + dy, planarium.getZ() + dz);
                    if (dx == 0 && dy == 0 && dz == 0 || !level.isLoaded(pos)) {
                        continue;
                    }
                    BlockState state = level.getBlockState(pos);
                    int emission = state.getLightEmission(level, pos);
                    if (emission <= 0) {
                        continue;
                    }
                    for (int f = 0; f < FACES; f++) {
                        int contribution = emission - pos.distManhattan(positions[f]);
                        if (contribution > best[f]) {
                            best[f] = contribution;
                            states[f] = Block.getId(state);
                        }
                    }
                }
            }
        }
        return states;
    }

    /** Les faces auxquelles appartient une position de paroi. */
    public static int lightAt(int x, int y, int z, int[] faceLight) {
        int light = 0;
        boolean low = y < SPLIT_Y;
        if (x == MIN) {
            light = Math.max(light, faceLight[low ? 0 : 1]);
        }
        if (x == MAX) {
            light = Math.max(light, faceLight[low ? 2 : 3]);
        }
        if (z == MIN) {
            light = Math.max(light, faceLight[low ? 4 : 5]);
        }
        if (z == MAX) {
            light = Math.max(light, faceLight[low ? 6 : 7]);
        }
        if (y == MIN_Y) {
            light = Math.max(light, faceLight[8]);
        }
        if (y == MAX_Y) {
            light = Math.max(light, faceLight[9]);
        }
        return light;
    }

    /** La face principale d'une position de paroi (celle qui donne le plus de lumiere), ou -1. */
    public static int faceAt(int x, int y, int z, int[] faceLight) {
        boolean low = y < SPLIT_Y;
        int face = -1;
        int best = -1;
        int[] candidates = {
                x == MIN ? (low ? 0 : 1) : -1,
                x == MAX ? (low ? 2 : 3) : -1,
                z == MIN ? (low ? 4 : 5) : -1,
                z == MAX ? (low ? 6 : 7) : -1,
                y == MIN_Y ? 8 : -1,
                y == MAX_Y ? 9 : -1};
        for (int candidate : candidates) {
            if (candidate >= 0 && faceLight[candidate] > best) {
                best = faceLight[candidate];
                face = candidate;
            }
        }
        return face;
    }

    private static boolean isWall(int x, int y, int z) {
        return x == MIN || x == MAX || z == MIN || z == MAX || y == MIN_Y || y == MAX_Y;
    }

    /**
     * Met a jour la lumiere emise par les parois du bocal.
     * Retourne {nombre de parois trouvees, nombre de parois sans la propriete, nombre de blocs modifies}.
     */
    public static int[] apply(ServerLevel pocket, int[] faceLight) {
        int found = 0;
        int missing = 0;
        int changed = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = MIN; x <= MAX; x++) {
            for (int y = MIN_Y; y <= MAX_Y; y++) {
                for (int z = MIN; z <= MAX; z++) {
                    if (!isWall(x, y, z)) {
                        continue;
                    }
                    pos.set(x, y, z);
                    if (!pocket.isLoaded(pos)) {
                        continue;
                    }
                    BlockState state = pocket.getBlockState(pos);
                    if (!(state.getBlock() instanceof DimBoundary)) {
                        continue;
                    }
                    found++;
                    if (!state.hasProperty(LIGHT)) {
                        missing++;
                        continue;
                    }
                    int light = lightAt(x, y, z, faceLight);
                    if (state.getValue(LIGHT) != light) {
                        pocket.setBlock(pos, state.setValue(LIGHT, light), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                        changed++;
                    }
                }
            }
        }
        return new int[]{found, missing, changed};
    }
}

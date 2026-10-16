package com.matthieu.planariumview;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;

/**
 * Lumiere des blocs a l'interieur du bocal, calculee comme celle du bloc que le bocal represente.
 *
 * Le bocal fait 32 blocs de large, mais il represente un seul bloc du monde d'origine. Dans ce bloc, la lumiere
 * passe d'un cote a l'autre en douceur (par exemple 9 cote torche, 7 de l'autre cote). La propagation de Minecraft
 * (1 niveau de moins par bloc) ne pourrait jamais couvrir 32 blocs : on calcule donc chaque point de l'interieur
 * en interpolant entre la lumiere mesuree au centre (le Planarium) et celle de chaque cote.
 *
 * Les lumieres placees dans le bocal restent : on garde la plus forte des deux.
 * Cote client uniquement, rien n'est ecrit dans la sauvegarde.
 */
public final class JarLight {
    private static final int SIZE = 32;
    private static final int HEIGHT = 33;

    /** Lumiere de l'interieur (x 0-31, y 0-32, z 0-31), ou null si inconnue. */
    private static volatile byte[] table;
    private static volatile ClientLevel tableLevel;
    private static volatile net.minecraft.resources.ResourceLocation tableDim;
    private static int[] lastFaces;
    private static int lastCenter = -2;

    private JarLight() {
    }

    /** Lumiere des blocs a cet endroit, en tenant compte de la lumiere qui entre dans le bocal. */
    public static int blockLight(int x, int y, int z, int vanilla) {
        byte[] current = table;
        if (current == null || vanilla >= 15 || x < 0 || x >= SIZE || y < 0 || y >= HEIGHT || z < 0 || z >= SIZE) {
            return vanilla;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || !Config.outsideLight()) {
            return vanilla;
        }
        if (tableLevel != level) {
            // La lumiere a pu arriver avant que le client entre dans le bocal.
            if (!level.dimension().location().equals(tableDim)) {
                return vanilla;
            }
            tableLevel = level;
        }
        int index = (x * HEIGHT + y) * SIZE + z;
        byte[] shaded = lit;
        return Math.max(vanilla, shaded != null ? shaded[index] : current[index]);
    }

    /**
     * Lumiere du dehors bloc par bloc, en tenant compte des blocs places dans le bocal : les blocs sous le ciel du bocal
     * (rien au-dessus jusqu'au verre du haut) et ceux qui touchent les parois recoivent la lumiere du dehors ; ailleurs,
     * elle se propage comme celle de Minecraft (un niveau de moins par bloc). Une piece fermee reste noire ; une ouverture
     * l'eclaire de moins en moins en s'en eloignant. Null = pas encore calcule (toute la table).
     */
    private static volatile byte[] lit;
    /** Blocs qui laissent passer la lumiere, ou null si les chunks du bocal ne sont pas charges. */
    private static boolean[] passable;
    /** Formes des faces des blocs (voir faceShapes), memes indices que passable. */
    private static net.minecraft.world.phys.shapes.VoxelShape[][] shapes;
    /** Bloc de chaque case (lu chaque seconde) : on ne recalcule que si un bloc a change. */
    private static int[] blockIds;

    /** Relit les blocs du bocal (chaque seconde) et recalcule la lumiere s'ils ont change. */
    public static void updateReach(ClientLevel level) {
        int[] nextIds = readIds(level);
        if (java.util.Arrays.equals(nextIds, blockIds)) {
            return;
        }
        blockIds = nextIds;
        if (nextIds == null) {
            passable = null;
            shapes = null;
        } else {
            readBlocks(level);
        }
        relight();
        // Un bloc pose ou casse dans le bocal. On ne redessine tout le bocal que si de la lumiere du dehors y entre
        // (sinon rien ne change), et on ne fait recolorer Contraption Lights que si cette lumiere a une couleur :
        // le recoloriage de tout le bocal faisait clignoter en blanc les lumieres colorees placees dans le bocal.
        if (table != null && level == tableLevel && hasLight(table)) {
            Minecraft.getInstance().levelRenderer.setBlocksDirty(-1, 0, -1, 32, 32, 32);
            if (JarColor.hasColor()) {
                WallColors.notifyChanged();
            }
            FlywheelCompat.refresh(level);
        }
    }

    private static boolean hasLight(byte[] values) {
        for (byte value : values) {
            if (value > 0) {
                return true;
            }
        }
        return false;
    }

    private static int[] readIds(ClientLevel level) {
        if (level == null) {
            return null;
        }
        for (int cx = 0; cx <= 1; cx++) {
            for (int cz = 0; cz <= 1; cz++) {
                if (!level.hasChunk(cx, cz)) {
                    return null;
                }
            }
        }
        int[] ids = new int[SIZE * HEIGHT * SIZE];
        net.minecraft.core.BlockPos.MutableBlockPos pos = new net.minecraft.core.BlockPos.MutableBlockPos();
        for (int x = 0; x < SIZE; x++) {
            for (int y = 0; y < HEIGHT; y++) {
                for (int z = 0; z < SIZE; z++) {
                    pos.set(x, y, z);
                    ids[(x * HEIGHT + y) * SIZE + z] = net.minecraft.world.level.block.Block.getId(level.getBlockState(pos));
                }
            }
        }
        return ids;
    }

    /** Lit les blocs qui laissent passer la lumiere et les formes de leurs faces. */
    private static void readBlocks(ClientLevel level) {
        boolean[] open = new boolean[SIZE * HEIGHT * SIZE];
        net.minecraft.world.phys.shapes.VoxelShape[][] faceShapes = new net.minecraft.world.phys.shapes.VoxelShape[open.length][];
        net.minecraft.core.BlockPos.MutableBlockPos pos = new net.minecraft.core.BlockPos.MutableBlockPos();
        for (int x = 0; x < SIZE; x++) {
            for (int y = 0; y < HEIGHT; y++) {
                for (int z = 0; z < SIZE; z++) {
                    pos.set(x, y, z);
                    int index = (x * HEIGHT + y) * SIZE + z;
                    net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
                    open[index] = state.getLightBlock(level, pos) < 15;
                    if (open[index]) {
                        faceShapes[index] = faceShapes(state, level, pos);
                    }
                }
            }
        }
        passable = open;
        shapes = faceShapes;
    }

    /**
     * Formes des faces d'un bloc pour la lumiere (une par Direction, ordinal), comme Minecraft : une dalle ou un escalier
     * laisse passer la lumiere par ses cotes ouverts, mais pas a travers sa face pleine, et deux demi-faces qui se
     * touchent (dalle du haut contre dalle du bas) bouchent le passage ensemble. Null pour les blocs sans forme.
     */
    static net.minecraft.world.phys.shapes.VoxelShape[] faceShapes(net.minecraft.world.level.block.state.BlockState state,
                                                                    net.minecraft.world.level.BlockGetter level,
                                                                    net.minecraft.core.BlockPos pos) {
        if (!state.useShapeForLightOcclusion()) {
            return null;
        }
        try {
            net.minecraft.core.Direction[] directions = net.minecraft.core.Direction.values();
            net.minecraft.world.phys.shapes.VoxelShape[] result = new net.minecraft.world.phys.shapes.VoxelShape[directions.length];
            boolean any = false;
            for (net.minecraft.core.Direction direction : directions) {
                result[direction.ordinal()] = state.getFaceOcclusionShape(level, pos, direction);
                any |= !result[direction.ordinal()].isEmpty();
            }
            return any ? result : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Recalcule la lumiere du dehors bloc par bloc (voir lit). */
    private static void relight() {
        byte[] source = table;
        boolean[] open = passable;
        if (source == null || open == null) {
            lit = null;
            return;
        }
        lit = occlude(source, open, shapes);
    }

    /**
     * Lumiere du dehors (table) repartie en tenant compte des blocs : voir lit. {@code open} = blocs qui laissent passer
     * la lumiere, meme indices que la table. Sert au bocal et a la miniature.
     */
    static byte[] occlude(byte[] source, boolean[] open) {
        return occlude(source, open, null);
    }

    /** Direction (ordinal) de la face de sortie pour chaque sens de propagation : x+1, x-1, y+1, y-1, z+1, z-1. */
    private static final int[] LEAVING = {
            net.minecraft.core.Direction.EAST.ordinal(), net.minecraft.core.Direction.WEST.ordinal(),
            net.minecraft.core.Direction.UP.ordinal(), net.minecraft.core.Direction.DOWN.ordinal(),
            net.minecraft.core.Direction.SOUTH.ordinal(), net.minecraft.core.Direction.NORTH.ordinal()};
    private static final int[] ENTERING = {LEAVING[1], LEAVING[0], LEAVING[3], LEAVING[2], LEAVING[5], LEAVING[4]};

    /**
     * La lumiere peut-elle passer du bloc {@code from} (-1 = la paroi de verre) au bloc {@code to} dans le sens
     * {@code d} (voir LEAVING) ? Non si la face de sortie et la face d'entree couvrent ensemble toute la face.
     */
    private static boolean crosses(net.minecraft.world.phys.shapes.VoxelShape[][] shapes, int from, int to, int d) {
        if (shapes == null) {
            return true;
        }
        net.minecraft.world.phys.shapes.VoxelShape[] a = from < 0 ? null : shapes[from];
        net.minecraft.world.phys.shapes.VoxelShape[] b = shapes[to];
        if (a == null && b == null) {
            return true;
        }
        net.minecraft.world.phys.shapes.VoxelShape leaving = a == null ? net.minecraft.world.phys.shapes.Shapes.empty() : a[LEAVING[d]];
        net.minecraft.world.phys.shapes.VoxelShape entering = b == null ? net.minecraft.world.phys.shapes.Shapes.empty() : b[ENTERING[d]];
        return !net.minecraft.world.phys.shapes.Shapes.faceShapeOccludes(leaving, entering);
    }

    /**
     * Comme occlude(source, open), en tenant compte de la forme des faces des blocs ({@code faces}, voir faceShapes, ou
     * null) : la lumiere ne traverse plus un toit d'escaliers ou de dalles comme s'il etait ouvert.
     */
    static byte[] occlude(byte[] source, boolean[] open, net.minecraft.world.phys.shapes.VoxelShape[][] faces) {
        int total = SIZE * HEIGHT * SIZE;
        boolean[] sees = new boolean[total];
        // Comme la lumiere du ciel de Minecraft : elle descend tout droit depuis le haut du bocal (verre) sans perdre
        // de force ; elle entre aussi par les parois de verre, dans les blocs qui les touchent. Partout ailleurs,
        // elle se propage en perdant un niveau par bloc (une fenetre eclaire une piece de moins en moins).
        for (int a = 0; a < SIZE; a++) {
            for (int b = 0; b < SIZE; b++) {
                sweepY(open, faces, sees, a, b, HEIGHT - 1, -1);
            }
        }
        for (int x = 0; x < SIZE; x++) {
            for (int y = 1; y < HEIGHT; y++) {
                for (int z = 0; z < SIZE; z++) {
                    int index = (x * HEIGHT + y) * SIZE + z;
                    if (!open[index]) {
                        continue;
                    }
                    // Par la paroi de verre, sauf si la face du bloc tournee vers elle est pleine.
                    if ((x == 0 && crosses(faces, -1, index, 0)) || (x == SIZE - 1 && crosses(faces, -1, index, 1))
                            || (z == 0 && crosses(faces, -1, index, 4)) || (z == SIZE - 1 && crosses(faces, -1, index, 5))) {
                        sees[index] = true;
                    }
                }
            }
        }
        byte[] next = new byte[total];
        // File par niveau (15 a 1) : propagation comme Minecraft, un niveau de moins par bloc transparent.
        int[][] buckets = new int[16][];
        int[] counts = new int[16];
        for (int i = 0; i < 16; i++) {
            buckets[i] = new int[64];
        }
        for (int index = 0; index < total; index++) {
            if (sees[index] && source[index] > 0) {
                next[index] = source[index];
                int level = source[index];
                if (counts[level] == buckets[level].length) {
                    buckets[level] = java.util.Arrays.copyOf(buckets[level], counts[level] * 2);
                }
                buckets[level][counts[level]++] = index;
            }
        }
        for (int level = 15; level >= 2; level--) {
            for (int k = 0; k < counts[level]; k++) {
                int index = buckets[level][k];
                if (next[index] != level) {
                    continue;
                }
                int x = index / (HEIGHT * SIZE);
                int y = (index / SIZE) % HEIGHT;
                int z = index % SIZE;
                for (int d = 0; d < 6; d++) {
                    int nx = x + (d == 0 ? 1 : d == 1 ? -1 : 0);
                    int ny = y + (d == 2 ? 1 : d == 3 ? -1 : 0);
                    int nz = z + (d == 4 ? 1 : d == 5 ? -1 : 0);
                    if (nx < 0 || nx >= SIZE || ny < 0 || ny >= HEIGHT || nz < 0 || nz >= SIZE) {
                        continue;
                    }
                    int neighbor = (nx * HEIGHT + ny) * SIZE + nz;
                    if (!open[neighbor] || next[neighbor] >= level - 1 || !crosses(faces, index, neighbor, d)) {
                        continue;
                    }
                    next[neighbor] = (byte) (level - 1);
                    int target = level - 1;
                    if (counts[target] == buckets[target].length) {
                        buckets[target] = java.util.Arrays.copyOf(buckets[target], counts[target] * 2);
                    }
                    buckets[target][counts[target]++] = neighbor;
                }
            }
        }
        return next;
    }

    private static void sweepY(boolean[] open, net.minecraft.world.phys.shapes.VoxelShape[][] faces, boolean[] sees, int x, int z, int start, int step) {
        int above = -1;
        for (int y = start; y >= 1 && y < HEIGHT; y += step) {
            int index = (x * HEIGHT + y) * SIZE + z;
            if (!open[index]) {
                if (y == HEIGHT - 1) {
                    continue; // le plafond du bocal lui-meme
                }
                return;
            }
            // En descendant (sens y-1) : la face du dessous du bloc d'au-dessus et celle du dessus de ce bloc.
            if (!crosses(faces, above, index, 3)) {
                return;
            }
            sees[index] = true;
            above = index;
        }
    }

    /** Bocal dans lequel on vient d'entrer, et nombre de fois ou il reste a redessiner ses chunks. */
    private static ClientLevel remeshLevel;
    private static int remeshesLeft;

    /**
     * Appele chaque seconde dans le bocal (fil principal). Juste apres l'entree, la lumiere du ciel, celle du dehors
     * et les couleurs arrivent pendant que les chunks du bocal sont dessines : certains gardent un rendu faux
     * (il fallait F3 + A). On redessine donc seulement les chunks du bocal, une fois par seconde pendant 3 secondes.
     */
    public static void tickRemesh(ClientLevel level) {
        if (level == null) {
            return;
        }
        if (level != remeshLevel) {
            remeshLevel = level;
            remeshesLeft = 3;
        }
        if (remeshesLeft <= 0) {
            return;
        }
        remeshesLeft--;
        Minecraft.getInstance().levelRenderer.setBlocksDirty(-1, 0, -1, 32, 32, 32);
        WallColors.notifyChanged();
        FlywheelCompat.refresh(level);
    }

    /** Lumiere qui entre dans le bocal a cet endroit (sans les lumieres placees dedans), 0 hors du bocal. */
    public static int interiorLevel(int x, int y, int z) {
        return blockLight(x, y, z, 0);
    }

    /** Lumiere des blocs packee pour la lightmap, avec la lumiere qui entre dans le bocal. */
    public static int packedLight(int packed, int x, int y, int z) {
        int block = (packed >> 4) & 15;
        int next = blockLight(x, y, z, block);
        return next == block ? packed : (packed & ~0xFF) | next << 4;
    }

    /** Recalcule la lumiere de l'interieur quand la lumiere du dehors change. */
    public static void rebuild(int[] faces, int center, net.minecraft.resources.ResourceLocation dim) {
        ClientLevel level = Minecraft.getInstance().level;
        if (faces == null || center < 0 || dim == null) {
            return;
        }
        if (table != null && dim.equals(tableDim) && center == lastCenter && java.util.Arrays.equals(faces, lastFaces)) {
            return;
        }
        lastFaces = faces.clone();
        lastCenter = center;
        byte[] next = computeTable(faces, center);
        table = next;
        relight();
        tableDim = dim;
        tableLevel = level != null && level.dimension().location().equals(dim) ? level : null;
        // Les chunks du bocal doivent etre redessines avec la nouvelle lumiere.
        if (tableLevel != null) {
            Minecraft.getInstance().levelRenderer.setBlocksDirty(-1, 0, -1, 32, 32, 32);
            WallColors.notifyChanged();
            FlywheelCompat.refresh(tableLevel);
        } else {
            JarColor.invalidate();
        }
    }

    /** Lumiere qui entre dans le bocal pour chaque bloc de l'interieur (x 0-31, y 0-32, z 0-31). */
    static final int TABLE_SIZE = SIZE;
    static final int TABLE_HEIGHT = HEIGHT;

    public static int tableIndex(int x, int y, int z) {
        return x < 0 || x >= SIZE || y < 0 || y >= HEIGHT || z < 0 || z >= SIZE ? -1 : (x * HEIGHT + y) * SIZE + z;
    }

    /**
     * Lumiere de chaque bloc de l'interieur, a partir de la lumiere de chaque cote du Planarium et de celle du centre.
     * Sert aussi a la miniature vue de l'exterieur.
     */
    public static byte[] computeTable(int[] faces, int center) {
        byte[] next = new byte[SIZE * HEIGHT * SIZE];
        for (int x = 0; x < SIZE; x++) {
            float fx = (x + 0.5F) / SIZE;
            for (int y = 0; y < HEIGHT; y++) {
                float fy = y / (float) (HEIGHT - 1);
                boolean low = y < JarWalls.SPLIT_Y;
                float west = side(faces[low ? 0 : 1], center);
                float east = side(faces[low ? 2 : 3], center);
                float north = side(faces[low ? 4 : 5], center);
                float south = side(faces[low ? 6 : 7], center);
                float down = side(faces[8], center);
                float up = side(faces[9], center);
                for (int z = 0; z < SIZE; z++) {
                    float fz = (z + 0.5F) / SIZE;
                    float light = center
                            + (axis(fx, west, east, center) - center)
                            + (axis(fy, down, up, center) - center)
                            + (axis(fz, north, south, center) - center);
                    next[(x * HEIGHT + y) * SIZE + z] = (byte) Mth.clamp(Math.round(light), 0, 15);
                }
            }
        }
        return next;
    }

    /**
     * Lumiere d'un cote. Un bloc opaque (lumiere mesuree 0) ne donne pas de lumiere mais n'assombrit pas non plus :
     * comme l'eclairage doux de Minecraft, on l'ignore et on garde la lumiere du centre.
     */
    private static float side(int value, int center) {
        return value <= 0 ? center : value;
    }

    /** Lumiere le long d'un axe : du premier cote (f = 0) au centre (f = 0.5), puis au second cote (f = 1). */
    private static float axis(float f, float first, float second, float center) {
        return f < 0.5F ? Mth.lerp(f * 2.0F, first, center) : Mth.lerp((f - 0.5F) * 2.0F, center, second);
    }

    public static void clear() {
        table = null;
        lit = null;
        passable = null;
        shapes = null;
        blockIds = null;
        tableLevel = null;
        tableDim = null;
        lastFaces = null;
        lastCenter = -2;
    }
}

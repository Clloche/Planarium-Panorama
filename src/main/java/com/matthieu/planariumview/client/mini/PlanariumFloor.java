package com.matthieu.planariumview.client.mini;

import com.matthieu.planariumview.client.jar.JarColor;
import com.matthieu.planariumview.client.jar.WallColors;
import com.matthieu.planariumview.config.Config;
import com.matthieu.planariumview.network.MiniLightPayload;

import com.hollingsworth.arsnouveau.client.renderer.world.CulledStatePos;
import com.hollingsworth.arsnouveau.common.block.tile.PlanariumTile;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Le plateau du Planarium, vu de dehors, est le plancher de la miniature. Avec une lumiere dans le bocal
 * (un feu de camp vert...), on le redessine par-dessus, decoupe en 32 x 32 carres (un par bloc du bocal),
 * chacun eclaire par la lumiere du plancher du bocal a cet endroit, avec la couleur des lumieres proches :
 * une flaque de lumiere coloree autour de la lumiere, comme sur le plancher a l'interieur du bocal.
 */
public final class PlanariumFloor {
    private static final int SIZE = 32;
    /** Hauteur du plateau dans le modele du Planarium (13/16 de bloc). */
    private static final float TOP = 13.0F / 16.0F;

    /** Lumiere (0-15) et couleur (0xRRGGBB ou -1) a chaque coin de la grille (33 x 33), par bocal. */
    private record Grid(byte[] level, int[] color, boolean any) {
    }

    private record Emitter(int x, int y, int z, int emission, int color) {
    }

    private static final Map<ResourceLocation, byte[]> LIGHT = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, List<Emitter>> EMITTERS = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, Grid> GRIDS = new ConcurrentHashMap<>();

    /** Quadrilateres du dessus du plateau dans le modele du Planarium (lus une fois). */
    private static List<BakedQuad> topQuads;
    private static BlockState topState;

    private PlanariumFloor() {
    }

    /** Lumiere de l'interieur du bocal recue du serveur (voir MiniLightPayload). */
    public static void setLight(ResourceLocation dim, byte[] light) {
        LIGHT.put(dim, light);
        GRIDS.remove(dim);
    }

    /** Lumieres placees dans le bocal, lues dans la miniature (positions du gabarit : y + 1 = y du bocal). */
    static void setEmitters(ResourceLocation dim, List<CulledStatePos> states) {
        List<Emitter> list = new ArrayList<>();
        for (CulledStatePos pos : states) {
            int emission = pos.state.getLightEmission();
            if (emission > 0) {
                list.add(new Emitter(pos.pos.getX(), pos.pos.getY() + 1, pos.pos.getZ(), emission,
                        WallColors.colorOf(Block.getId(pos.state))));
            }
        }
        EMITTERS.put(dim, list);
        GRIDS.remove(dim);
    }

    public static void clear() {
        LIGHT.clear();
        EMITTERS.clear();
        GRIDS.clear();
        resourcesReloaded();
    }

    /** Les UV et le modele du plateau appartiennent aux ressources rechargees. La lumiere recue reste valide. */
    public static void resourcesReloaded() {
        topQuads = null;
        topState = null;
        BUILT.clear();
    }

    private static Grid grid(ResourceLocation dim) {
        Grid grid = GRIDS.get(dim);
        if (grid != null) {
            return grid;
        }
        byte[] light = LIGHT.get(dim);
        if (light == null || light.length != MiniLightPayload.SIZE_X * MiniLightPayload.SIZE_Y * MiniLightPayload.SIZE_Z) {
            return null;
        }
        List<Emitter> emitters = EMITTERS.getOrDefault(dim, List.of());
        byte[] level = new byte[(SIZE + 1) * (SIZE + 1)];
        int[] color = new int[(SIZE + 1) * (SIZE + 1)];
        boolean any = false;
        for (int i = 0; i <= SIZE; i++) {
            for (int j = 0; j <= SIZE; j++) {
                // Coin entre 4 blocs du bocal, juste au-dessus du plancher (y = 1) : moyenne, comme l'eclairage lisse.
                int sum = 0;
                for (int dx = -1; dx <= 0; dx++) {
                    for (int dz = -1; dz <= 0; dz++) {
                        int x = Mth.clamp(i + dx, 0, SIZE - 1) + 1;
                        int z = Mth.clamp(j + dz, 0, SIZE - 1) + 1;
                        sum += light[(x * MiniLightPayload.SIZE_Y + 1) * MiniLightPayload.SIZE_Z + z] & 15;
                    }
                }
                int value = Math.round(sum / 4.0F);
                level[i * (SIZE + 1) + j] = (byte) value;
                any |= value > 0;
                // Couleur des lumieres qui eclairent ce coin, ponderee par ce qu'elles y apportent.
                double r = 0;
                double g = 0;
                double b = 0;
                double total = 0;
                for (Emitter e : emitters) {
                    double distance = Math.abs(e.x() + 0.5 - i) + Math.abs(e.y() - 1) + Math.abs(e.z() + 0.5 - j);
                    double contribution = e.emission() - distance;
                    if (contribution <= 0) {
                        continue;
                    }
                    double w = contribution * contribution;
                    r += ((e.color() >> 16) & 255) * w;
                    g += ((e.color() >> 8) & 255) * w;
                    b += (e.color() & 255) * w;
                    total += w;
                }
                color[i * (SIZE + 1) + j] = total <= 0 ? -1
                        : ((int) (r / total) & 255) << 16 | ((int) (g / total) & 255) << 8 | ((int) (b / total) & 255);
            }
        }
        grid = new Grid(level, color, any);
        GRIDS.put(dim, grid);
        return grid;
    }

    private static List<BakedQuad> topQuads(BlockState state) {
        if (topQuads != null && topState == state) {
            return topQuads;
        }
        List<BakedQuad> result = new ArrayList<>();
        try {
            BakedModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
            RandomSource random = RandomSource.create(42L);
            List<BakedQuad> all = new ArrayList<>(model.getQuads(state, null, random));
            all.addAll(model.getQuads(state, Direction.UP, random));
            for (BakedQuad quad : all) {
                if (quad.getDirection() != Direction.UP) {
                    continue;
                }
                int[] data = quad.getVertices();
                int stride = data.length / 4;
                boolean top = true;
                float minX = 1;
                float maxX = 0;
                for (int v = 0; v < 4; v++) {
                    float x = Float.intBitsToFloat(data[v * stride]);
                    float y = Float.intBitsToFloat(data[v * stride + 1]);
                    top &= Math.abs(y - TOP) < 0.01F;
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                }
                // Le grand plateau (toute la largeur du bloc), pas les petits morceaux a la meme hauteur.
                if (top && maxX - minX > 0.9F) {
                    result.add(quad);
                }
            }
        } catch (Exception ignored) {
            // modele inattendu : pas de plateau eclaire
        }
        topQuads = result;
        topState = state;
        return result;
    }

    /**
     * Maillage du plateau deja calcule pour un bocal et une lumiere du Planarium : les sommets (position et UV dans le
     * bloc, lumiere) ne changent qu'avec la lumiere recue, le modele ou cette lumiere. Avant, la grille de 32 x 32
     * carres et ses couleurs etaient refaites a chaque image (10 % du rendu devant une miniature).
     */
    private record Built(Grid grid, List<BakedQuad> quads, float[] xyz, float[] uv, int[] light, int count) {
    }

    private record Key(ResourceLocation dim, int packedLight, boolean colored) {
    }

    private static final Map<Key, Built> BUILT = new ConcurrentHashMap<>();
    /** Lumieres du Planarium differentes gardees au plus (heure, meteo, plusieurs Planariums). */
    private static final int MAX_BUILT = 256;

    /** Appele au debut du dessin du Planarium (la pile de matrices est au coin du bloc). */
    public static void render(PlanariumTile tile, PoseStack poseStack, MultiBufferSource buffers, int packedLight) {
        if (tile.key == null || !Config.realLighting() || tile.getLevel() == null) {
            return;
        }
        Grid grid = grid(tile.key.location());
        if (grid == null || !grid.any()) {
            return;
        }
        List<BakedQuad> quads = topQuads(tile.getBlockState());
        if (quads.isEmpty()) {
            return;
        }
        Key key = new Key(tile.key.location(), packedLight, Config.coloredLight());
        Built built = BUILT.get(key);
        if (built == null || built.grid() != grid || built.quads() != quads) {
            if (BUILT.size() >= MAX_BUILT) BUILT.clear();
            built = build(grid, quads, packedLight, key.colored());
            BUILT.put(key, built);
        }
        if (built.count() == 0) {
            return;
        }
        VertexConsumer out = buffers.getBuffer(RenderType.cutout());
        PoseStack.Pose pose = poseStack.last();
        float[] xyz = built.xyz();
        float[] uv = built.uv();
        int[] light = built.light();
        for (int i = 0; i < built.count(); i++) {
            out.addVertex(pose, xyz[i * 3], xyz[i * 3 + 1], xyz[i * 3 + 2])
                    .setColor(255, 255, 255, 255)
                    .setUv(uv[i * 2], uv[i * 2 + 1])
                    .setOverlay(OverlayTexture.NO_OVERLAY)
                    .setLight(light[i])
                    .setNormal(pose, 0.0F, 1.0F, 0.0F);
        }
    }

    /**
     * Les carres aussi sombres que le plateau d'Ars (ses 4 coins a la lumiere du Planarium) ne sont pas redessines : le
     * plateau dessous a deja cette apparence. Les zones de lumiere constante deviennent de grands rectangles, en
     * eventail depuis leur centre pour garder tous les points de la grille sur leur bord (pas de jonction en T, donc pas
     * de fente vers le plateau plus sombre), comme les parois du bocal (ProjectorLighting).
     */
    private static Built build(Grid grid, List<BakedQuad> quads, int packedLight, boolean colored) {
        int outsideBlock = (packedLight >> 4) & 15;
        int sky = (packedLight >> 20) & 15;
        int[] corner = new int[(SIZE + 1) * (SIZE + 1)];
        boolean brighter = false;
        for (int k = 0; k < corner.length; k++) {
            int level = grid.level()[k];
            if (level <= outsideBlock) {
                corner[k] = packedLight;
                continue;
            }
            brighter = true;
            int next = sky << 20 | level << 4;
            int color = grid.color()[k];
            corner[k] = colored && color >= 0 ? JarColor.tagColor(next, color) : next;
        }
        Mesh mesh = new Mesh();
        if (brighter) {
            for (BakedQuad quad : quads) {
                int[] data = quad.getVertices();
                int stride = data.length / 4;
                float[][] p = new float[4][3];
                float[][] uv = new float[4][2];
                for (int v = 0; v < 4; v++) {
                    p[v][0] = Float.intBitsToFloat(data[v * stride]);
                    p[v][1] = Float.intBitsToFloat(data[v * stride + 1]) + 0.0015F;
                    p[v][2] = Float.intBitsToFloat(data[v * stride + 2]);
                    uv[v][0] = Float.intBitsToFloat(data[v * stride + 4]);
                    uv[v][1] = Float.intBitsToFloat(data[v * stride + 5]);
                }
                // Lumiere de chaque point de la grille du quadrilatere (sa place sur le plateau donne le coin du bocal).
                int[][] light = new int[SIZE + 1][SIZE + 1];
                for (int a = 0; a <= SIZE; a++) {
                    for (int b = 0; b <= SIZE; b++) {
                        float s = (float) a / SIZE;
                        float t = (float) b / SIZE;
                        int i = Mth.clamp(Math.round(lerp(p, s, t, 0) * SIZE), 0, SIZE);
                        int j = Mth.clamp(Math.round(lerp(p, s, t, 2) * SIZE), 0, SIZE);
                        light[a][b] = corner[i * (SIZE + 1) + j];
                    }
                }
                mesh.quad(p, uv, light, packedLight);
            }
        }
        return new Built(grid, quads, java.util.Arrays.copyOf(mesh.xyz, mesh.count * 3),
                java.util.Arrays.copyOf(mesh.uv, mesh.count * 2), java.util.Arrays.copyOf(mesh.light, mesh.count), mesh.count);
    }

    /** Sommets du plateau en construction (positions et UV dans le bloc). */
    private static final class Mesh {
        float[] xyz = new float[3 * 256];
        float[] uv = new float[2 * 256];
        int[] light = new int[256];
        int count;
        private float[][] p;
        private float[][] quadUv;

        void quad(float[][] p, float[][] uv, int[][] light, int base) {
            this.p = p;
            this.quadUv = uv;
            boolean[][] covered = new boolean[SIZE][SIZE];
            int[] rects = merge(light);
            for (int r = 0; r + 3 < rects.length; r += 4) {
                int a0 = rects[r], b0 = rects[r + 1], a1 = rects[r + 2], b1 = rects[r + 3];
                for (int a = a0; a < a1; a++) {
                    for (int b = b0; b < b1; b++) {
                        covered[a][b] = true;
                    }
                }
                int value = light[a0][b0];
                if (value == base) continue; // Comme le plateau d'Ars : rien a redessiner.
                int w = a1 - a0, h = b1 - b0;
                if (w * h <= 2 * (w + h)) {
                    for (int a = a0; a < a1; a++) {
                        for (int b = b0; b < b1; b++) {
                            cell(a, b, value, value, value, value);
                        }
                    }
                } else {
                    fan(a0, b0, a1, b1, value);
                }
            }
            for (int a = 0; a < SIZE; a++) {
                for (int b = 0; b < SIZE; b++) {
                    if (!covered[a][b]) {
                        cell(a, b, light[a][b], light[a + 1][b], light[a + 1][b + 1], light[a][b + 1]);
                    }
                }
            }
        }

        private void cell(int a, int b, int l00, int l10, int l11, int l01) {
            emit(a, b, l00);
            emit(a + 1, b, l10);
            emit(a + 1, b + 1, l11);
            emit(a, b + 1, l01);
        }

        /** Rectangle de lumiere constante : un eventail qui passe par tous les points de son contour. */
        private void fan(int a0, int b0, int a1, int b1, int value) {
            float ca = (a0 + a1) * 0.5F, cb = (b0 + b1) * 0.5F;
            int total = 2 * ((a1 - a0) + (b1 - b0));
            int pa = a0, pb = b0;
            for (int i = 0; i < total; i++) {
                int na = pa, nb = pb;
                if (pb == b0 && pa < a1) na++;
                else if (pa == a1 && pb < b1) nb++;
                else if (pb == b1 && pa > a0) na--;
                else nb--;
                emit(ca, cb, value);
                emit(pa, pb, value);
                emit(na, nb, value);
                emit(na, nb, value);
                pa = na;
                pb = nb;
            }
        }

        private void emit(float a, float b, int packed) {
            if (count == light.length) {
                xyz = java.util.Arrays.copyOf(xyz, xyz.length * 2);
                uv = java.util.Arrays.copyOf(uv, uv.length * 2);
                light = java.util.Arrays.copyOf(light, light.length * 2);
            }
            float s = a / SIZE, t = b / SIZE;
            xyz[count * 3] = lerp(p, s, t, 0);
            xyz[count * 3 + 1] = lerp(p, s, t, 1);
            xyz[count * 3 + 2] = lerp(p, s, t, 2);
            uv[count * 2] = lerp(quadUv, s, t, 0);
            uv[count * 2 + 1] = lerp(quadUv, s, t, 1);
            light[count++] = packed;
        }

        private static boolean flatCell(int[][] light, int a, int b) {
            int v = light[a][b];
            return light[a + 1][b] == v && light[a][b + 1] == v && light[a + 1][b + 1] == v;
        }

        /** Rectangles de carres a lumiere constante (meme regroupement que ProjectorLighting). */
        private static int[] merge(int[][] light) {
            boolean[][] used = new boolean[SIZE][SIZE];
            int[] rects = new int[16];
            int n = 0;
            for (int b = 0; b < SIZE; b++) {
                for (int a = 0; a < SIZE; a++) {
                    if (used[a][b] || !flatCell(light, a, b)) continue;
                    int value = light[a][b];
                    int a1 = a;
                    while (a1 + 1 < SIZE && !used[a1 + 1][b] && flatCell(light, a1 + 1, b) && light[a1 + 1][b] == value) {
                        a1++;
                    }
                    int b1 = b;
                    grow:
                    while (b1 + 1 < SIZE) {
                        for (int x = a; x <= a1; x++) {
                            if (used[x][b1 + 1] || !flatCell(light, x, b1 + 1) || light[x][b1 + 1] != value) break grow;
                        }
                        b1++;
                    }
                    for (int x = a; x <= a1; x++) {
                        for (int y = b; y <= b1; y++) {
                            used[x][y] = true;
                        }
                    }
                    if (n + 4 > rects.length) rects = java.util.Arrays.copyOf(rects, rects.length * 2);
                    rects[n++] = a;
                    rects[n++] = b;
                    rects[n++] = a1 + 1;
                    rects[n++] = b1 + 1;
                }
            }
            return java.util.Arrays.copyOf(rects, n);
        }
    }

    /** Point du quadrilatere : v0 en (0,0), v1 en (1,0), v2 en (1,1), v3 en (0,1). */
    private static float lerp(float[][] v, float s, float t, int axis) {
        return (1 - s) * (1 - t) * v[0][axis] + s * (1 - t) * v[1][axis] + s * t * v[2][axis] + (1 - s) * t * v[3][axis];
    }
}

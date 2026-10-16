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
        int outsideBlock = (packedLight >> 4) & 15;
        int sky = (packedLight >> 20) & 15;
        boolean colored = Config.coloredLight();
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
        if (!brighter) {
            return;
        }
        VertexConsumer out = buffers.getBuffer(RenderType.cutout());
        PoseStack.Pose pose = poseStack.last();
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
            for (int a = 0; a < SIZE; a++) {
                for (int b = 0; b < SIZE; b++) {
                    float s0 = (float) a / SIZE;
                    float s1 = (float) (a + 1) / SIZE;
                    float t0 = (float) b / SIZE;
                    float t1 = (float) (b + 1) / SIZE;
                    vertex(out, pose, p, uv, s0, t0, corner);
                    vertex(out, pose, p, uv, s1, t0, corner);
                    vertex(out, pose, p, uv, s1, t1, corner);
                    vertex(out, pose, p, uv, s0, t1, corner);
                }
            }
        }
    }

    private static void vertex(VertexConsumer out, PoseStack.Pose pose, float[][] p, float[][] uv, float s, float t,
                               int[] corner) {
        float x = lerp(p, s, t, 0);
        float y = lerp(p, s, t, 1);
        float z = lerp(p, s, t, 2);
        int i = Mth.clamp(Math.round(x * SIZE), 0, SIZE);
        int j = Mth.clamp(Math.round(z * SIZE), 0, SIZE);
        int light = corner[i * (SIZE + 1) + j];
        out.addVertex(pose, x, y, z)
                .setColor(255, 255, 255, 255)
                .setUv(lerp(uv, s, t, 0), lerp(uv, s, t, 1))
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(pose, 0.0F, 1.0F, 0.0F);
    }

    /** Point du quadrilatere : v0 en (0,0), v1 en (1,0), v2 en (1,1), v3 en (0,1). */
    private static float lerp(float[][] v, float s, float t, int axis) {
        return (1 - s) * (1 - t) * v[0][axis] + s * (1 - t) * v[1][axis] + s * t * v[2][axis] + (1 - s) * t * v[3][axis];
    }
}

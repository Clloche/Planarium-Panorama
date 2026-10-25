package com.matthieu.planariumview.client.scenery;

import com.matthieu.planariumview.compat.contraption_lights.ColorCompat;
import com.matthieu.planariumview.config.Config;
import com.matthieu.planariumview.network.NeighborPocketsPayload;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.pipeline.VertexConsumerWrapper;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Static shell of a displayed neighbor: hide shared glass and use its own lamps on the floor. */
final class NeighborPlanariumModel extends BakedModelWrapper<BakedModel> {
    private static final float FLOOR = 13.0F / 16;
    private static final float TOP = 29.0F / 16;
    private static final float EPSILON = 0.0001F;
    private static final int STEPS = 32;
    private static final int LIGHT_BITS = 0x00F000F0;
    private static final int[] INTERPOLATED = {0, 1, 2, 4, 5};
    private final FarRing.FarWorld pocket;
    private final int sharedSides;
    /**
     * Cotes colles a un bloc plein du decor (mur de grotte) : pas de face interieure de verre. Dans le meme plan que la
     * face de ce bloc, les deux se battaient pour la profondeur et le verre clignotait. (La reculer un peu la faisait
     * eclairer par la case du Planarium, noire : bords du verre sombres.)
     */
    private final int blockedSides;

    NeighborPlanariumModel(BakedModel model, FarRing.FarWorld pocket, int sharedSides, int blockedSides) {
        super(model);
        this.pocket = pocket;
        this.sharedSides = sharedSides;
        this.blockedSides = blockedSides;
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource random,
                                    ModelData data, @Nullable RenderType renderType) {
        List<BakedQuad> result = new ArrayList<>();
        for (BakedQuad quad : super.getQuads(state, side, random, data, renderType)) {
            if (sharedGlass(quad, sharedSides)) continue;
            if (Config.realLighting() && floor(quad)) {
                addFloor(quad, result);
            } else if (glassPlane(quad) && quad.hasAmbientOcclusion()) {
                // The glass extends above its block: AO extrapolates its corner light beyond the cell.
                // Use exterior light on the original faces too, preserving their directional shade.
                result.add(new BakedQuad(quad.getVertices(), quad.getTintIndex(), quad.getDirection(),
                        quad.getSprite(), quad.isShade(), false));
            } else {
                result.add(quad);
            }
        }
        if (side == null) addInnerGlass(state, random, data, renderType, result);
        return result;
    }

    /**
     * Face interieure du verre (parois non partagees et dessus) : le modele d'Ars n'a que des faces tournees vers
     * dehors. Vu de notre bocal, on voyait donc la paroi proche d'un voisin, mais pas ses parois du fond et des cotes
     * (faces tournees vers l'exterieur, cachees de dos). Dans le bocal, Ars dessine aussi l'interieur du verre (modele
     * "inverted" du projecteur). Sans face de coupe : une face interieure se voit meme contre un bloc colle.
     */
    private void addInnerGlass(@Nullable BlockState state, RandomSource random, ModelData data,
                               @Nullable RenderType renderType, List<BakedQuad> result) {
        List<Direction> sides = new ArrayList<>(List.of(Direction.values()));
        sides.add(null);
        for (Direction cull : sides) {
            for (BakedQuad quad : super.getQuads(state, cull, random, data, renderType)) {
                if (!glassPlane(quad) || sharedGlass(quad, sharedSides) || sharedGlass(quad, blockedSides)) continue;
                result.add(reversed(quad));
            }
        }
    }

    /** Plan de verre du modele : une paroi pleine hauteur (FLOOR a TOP) ou le dessus (a TOP). */
    static boolean glassPlane(BakedQuad quad) {
        int[] vertices = quad.getVertices();
        int stride = vertices.length / 4;
        boolean top = true;
        float minY = Float.POSITIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        float minX = Float.POSITIVE_INFINITY, maxX = Float.NEGATIVE_INFINITY;
        float minZ = Float.POSITIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < 4; i++) {
            float x = Float.intBitsToFloat(vertices[i * stride]);
            float y = Float.intBitsToFloat(vertices[i * stride + 1]);
            float z = Float.intBitsToFloat(vertices[i * stride + 2]);
            top &= Math.abs(y - TOP) < EPSILON;
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minZ = Math.min(minZ, z);
            maxZ = Math.max(maxZ, z);
        }
        // The handle also has a face at TOP: only the full-width glass roof needs an interior copy.
        if (top) return quad.getDirection() == Direction.UP && Math.abs(minX) < EPSILON
                && Math.abs(maxX - 1) < EPSILON && Math.abs(minZ) < EPSILON && Math.abs(maxZ - 1) < EPSILON;
        if (Math.abs(minY - FLOOR) >= EPSILON || Math.abs(maxY - TOP) >= EPSILON) return false;
        for (int axis : new int[]{0, 2}) {
            for (float plane : new float[]{0, 1}) {
                boolean on = true;
                for (int i = 0; i < 4; i++) {
                    on &= Math.abs(Float.intBitsToFloat(vertices[i * stride + axis]) - plane) < EPSILON;
                }
                if (on) return true;
            }
        }
        return false;
    }

    /** Meme face, tournee de l'autre cote (ordre des sommets inverse, normale opposee). */
    static BakedQuad reversed(BakedQuad quad) {
        int[] vertices = quad.getVertices();
        int stride = vertices.length / 4;
        int[] next = new int[vertices.length];
        int[] order = {0, 3, 2, 1};
        for (int i = 0; i < 4; i++) {
            System.arraycopy(vertices, order[i] * stride, next, i * stride, stride);
            // Normale empaquetee (x, y, z en octets signes) a la fin de chaque sommet du format des blocs.
            if (stride >= 8) {
                int normal = next[i * stride + 7];
                int x = -(byte) normal, y = -(byte) (normal >> 8), z = -(byte) (normal >> 16);
                next[i * stride + 7] = (normal & 0xFF000000) | (z & 0xFF) << 16 | (y & 0xFF) << 8 | (x & 0xFF);
            }
        }

        // Direction d'origine : la face prend la lumiere du dehors, comme la face exterieure du meme verre. Tournee
        // vers l'interieur, elle lisait la case du Planarium (souvent sans lumiere) : verre noir par endroits.
        // Comme les faces exterieures, sans ombrage doux : le verre depasse au-dessus du bloc, hors de la plage
        // d'interpolation des coins. Eclairage plat : une seule lumiere, celle du dehors.
        return new BakedQuad(next, quad.getTintIndex(), quad.getDirection(), quad.getSprite(),
                quad.isShade(), false);
    }

    /** Match only the full-height glass planes of Ars' model, preserving its base, legs and handle. */
    static boolean sharedGlass(BakedQuad quad, int sides) {
        if (sides == 0) return false;
        int[] vertices = quad.getVertices();
        int stride = vertices.length / 4;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if ((sides & 1 << direction.ordinal()) == 0) continue;
            int axis = direction.getAxis() == Direction.Axis.X ? 0 : 2;
            float plane = direction.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : 0;
            boolean matches = true;
            float minY = Float.POSITIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
            for (int i = 0; i < 4; i++) {
                float coordinate = Float.intBitsToFloat(vertices[i * stride + axis]);
                float y = Float.intBitsToFloat(vertices[i * stride + 1]);
                matches &= Math.abs(coordinate - plane) < EPSILON;
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
            }
            if (matches && Math.abs(minY - FLOOR) < EPSILON && Math.abs(maxY - TOP) < EPSILON) return true;
        }
        return false;
    }

    static boolean floor(BakedQuad quad) {
        if (quad.getDirection() != Direction.UP) return false;
        int[] vertices = quad.getVertices();
        int stride = vertices.length / 4;
        for (int i = 0; i < 4; i++) {
            if (Math.abs(Float.intBitsToFloat(vertices[i * stride + 1]) - FLOOR) >= EPSILON) return false;
        }
        return true;
    }

    private void addFloor(BakedQuad quad, List<BakedQuad> result) {
        int[] vertices = quad.getVertices();
        int stride = vertices.length / 4;
        int[][] light = new int[STEPS + 1][STEPS + 1];
        // Read the captured pocket, including its optional colored light, only while building the mesh.
        ColorCompat.begin(pocket);
        try {
            for (int a = 0; a <= STEPS; a++) {
                for (int b = 0; b <= STEPS; b++) {
                    float s = a / (float) STEPS, t = b / (float) STEPS;
                    double x = (interpolate(vertices, stride, 0, s, t) - ClientView.ARS_OFFSET) * ClientView.GIANT;
                    double z = (interpolate(vertices, stride, 2, s, t) - ClientView.ARS_OFFSET) * ClientView.GIANT;
                    light[a][b] = floorLight(x, z);
                }
            }
        } finally {
            ColorCompat.end();
        }
        for (int a = 0; a < STEPS; a++) {
            for (int b = 0; b < STEPS; b++) {
                int[] next = vertices.clone();
                for (int vertex = 0; vertex < 4; vertex++) {
                    float s = (a + (vertex == 1 || vertex == 2 ? 1 : 0)) / (float) STEPS;
                    float t = (b + (vertex >= 2 ? 1 : 0)) / (float) STEPS;
                    for (int component : INTERPOLATED) {
                        next[vertex * stride + component] = Float.floatToRawIntBits(interpolate(vertices, stride, component, s, t));
                    }
                }
                result.add(new FloorQuad(next, quad, new int[]{light[a][b], light[a + 1][b],
                        light[a + 1][b + 1], light[a][b + 1]}));
            }
        }
    }

    private int floorLight(double x, double z) {
        int x0 = Mth.floor(x - 0.5), z0 = Mth.floor(z - 0.5);
        int sum = 0;
        for (int dx = 0; dx <= 1; dx++) {
            for (int dz = 0; dz <= 1; dz++) {
                // Sans la lumiere du dehors ajoutee a la poche : elle eclairait le plancher en blanc, par-dessus celle
                // (coloree) que la coquille recoit deja.
                sum += pocket.ownBlockLight(sample(x0 + dx, z0 + dz));
            }
        }
        int tagged = LevelRenderer.getLightColor(pocket, sample(Mth.floor(x), Mth.floor(z)));
        // The shell already receives the source world's sky light; only add the pocket's lamps.
        return tagged & ~LIGHT_BITS | LightTexture.pack(Math.round(sum / 4.0F), 0);
    }

    private static BlockPos sample(int x, int z) {
        BlockPos origin = NeighborPocketsPayload.ORIGIN;
        return new BlockPos(Mth.clamp(x, 0, 31) - origin.getX(), 1 - origin.getY(), Mth.clamp(z, 0, 31) - origin.getZ());
    }

    static float interpolate(int[] vertices, int stride, int component, float s, float t) {
        float a = Float.intBitsToFloat(vertices[component]);
        float b = Float.intBitsToFloat(vertices[stride + component]);
        float c = Float.intBitsToFloat(vertices[2 * stride + component]);
        float d = Float.intBitsToFloat(vertices[3 * stride + component]);
        return Mth.lerp(t, Mth.lerp(s, a, b), Mth.lerp(s, d, c));
    }

    private static final class FloorQuad extends BakedQuad {
        final int[] light;

        FloorQuad(int[] vertices, BakedQuad original, int[] light) {
            super(vertices, original.getTintIndex(), original.getDirection(), original.getSprite(), original.isShade(), original.hasAmbientOcclusion());
            this.light = light;
        }
    }

    /** Preserve exterior lighting as a baseline, using the pocket's color where its lamps are stronger. */
    static int combineLight(int outside, int inside) {
        int color = (inside & 0xF0) > (outside & 0xF0) ? inside : outside;
        return color & ~LIGHT_BITS | Math.max(outside & 0xF0, inside & 0xF0)
                | Math.max(outside & 0xF00000, inside & 0xF00000);
    }

    static VertexConsumer light(VertexConsumer exterior, VertexConsumer floorTarget) {
        return new VertexConsumerWrapper(exterior) {
            @Override
            public void putBulkData(PoseStack.Pose pose, BakedQuad quad, float[] brightness, float red, float green,
                                    float blue, float alpha, int[] lightmap, int overlay, boolean readAlpha) {
                if (quad instanceof FloorQuad floor) {
                    int[] lights = new int[4];
                    for (int i = 0; i < 4; i++) lights[i] = combineLight(lightmap[i], floor.light[i]);
                    floorTarget.putBulkData(pose, quad, brightness, red, green, blue, alpha, lights, overlay, readAlpha);
                } else {
                    parent.putBulkData(pose, quad, brightness, red, green, blue, alpha, lightmap, overlay, readAlpha);
                }
            }
        };
    }
}

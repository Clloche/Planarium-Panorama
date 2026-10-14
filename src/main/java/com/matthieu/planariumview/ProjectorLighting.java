package com.matthieu.planariumview;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;

/**
 * Eclairage des parois et du plancher du bocal tels qu'Ars les dessine : un seul Planarium geant, a l'envers.
 *
 * Ars donne une seule valeur de lumiere a tout ce modele. On le redecoupe en carres d'un bloc,
 * et chaque coin prend la vraie lumiere des blocs du bocal a cet endroit. La lumiere qui entre par les parois
 * (voir JarWalls) dessine donc un degrade sur les parois et le plancher, comme sur un bloc normal.
 */
public final class ProjectorLighting {
    /** Interieur du bocal (les parois sont juste autour). */
    private static final int MIN_XZ = 0;
    private static final int MAX_XZ = 31;
    private static final int MIN_Y = 1;
    private static final int MAX_Y = 31;
    private static final int SIZE = 32;
    /** Nombre maximal de carres par cote d'une face (le modele fait 32 blocs). */
    private static final int MAX_STEPS = 32;

    /** Lumiere des blocs de l'interieur, relue toutes les demi-secondes. -1 = pas encore lue. */
    private static final byte[] CACHE = new byte[SIZE * SIZE * SIZE];
    private static long cacheTime = Long.MIN_VALUE;
    private static ClientLevel cacheLevel;
    /** Lumiere avec l'etiquette de couleur de Contraption Lights, par bloc et niveau (relue avec CACHE). */
    private static final java.util.Map<Long, Integer> TAGGED = new java.util.concurrent.ConcurrentHashMap<>();
    private static java.lang.invoke.MethodHandle colorTag;
    private static boolean colorTagMissing;
    private static boolean loggedActive;

    /**
     * Grille de lumiere deja calculee pour chaque quadrilatere du modele (le modele envoie toujours les memes, dans le
     * meme ordre). Chaque image, seulement un quadrilatere sur ROLLING est recalcule : la lumiere suit les changements
     * en quelques images, sans tout recalculer a chaque image (c'etait le plus gros cout du mod).
     */
    private static final int ROLLING = 8;
    private static final java.util.List<QuadCache> QUADS = new java.util.ArrayList<>();
    private static int quadIndex;
    private static int frame;

    private static final class QuadCache {
        double x;
        double y;
        double z;
        int stepsA;
        int stepsB;
        int[][] light;
    }

    private ProjectorLighting() {
    }

    /** Remplace le MultiBufferSource du dessin du projecteur, si la vraie luminosite est active dans ce bocal. */
    public static MultiBufferSource wrap(MultiBufferSource source) {
        if (!SkySync.projectorFollowsOutside()) {
            return source;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return source;
        }
        refreshCache(level);
        quadIndex = 0;
        frame++;
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        return type -> new Subdivider(source.getBuffer(type), level, camera);
    }

    private static void refreshCache(ClientLevel level) {
        long time = level.getGameTime();
        if (level != cacheLevel || time < cacheTime || time - cacheTime >= 10) {
            java.util.Arrays.fill(CACHE, (byte) -1);
            TAGGED.clear();
            CORNERS.clear();
            cacheLevel = level;
            cacheTime = time;
        }
    }

    private static int blockLight(ClientLevel level, int x, int y, int z) {
        x = Mth.clamp(x, MIN_XZ, MAX_XZ);
        y = Mth.clamp(y, MIN_Y, MAX_Y);
        z = Mth.clamp(z, MIN_XZ, MAX_XZ);
        int index = (x * SIZE + y) * SIZE + z;
        byte cached = CACHE[index];
        if (cached < 0) {
            cached = (byte) JarLight.blockLight(x, y, z, level.getBrightness(LightLayer.BLOCK, new BlockPos(x, y, z)));
            CACHE[index] = cached;
        }
        return cached;
    }

    /**
     * Lumiere lissee a un coin, dans le monde : moyenne des blocs de l'interieur qui touchent ce point.
     * Retourne la coordonnee de lightmap (0 a 240) pour la lumiere des blocs.
     */
    private static int smoothBlockLight(ClientLevel level, double wx, double wy, double wz) {
        int x0 = Mth.floor(wx - 0.5);
        int y0 = Mth.floor(wy - 0.5);
        int z0 = Mth.floor(wz - 0.5);
        int sum = 0;
        for (int dx = 0; dx <= 1; dx++) {
            for (int dy = 0; dy <= 1; dy++) {
                for (int dz = 0; dz <= 1; dz++) {
                    sum += blockLight(level, x0 + dx, y0 + dy, z0 + dz);
                }
            }
        }
        // sum / 8 niveaux, fois 16 pour la coordonnee de lightmap
        return Mth.clamp(sum * 2, 0, 240);
    }

    /**
     * Lumiere packee (ciel plein, blocs = blockU) avec la couleur de Contraption Lights a ce bloc de l'interieur,
     * comme Contraption Lights le fait pour les entites. Sans Contraption Lights, la lumiere reste sans couleur.
     */
    private static int tagged(ClientLevel level, int blockU, int bx, int by, int bz) {
        int packed = 240 << 16 | blockU;
        if (colorTagMissing || !Config.coloredLight()) {
            return packed;
        }
        int x = Mth.clamp(bx, MIN_XZ, MAX_XZ);
        int y = Mth.clamp(by, MIN_Y, MAX_Y);
        int z = Mth.clamp(bz, MIN_XZ, MAX_XZ);
        long key = ((long) ((x * SIZE + y) * SIZE + z) << 8) | blockU;
        Integer cached = TAGGED.get(key);
        if (cached != null) {
            return cached;
        }
        int result = packed;
        try {
            if (colorTag == null) {
                Class<?> feature = Class.forName("xyz.atmerek.contraptionlights.light.color.ColorLightFeature");
                colorTag = java.lang.invoke.MethodHandles.publicLookup().findStatic(feature, "tag",
                        java.lang.invoke.MethodType.methodType(int.class, int.class,
                                net.minecraft.world.level.BlockAndTintGetter.class, int.class, int.class, int.class));
            }
            result = (int) colorTag.invokeExact(packed, (net.minecraft.world.level.BlockAndTintGetter) level, x, y, z);
        } catch (Throwable e) {
            colorTagMissing = true;
        }
        TAGGED.put(key, result);
        return result;
    }

    private static java.lang.invoke.MethodHandle blendCorner;
    private static final java.util.Map<Long, Integer> CORNERS = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Lumiere d'un coin de carre, avec la couleur melangee des 4 blocs de l'interieur qui touchent ce coin,
     * comme Contraption Lights le fait pour ses propres blocs (sinon la couleur avance bloc par bloc, en escalier).
     * {@code axis} : l'axe perpendiculaire a la face (0 = x, 1 = y, 2 = z).
     */
    private static int corner(ClientLevel level, int blockU, double wx, double wy, double wz, int axis) {
        int base = 240 << 16 | blockU;
        if (colorTagMissing || !Config.coloredLight()) {
            return base;
        }
        long key = ((((long) Math.round(wx * 2) & 0xFFF) << 40) | (((long) Math.round(wy * 2) & 0xFFF) << 28)
                | (((long) Math.round(wz * 2) & 0xFFF) << 16) | ((long) axis << 8) | blockU);
        Integer cached = CORNERS.get(key);
        if (cached != null) {
            return cached;
        }
        double[] w = {wx, wy, wz};
        int[] lo = new int[3];
        int[] hi = new int[3];
        for (int i = 0; i < 3; i++) {
            if (i == axis) {
                lo[i] = hi[i] = Mth.floor(w[i]);
            } else {
                lo[i] = Mth.floor(w[i] - 0.5);
                hi[i] = lo[i] + 1;
            }
        }
        int[] tags = new int[4];
        int n = 0;
        for (int dx = lo[0]; dx <= hi[0]; dx++) {
            for (int dy = lo[1]; dy <= hi[1]; dy++) {
                for (int dz = lo[2]; dz <= hi[2]; dz++) {
                    if (n < 4) {
                        tags[n++] = tagged(level, blockU, dx, dy, dz);
                    }
                }
            }
        }
        int result = n > 0 ? tags[0] : base;
        try {
            if (blendCorner == null) {
                Class<?> feature = Class.forName("xyz.atmerek.contraptionlights.light.color.ColorLightFeature");
                blendCorner = java.lang.invoke.MethodHandles.publicLookup().findStatic(feature, "blendCorner",
                        java.lang.invoke.MethodType.methodType(int.class, int.class, int.class, int.class, int.class, int.class));
            }
            if (n == 4) {
                result = (int) blendCorner.invokeExact(base, tags[0], tags[1], tags[2], tags[3]);
            }
        } catch (Throwable e) {
            // sans melange : la couleur du premier bloc
        }
        CORNERS.put(key, result);
        return result;
    }

    /** Recoit les quadrilateres du modele et les renvoie decoupes, avec la lumiere de chaque coin. */
    private static final class Subdivider implements VertexConsumer {
        private final VertexConsumer out;
        private final ClientLevel level;
        private final Vec3 camera;
        private final float[][] pos = new float[4][3];
        private final int[] color = new int[4];
        private final float[][] uv = new float[4][2];
        private final int[] overlay = new int[4];
        private final float[][] normal = new float[4][3];
        private int count;
        private int current = -1;

        Subdivider(VertexConsumer out, ClientLevel level, Vec3 camera) {
            this.out = out;
            this.level = level;
            this.camera = camera;
        }

        @Override
        public void addVertex(float x, float y, float z, int argb, float u, float v, int packedOverlay, int packedLight,
                              float nx, float ny, float nz) {
            int i = count;
            pos[i][0] = x;
            pos[i][1] = y;
            pos[i][2] = z;
            color[i] = argb;
            uv[i][0] = u;
            uv[i][1] = v;
            overlay[i] = packedOverlay;
            normal[i][0] = nx;
            normal[i][1] = ny;
            normal[i][2] = nz;
            current = -1;
            if (++count == 4) {
                flush();
            }
        }

        // Chemin vertex par vertex (au cas ou un mod n'utiliserait pas la methode complete).
        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            current = count;
            pos[current][0] = x;
            pos[current][1] = y;
            pos[current][2] = z;
            color[current] = 0xFFFFFFFF;
            overlay[current] = 0;
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            if (current >= 0) {
                color[current] = (a & 255) << 24 | (r & 255) << 16 | (g & 255) << 8 | (b & 255);
            }
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            if (current >= 0) {
                uv[current][0] = u;
                uv[current][1] = v;
            }
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            if (current >= 0) {
                overlay[current] = (v & 0xFFFF) << 16 | (u & 0xFFFF);
            }
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float nx, float ny, float nz) {
            if (current >= 0) {
                normal[current][0] = nx;
                normal[current][1] = ny;
                normal[current][2] = nz;
                current = -1;
                if (++count == 4) {
                    flush();
                }
            }
            return this;
        }

        private void flush() {
            count = 0;
            if (!loggedActive) {
                loggedActive = true;
                org.slf4j.LoggerFactory.getLogger(ProjectorLighting.class)
                        .info("[Planarium Panorama] Parois et plancher du bocal : eclairage par bloc actif");
            }
            float lenA = dist(pos[0], pos[1]);
            float lenB = dist(pos[0], pos[3]);
            int stepsA = Mth.clamp(Mth.ceil(lenA - 0.01F), 1, MAX_STEPS);
            int stepsB = Mth.clamp(Mth.ceil(lenB - 0.01F), 1, MAX_STEPS);
            // Axe perpendiculaire a la face, pour choisir les 4 blocs qui touchent chaque coin.
            float nx = Math.abs(normal[0][0]);
            float ny = Math.abs(normal[0][1]);
            float nz = Math.abs(normal[0][2]);
            int axis = nx >= ny && nx >= nz ? 0 : ny >= nz ? 1 : 2;
            // Lumiere de chaque point de la grille : reprise du cache, ou recalculee (par tranches, voir ROLLING).
            int index = quadIndex++;
            double ox = pos[0][0] + camera.x;
            double oy = pos[0][1] + camera.y;
            double oz = pos[0][2] + camera.z;
            QuadCache cached = index < QUADS.size() ? QUADS.get(index) : null;
            boolean valid = cached != null && cached.stepsA == stepsA && cached.stepsB == stepsB
                    && Math.abs(cached.x - ox) < 0.01 && Math.abs(cached.y - oy) < 0.01 && Math.abs(cached.z - oz) < 0.01;
            if (valid && (index + frame) % ROLLING != 0) {
                emitGrid(stepsA, stepsB, cached.light);
                return;
            }
            int[][] light = new int[stepsA + 1][stepsB + 1];
            for (int a = 0; a <= stepsA; a++) {
                for (int b = 0; b <= stepsB; b++) {
                    float s = (float) a / stepsA;
                    float t = (float) b / stepsB;
                    double wx = lerp2(s, t, 0) + camera.x;
                    double wy = lerp2(s, t, 1) + camera.y;
                    double wz = lerp2(s, t, 2) + camera.z;
                    light[a][b] = corner(level, smoothBlockLight(level, wx, wy, wz), wx, wy, wz, axis);
                }
            }
            QuadCache entry = cached != null ? cached : new QuadCache();
            entry.x = ox;
            entry.y = oy;
            entry.z = oz;
            entry.stepsA = stepsA;
            entry.stepsB = stepsB;
            entry.light = light;
            if (index < QUADS.size()) {
                QUADS.set(index, entry);
            } else {
                QUADS.add(entry);
            }
            emitGrid(stepsA, stepsB, light);
        }

        private void emitGrid(int stepsA, int stepsB, int[][] light) {
            for (int a = 0; a < stepsA; a++) {
                for (int b = 0; b < stepsB; b++) {
                    float s0 = (float) a / stepsA;
                    float s1 = (float) (a + 1) / stepsA;
                    float t0 = (float) b / stepsB;
                    float t1 = (float) (b + 1) / stepsB;
                    emit(s0, t0, light[a][b]);
                    emit(s1, t0, light[a + 1][b]);
                    emit(s1, t1, light[a + 1][b + 1]);
                    emit(s0, t1, light[a][b + 1]);
                }
            }
        }

        private void emit(float s, float t, int packedLight) {
            out.addVertex(lerp2(s, t, 0), lerp2(s, t, 1), lerp2(s, t, 2))
                    .setColor(color[0])
                    .setUv(lerpUv(s, t, 0), lerpUv(s, t, 1))
                    .setOverlay(overlay[0])
                    .setUv2(packedLight & 0xFFFF, packedLight >>> 16)
                    .setNormal(normal[0][0], normal[0][1], normal[0][2]);
        }

        /** Point du quadrilatere : v0 en (0,0), v1 en (1,0), v2 en (1,1), v3 en (0,1). */
        private float lerp2(float s, float t, int axis) {
            return (1 - s) * (1 - t) * pos[0][axis] + s * (1 - t) * pos[1][axis]
                    + s * t * pos[2][axis] + (1 - s) * t * pos[3][axis];
        }

        private float lerpUv(float s, float t, int axis) {
            return (1 - s) * (1 - t) * uv[0][axis] + s * (1 - t) * uv[1][axis]
                    + s * t * uv[2][axis] + (1 - s) * t * uv[3][axis];
        }

        private static float dist(float[] p, float[] q) {
            float dx = p[0] - q[0];
            float dy = p[1] - q[1];
            float dz = p[2] - q[2];
            return Mth.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }
}

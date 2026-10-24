package com.matthieu.planariumview.client.jar;

import com.matthieu.planariumview.client.sky.SkySync;
import com.matthieu.planariumview.config.Config;

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
     * meme ordre). La lumiere des blocs n'est relue que toutes les 10 ticks (refreshCache, nouvelle generation) : chaque
     * quadrilatere est recalcule une seule fois par generation, reparti sur ROLLING images (pas de pointe).
     */
    private static final int ROLLING = 8;
    /** Augmente a chaque relecture de la lumiere : les grilles d'une generation plus ancienne sont a verifier. */
    private static int lightGeneration;
    private static final java.util.List<QuadCache> QUADS = new java.util.ArrayList<>();
    private static int quadIndex;
    private static int frame;

    /**
     * Un quadrilatere du modele, deja decoupe : ses sommets finaux en coordonnees du monde (fixes, le projecteur ne
     * bouge pas). Chaque image, on les renvoie tels quels, decales de la camera : plus de decoupage, de fusion ni
     * d'interpolation a refaire. Refait seulement quand la lumiere de sa grille change, ou quand le modele change
     * (position, texture apres un rechargement des ressources, couleur).
     */
    private static final class QuadCache {
        /** Les 4 coins du quadrilatere dans le monde (x, y, z) et leurs UV : le moindre changement refait les sommets. */
        final double[] corners = new double[12];
        final float[] cornerUv = new float[8];
        int stepsA;
        int stepsB;
        int[][] light;
        /** Generation de lumiere de la derniere verification de la grille (voir lightGeneration). */
        int generation = -1;
        int color;
        int overlay;
        float nx, ny, nz;
        /** Sommets : x, y, z du monde (3 par sommet), u, v (2 par sommet), lumiere (1 par sommet). */
        double[] xyz = new double[96];
        float[] uv = new float[64];
        int[] lights = new int[32];
        int count;

        void add(double wx, double wy, double wz, float u, float v, int light) {
            if (count == lights.length) {
                xyz = java.util.Arrays.copyOf(xyz, xyz.length * 2);
                uv = java.util.Arrays.copyOf(uv, uv.length * 2);
                lights = java.util.Arrays.copyOf(lights, lights.length * 2);
            }
            xyz[count * 3] = wx;
            xyz[count * 3 + 1] = wy;
            xyz[count * 3 + 2] = wz;
            uv[count * 2] = u;
            uv[count * 2 + 1] = v;
            lights[count++] = light;
        }
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

    /**
     * Sortie ou changement de bocal, deconnexion : oublie les caches de lumiere et l'ancien monde client. Sinon,
     * cacheLevel gardait tout l'ancien monde (chunks, entites) en memoire jusqu'au prochain bocal.
     */
    public static void clear() {
        cacheLevel = null;
        cacheTime = Long.MIN_VALUE;
        java.util.Arrays.fill(CACHE, (byte) -1);
        TAGGED.clear();
        CORNERS.clear();
        QUADS.clear();
        quadIndex = 0;
        lightGeneration++;
    }

    private static void refreshCache(ClientLevel level) {
        long time = level.getGameTime();
        if (level != cacheLevel || time < cacheTime || time - cacheTime >= 10) {
            java.util.Arrays.fill(CACHE, (byte) -1);
            TAGGED.clear();
            CORNERS.clear();
            cacheLevel = level;
            cacheTime = time;
            lightGeneration++;
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
        if (colorTagMissing || !Config.jarColoredLight()) {
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
        if (colorTagMissing || !Config.jarColoredLight()) {
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
        /** Quadrilatere dont on construit les sommets (pendant flush seulement). */
        private QuadCache building;

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
            QuadCache cached = index < QUADS.size() ? QUADS.get(index) : null;
            boolean valid = cached != null && cached.stepsA == stepsA && cached.stepsB == stepsB
                    && sameShape(cached) && sameLook(cached);
            // Deja verifie avec cette lumiere, ou pas son tour dans cette image : les sommets gardes.
            if (valid && (cached.generation == lightGeneration || (index + frame) % ROLLING != 0)) {
                replay(cached);
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
            if (valid && java.util.Arrays.deepEquals(cached.light, light)) {
                // Rien n'a change : les sommets gardes sont encore bons.
                cached.generation = lightGeneration;
                replay(cached);
                return;
            }
            QuadCache entry = cached != null ? cached : new QuadCache();
            for (int i = 0; i < 4; i++) {
                entry.corners[i * 3] = pos[i][0] + camera.x;
                entry.corners[i * 3 + 1] = pos[i][1] + camera.y;
                entry.corners[i * 3 + 2] = pos[i][2] + camera.z;
                entry.cornerUv[i * 2] = uv[i][0];
                entry.cornerUv[i * 2 + 1] = uv[i][1];
            }
            entry.stepsA = stepsA;
            entry.stepsB = stepsB;
            entry.light = light;
            entry.generation = lightGeneration;
            entry.color = color[0];
            entry.overlay = overlay[0];
            entry.nx = normal[0][0];
            entry.ny = normal[0][1];
            entry.nz = normal[0][2];
            entry.count = 0;
            building = entry;
            emitMerged(stepsA, stepsB, light, merge(stepsA, stepsB, light));
            building = null;
            if (index < QUADS.size()) {
                QUADS.set(index, entry);
            } else {
                QUADS.add(entry);
            }
            replay(entry);
        }

        /**
         * Memes 4 coins dans le monde que le quadrilatere garde. Les positions recues dependent de la camera (arrondis en
         * float) : une tolerance de 0,01 bloc evite de tout refaire a chaque mouvement.
         */
        private boolean sameShape(QuadCache c) {
            for (int i = 0; i < 4; i++) {
                if (Math.abs(c.corners[i * 3] - (pos[i][0] + camera.x)) >= 0.01
                        || Math.abs(c.corners[i * 3 + 1] - (pos[i][1] + camera.y)) >= 0.01
                        || Math.abs(c.corners[i * 3 + 2] - (pos[i][2] + camera.z)) >= 0.01) return false;
            }
            return true;
        }

        /** Meme apparence : UV des 4 coins (changent au rechargement des ressources), couleur, overlay, normale. */
        private boolean sameLook(QuadCache c) {
            for (int i = 0; i < 4; i++) {
                if (c.cornerUv[i * 2] != uv[i][0] || c.cornerUv[i * 2 + 1] != uv[i][1]) return false;
            }
            return c.color == color[0] && c.overlay == overlay[0]
                    && c.nx == normal[0][0] && c.ny == normal[0][1] && c.nz == normal[0][2];
        }

        /** Renvoie les sommets gardes, relatifs a la camera de cette image (methode complete : chemin rapide). */
        private void replay(QuadCache c) {
            double[] xyz = c.xyz;
            float[] uvs = c.uv;
            int[] lights = c.lights;
            for (int i = 0; i < c.count; i++) {
                out.addVertex((float) (xyz[i * 3] - camera.x), (float) (xyz[i * 3 + 1] - camera.y),
                        (float) (xyz[i * 3 + 2] - camera.z), c.color, uvs[i * 2], uvs[i * 2 + 1], c.overlay,
                        lights[i], c.nx, c.ny, c.nz);
            }
        }

        /**
         * Emet la grille en regroupant les carres dont les 4 coins ont la meme lumiere en grands rectangles :
         * une paroi uniformement eclairee devient quelques quadrilateres au lieu de 32 x 32. Seules les zones ou la
         * lumiere varie (pres d'une lampe, d'une paroi eclairee de dehors) restent decoupees bloc par bloc.
         * Le resultat est identique : sur un carre a lumiere constante, l'interpolation ne change rien.
         */
        private void emitMerged(int stepsA, int stepsB, int[][] light, int[] flat) {
            boolean[][] covered = new boolean[stepsA][stepsB];
            for (int i = 0; i + 3 < flat.length; i += 4) {
                int a0 = flat[i], b0 = flat[i + 1], a1 = flat[i + 2], b1 = flat[i + 3];
                for (int a = a0; a < a1; a++) {
                    for (int b = b0; b < b1; b++) {
                        covered[a][b] = true;
                    }
                }
                int value = light[a0][b0];
                int w = a1 - a0, h = b1 - b0;
                if (w * h <= 2 * (w + h)) {
                    // Petit rectangle : ses carres un par un coutent moins que l'eventail.
                    for (int a = a0; a < a1; a++) {
                        for (int b = b0; b < b1; b++) {
                            cell(a, b, stepsA, stepsB, value, value, value, value);
                        }
                    }
                } else {
                    fan(a0, b0, a1, b1, stepsA, stepsB, value);
                }
            }
            for (int a = 0; a < stepsA; a++) {
                for (int b = 0; b < stepsB; b++) {
                    if (covered[a][b]) continue;
                    cell(a, b, stepsA, stepsB, light[a][b], light[a + 1][b], light[a + 1][b + 1], light[a][b + 1]);
                }
            }
        }

        private void cell(int a, int b, int stepsA, int stepsB, int l00, int l10, int l11, int l01) {
            float s0 = (float) a / stepsA, s1 = (float) (a + 1) / stepsA;
            float t0 = (float) b / stepsB, t1 = (float) (b + 1) / stepsB;
            emit(s0, t0, l00);
            emit(s1, t0, l10);
            emit(s1, t1, l11);
            emit(s0, t1, l01);
        }

        /**
         * Rectangle a lumiere constante sans fentes : un seul grand quadrilatere avait ses coins au milieu des bords des
         * petits carres voisins (jonctions en T). Arrondis differemment, ces bords laissaient de petites fentes, et le
         * fond passait au travers (pixels blancs pres du joueur en bougeant). Ici, un eventail depuis son centre passe
         * par tous les points de la grille sur son contour, comme les carres voisins. Chaque triangle est un
         * quadrilatere dont le dernier coin est repete (meme sens que le rectangle).
         */
        private void fan(int a0, int b0, int a1, int b1, int stepsA, int stepsB, int value) {
            float sc = (a0 + a1) * 0.5F / stepsA, tc = (b0 + b1) * 0.5F / stepsB;
            int total = 2 * ((a1 - a0) + (b1 - b0));
            int pa = a0, pb = b0;
            for (int i = 0; i < total; i++) {
                int na = pa, nb = pb;
                // Contour dans le sens du rectangle : (a0,b0) -> (a1,b0) -> (a1,b1) -> (a0,b1) -> (a0,b0).
                if (pb == b0 && pa < a1) na++;
                else if (pa == a1 && pb < b1) nb++;
                else if (pb == b1 && pa > a0) na--;
                else nb--;
                emit(sc, tc, value);
                emit((float) pa / stepsA, (float) pb / stepsB, value);
                emit((float) na / stepsA, (float) nb / stepsB, value);
                emit((float) na / stepsA, (float) nb / stepsB, value);
                pa = na;
                pb = nb;
            }
        }

        private static boolean flatCell(int[][] light, int a, int b) {
            int v = light[a][b];
            return light[a + 1][b] == v && light[a][b + 1] == v && light[a + 1][b + 1] == v;
        }

        /** Rectangles (en carres de la grille) dont toute la lumiere est la meme, trouves une fois par grille. */
        private static int[] merge(int stepsA, int stepsB, int[][] light) {
            boolean[][] used = new boolean[stepsA][stepsB];
            int[] rects = new int[16];
            int n = 0;
            for (int b = 0; b < stepsB; b++) {
                for (int a = 0; a < stepsA; a++) {
                    if (used[a][b] || !flatCell(light, a, b)) continue;
                    int value = light[a][b];
                    int a1 = a;
                    while (a1 + 1 < stepsA && !used[a1 + 1][b] && flatCell(light, a1 + 1, b) && light[a1 + 1][b] == value) {
                        a1++;
                    }
                    int b1 = b;
                    grow:
                    while (b1 + 1 < stepsB) {
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

        /** Ajoute un sommet au quadrilatere en construction (voir replay pour l'envoi). */
        private void emit(float s, float t, int packedLight) {
            building.add(snap(lerp2(s, t, 0), camera.x), snap(lerp2(s, t, 1), camera.y), snap(lerp2(s, t, 2), camera.z),
                    lerpUv(s, t, 0), lerpUv(s, t, 1), packedLight);
        }

        /**
         * Point du monde sur une grille fixe (1/1024 de bloc) : deux faces voisines du modele calculent leur coin commun
         * depuis des coins differents, et un arrondi different laissait une fente entre elles.
         */
        private static double snap(float relative, double camera) {
            return Math.rint((relative + camera) * 1024.0) / 1024.0;
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

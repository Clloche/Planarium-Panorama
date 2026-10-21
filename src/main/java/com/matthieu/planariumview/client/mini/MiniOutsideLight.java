package com.matthieu.planariumview.client.mini;

import com.matthieu.planariumview.client.jar.JarColor;
import com.matthieu.planariumview.client.jar.JarLight;
import com.matthieu.planariumview.client.jar.JarWalls;
import com.matthieu.planariumview.client.jar.WallColors;
import com.matthieu.planariumview.client.scenery.LitRenderingWorld;
import com.matthieu.planariumview.compat.ColorCompat;
import com.matthieu.planariumview.config.Config;

import com.hollingsworth.arsnouveau.client.renderer.tile.PlanariumRenderer;
import com.hollingsworth.arsnouveau.common.block.tile.PlanariumTile;
import com.mojang.blaze3d.vertex.VertexBuffer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;

import java.util.Arrays;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Cote client : la miniature vue de l'exterieur recoit la lumiere des torches et lampes a cote du Planarium,
 * repartie comme a l'interieur du bocal (voir JarLight). On la mesure dans le monde du client, autour du Planarium.
 */
public final class MiniOutsideLight {
    /** Derniere lumiere mesuree autour de chaque Planarium (10 faces + le centre). */
    private static final Map<ResourceKey<Level>, int[]> MEASURED = new WeakHashMap<>();

    private MiniOutsideLight() {
    }

    /**
     * Aussi avec "Ciel et decor" : la miniature est vue de l'exterieur, a travers le verre, comme le decor geant.
     * Elle prend donc la lumiere de l'endroit ou se trouve le Planarium (sombre dans une grotte), meme si l'interieur
     * du bocal reste completement eclaire dans ce mode.
     */
    private static boolean enabled() {
        return Config.realLighting();
    }

    /** 10 faces, puis le centre, puis la lumiere du ciel au Planarium. */
    public static int[] measure(Level level, BlockPos planarium) {
        BlockPos[] positions = JarWalls.samplePositions(planarium);
        int[] values = new int[JarWalls.FACES + 2];
        for (int i = 0; i < JarWalls.FACES; i++) {
            values[i] = level.getBrightness(LightLayer.BLOCK, positions[i]);
        }
        values[JarWalls.FACES] = level.getBrightness(LightLayer.BLOCK, planarium);
        // Sans ciel (le Nether...) : la miniature n'a pas de lumiere du ciel, comme les blocs autour d'elle.
        values[JarWalls.FACES + 1] = level.dimensionType().hasSkyLight() && !level.dimensionType().hasCeiling()
                ? level.getBrightness(LightLayer.SKY, planarium) : 0;
        return values;
    }

    /** Chaque seconde : si la lumiere autour du Planarium a change, Ars reconstruit la miniature. */
    public static void check(PlanariumTile tile) {
        Level level = tile.getLevel();
        if (level == null || tile.key == null || level.getGameTime() % 20 != 0) {
            return;
        }
        int[] now = enabled() ? measure(level, tile.getBlockPos()) : null;
        int[] before = MEASURED.get(tile.key);
        if (Arrays.equals(now, before)) {
            return;
        }
        MEASURED.put(tile.key, now);
        if (before == null) {
            return; // premiere mesure : la miniature vient d'etre construite avec cette lumiere
        }
        PlanariumRenderer.StructureRenderData old = PlanariumRenderer.structureRenderData.remove(tile.key);
        if (old != null) {
            try {
                PlanariumRenderer.clearByteBuffers(old);
                for (VertexBuffer buffer : old.vertexBuffers.values()) {
                    buffer.close();
                }
            } catch (Exception ignored) {
                // la miniature sera simplement reconstruite
            }
        }
    }

    /** Au debut de la construction de la miniature : on donne la lumiere du dehors a son faux monde. */
    public static void apply(PlanariumRenderer.StructureRenderData data, Level level, BlockPos renderPos) {
        JarColor.clearMini();
        if (data == null || level == null || !(data.fakeRenderingWorld instanceof LitRenderingWorld lit)) {
            return;
        }
        // Ars passe parfois la position du Planarium, parfois celle juste au-dessus.
        BlockPos at = level.getBlockEntity(renderPos) instanceof PlanariumTile ? renderPos : renderPos.below();
        // Couleurs de l'herbe, des feuilles et de l'eau : celles du biome ou se trouve le Planarium, comme dans le bocal.
        lit.setTintBiome(Config.biomeColors() ? level.getBiome(at) : null);
        if (!enabled()) {
            lit.setOutsideLight(null);
            lit.setOutsideSky(-1);
            return;
        }
        BlockPos planarium = at;
        int[] values = measure(level, planarium);
        int[] faces = Arrays.copyOf(values, JarWalls.FACES);
        boolean dark = true;
        for (int i = 0; i <= JarWalls.FACES; i++) {
            dark &= values[i] <= 0;
        }
        byte[] table = dark ? null : JarLight.computeTable(faces, values[JarWalls.FACES]);
        lit.setOutsideLight(table);
        lit.setOutsideSky(values[JarWalls.FACES + 1]);
        // Couleur de cette lumiere (feu de camp vert a cote du Planarium...), comme a l'interieur du bocal (JarColor).
        if (table != null && Config.coloredLight() && ColorCompat.present()) {
            int[] sources = JarWalls.findSources(level, planarium);
            int[] colors = new int[JarWalls.FACES];
            for (int i = 0; i < JarWalls.FACES; i++) {
                colors[i] = sources[i] < 0 ? WallColors.WARM : WallColors.colorOf(sources[i]);
            }
            JarColor.setMini(JarColor.buildCells(faces, colors, table));
        }
    }
}

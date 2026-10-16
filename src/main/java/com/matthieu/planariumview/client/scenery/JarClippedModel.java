package com.matthieu.planariumview.client.scenery;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Decor geant : certains blocs ont des morceaux de modele qui depassent de leur case (les racines qui pendent de
 * Spelunkery, par exemple). A cote du Planarium, ces morceaux entraient dans la case du Planarium, c'est-a-dire
 * a l'interieur du bocal. On retire les faces qui entrent dans la case du Planarium, meme en partie.
 */
final class JarClippedModel extends BakedModelWrapper<BakedModel> {
    private final float dx;
    private final float dy;
    private final float dz;

    /** offset = position du Planarium moins celle du bloc (chaque composante entre -1 et 1). */
    JarClippedModel(BakedModel model, BlockPos offset) {
        super(model);
        this.dx = offset.getX();
        this.dy = offset.getY();
        this.dz = offset.getZ();
    }

    /** Vrai si le bloc touche la case du Planarium (y compris en diagonale). */
    static boolean touches(BlockPos pos, BlockPos planarium) {
        return Math.abs(pos.getX() - planarium.getX()) <= 1 && Math.abs(pos.getY() - planarium.getY()) <= 1
                && Math.abs(pos.getZ() - planarium.getZ()) <= 1 && !pos.equals(planarium);
    }

    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand,
                                    ModelData data, @Nullable RenderType renderType) {
        List<BakedQuad> quads = super.getQuads(state, side, rand, data, renderType);
        List<BakedQuad> kept = null;
        for (int i = 0; i < quads.size(); i++) {
            BakedQuad quad = quads.get(i);
            boolean inside = insidePlanarium(quad);
            if (inside && kept == null) {
                kept = new ArrayList<>(quads.subList(0, i));
            } else if (!inside && kept != null) {
                kept.add(quad);
            }
        }
        return kept != null ? kept : quads;
    }

    /** Vrai si la face entre dans la case du Planarium, meme en partie (les faces posees sur son bord restent). */
    private boolean insidePlanarium(BakedQuad quad) {
        int[] vertices = quad.getVertices();
        int stride = vertices.length / 4;
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (int v = 0; v < 4; v++) {
            float x = Float.intBitsToFloat(vertices[v * stride]) - dx;
            float y = Float.intBitsToFloat(vertices[v * stride + 1]) - dy;
            float z = Float.intBitsToFloat(vertices[v * stride + 2]) - dz;
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }
        float e = 0.001F;
        return maxX > e && minX < 1 - e && maxY > e && minY < 1 - e && maxZ > e && minZ < 1 - e;
    }
}

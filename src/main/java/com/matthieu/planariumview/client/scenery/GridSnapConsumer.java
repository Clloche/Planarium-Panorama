package com.matthieu.planariumview.client.scenery;

import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * Remet chaque sommet du decor geant sur une grille fixe (1/4096 de bloc), en coordonnees de poche.
 * Chaque bloc est place par sa propre matrice (position x 32,16) : en float, le coin commun de deux blocs voisins
 * tombait a des valeurs tres legerement differentes, et de petites fentes laissaient passer des pixels du ciel ou du
 * brouillard (scintillement blanc en bougeant, surtout dans le noir). Sur la grille, un meme coin donne toujours
 * exactement le meme float, donc les blocs se touchent sans fente.
 */
final class GridSnapConsumer implements VertexConsumer {
    private static final double GIANT = ClientView.GIANT;
    private static final double OFFSET_XZ = -(double) ClientView.ARS_OFFSET * GIANT;
    private static final double OFFSET_Y = OFFSET_XZ - ClientView.Y_SHIFT;
    private static final double STEPS = 4096.0;

    private final VertexConsumer inner;

    GridSnapConsumer(VertexConsumer inner) {
        this.inner = inner;
    }

    private static float snap(float value, double offset) {
        double block = (value - offset) / GIANT;
        return (float) (offset + Math.rint(block * STEPS) / STEPS * GIANT);
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        inner.addVertex(snap(x, OFFSET_XZ), snap(y, OFFSET_Y), snap(z, OFFSET_XZ));
        return this;
    }

    @Override
    public void addVertex(float x, float y, float z, int color, float u, float v, int overlay, int light,
                          float normalX, float normalY, float normalZ) {
        inner.addVertex(snap(x, OFFSET_XZ), snap(y, OFFSET_Y), snap(z, OFFSET_XZ), color, u, v, overlay, light,
                normalX, normalY, normalZ);
    }

    @Override
    public VertexConsumer setColor(int red, int green, int blue, int alpha) {
        inner.setColor(red, green, blue, alpha);
        return this;
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
        inner.setUv(u, v);
        return this;
    }

    @Override
    public VertexConsumer setUv1(int u, int v) {
        inner.setUv1(u, v);
        return this;
    }

    @Override
    public VertexConsumer setUv2(int u, int v) {
        inner.setUv2(u, v);
        return this;
    }

    @Override
    public VertexConsumer setNormal(float x, float y, float z) {
        inner.setNormal(x, y, z);
        return this;
    }
}

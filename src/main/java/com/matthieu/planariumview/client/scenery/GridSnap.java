package com.matthieu.planariumview.client.scenery;

import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormatElement;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Remet chaque sommet du decor geant sur une grille fixe (1/4096 de bloc), en coordonnees de poche.
 * Chaque bloc est place par sa propre matrice (position x 32,16) : en float, le coin commun de deux blocs voisins
 * tombait a des valeurs tres legerement differentes, et de petites fentes laissaient passer des pixels du ciel ou du
 * brouillard (scintillement blanc en bougeant, surtout dans le noir). Sur la grille, un meme coin donne toujours
 * exactement le meme float, donc les blocs se touchent sans fente.
 *
 * Applique au maillage fini, juste avant son envoi a la carte graphique (et avant le tri du verre et de l'eau), plutot
 * qu'en enveloppant le tampon pendant la construction : l'enveloppe faisait passer chaque face par le chemin generique
 * de VertexConsumer au lieu de celui du tampon lui-meme, que d'autres mods (Sodium, Contraption Lights) peuvent modifier.
 */
final class GridSnap {
    private static final double GIANT = ClientView.GIANT;
    private static final double OFFSET_XZ = -(double) ClientView.ARS_OFFSET * GIANT;
    private static final double OFFSET_Y = OFFSET_XZ - ClientView.Y_SHIFT;
    private static final double STEPS = 4096.0;

    private GridSnap() {
    }

    private static float snap(float value, double offset) {
        double block = (value - offset) / GIANT;
        return (float) (offset + Math.rint(block * STEPS) / STEPS * GIANT);
    }

    /** Remet sur la grille les positions de tous les sommets du maillage (en place). */
    static void snapMesh(MeshData mesh) {
        var state = mesh.drawState();
        int stride = state.format().getVertexSize();
        int offset = state.format().getOffset(VertexFormatElement.POSITION);
        if (offset < 0) return;
        ByteBuffer vertices = mesh.vertexBuffer().duplicate().order(ByteOrder.nativeOrder());
        int base = vertices.position();
        for (int vertex = 0; vertex < state.vertexCount(); vertex++) {
            int at = base + vertex * stride + offset;
            vertices.putFloat(at, snap(vertices.getFloat(at), OFFSET_XZ));
            vertices.putFloat(at + 4, snap(vertices.getFloat(at + 4), OFFSET_Y));
            vertices.putFloat(at + 8, snap(vertices.getFloat(at + 8), OFFSET_XZ));
        }
    }
}

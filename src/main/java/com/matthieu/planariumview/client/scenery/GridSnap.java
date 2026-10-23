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
    /**
     * Debordement de chaque face sur ses voisines (1/8192 de bloc geant, environ 0,004 unite : bien sous le pixel, mais
     * des dizaines de fois l'erreur d'arrondi qui ouvre les fentes). Assez petit pour que deux faces coplanaires qui se
     * recouvrent ne scintillent pas (z-fighting) sur une bande visible.
     * La grille ferme les fentes entre coins communs, mais pas les jonctions en T : le coin d'une face qui tombe au
     * milieu du bord d'une autre (dalle contre bloc plein, modeles de mods). Derriere la surface du decor, il n'y a
     * que le fond (les blocs caches ne sont pas captures) : pres du bocal, ces fentes laissaient voir des pixels
     * blancs en bougeant. Chaque bord est pousse un peu vers l'exterieur, et les faces se recouvrent.
     */
    private static final float SEAL = (float) (GIANT / 8192.0);

    private GridSnap() {
    }

    private static float snap(float value, double offset) {
        double block = (value - offset) / GIANT;
        return (float) (offset + Math.rint(block * STEPS) / STEPS * GIANT);
    }

    /** Remet sur la grille les positions de tous les sommets du maillage (en place). */
    static void snapMesh(MeshData mesh) {
        snapMesh(mesh, false);
    }

    /**
     * Comme snapMesh ; {@code seal} : en plus, chaque face deborde un peu sur ses voisines (voir SEAL). Pas pour les
     * couches translucides : le recouvrement y doublerait la transparence en fines lignes.
     */
    static void snapMesh(MeshData mesh, boolean seal) {
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
        if (seal && state.mode() == com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS) seal(vertices, base, stride, offset,
                state.vertexCount());
    }

    /** Pousse chaque coin le long de ses deux bords, vers l'exterieur de la face (triangles en quad compris). */
    private static void seal(ByteBuffer vertices, int base, int stride, int offset, int count) {
        float[] x = new float[4], y = new float[4], z = new float[4];
        for (int quad = 0; quad + 3 < count; quad += 4) {
            for (int i = 0; i < 4; i++) {
                int at = base + (quad + i) * stride + offset;
                x[i] = vertices.getFloat(at);
                y[i] = vertices.getFloat(at + 4);
                z[i] = vertices.getFloat(at + 8);
            }
            for (int i = 0; i < 4; i++) {
                float mx = 0, my = 0, mz = 0;
                for (int other : new int[]{(i + 3) & 3, (i + 1) & 3}) {
                    float dx = x[i] - x[other], dy = y[i] - y[other], dz = z[i] - z[other];
                    float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                    // Coin repete (triangle) : pas de bord dans cette direction.
                    if (length < 1.0E-4F) continue;
                    mx += dx / length;
                    my += dy / length;
                    mz += dz / length;
                }
                int at = base + (quad + i) * stride + offset;
                vertices.putFloat(at, x[i] + mx * SEAL);
                vertices.putFloat(at + 4, y[i] + my * SEAL);
                vertices.putFloat(at + 8, z[i] + mz * SEAL);
            }
        }
    }
}

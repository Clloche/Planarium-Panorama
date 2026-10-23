package com.matthieu.planariumview.client.scenery;

import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Frustum culling du decor geant : on ne dessine pas les sections hors du champ de la camera. Sodium, EntityCulling ou
 * MoreCulling ne voient pas ces maillages (dessines par le mod, pas par le rendu du monde) : il faut le faire ici.
 *
 * Chaque section garde la boite de ses vrais sommets, calculee a sa construction (un modele qui depasse de sa case est
 * donc couvert). Le test se fait dans le repere des sommets, avec la meme matrice que le dessin (projection x vue du
 * modele, placement Sable ou du vol compris) : JOML en tire les 6 plans du champ de vision directement dans ce repere,
 * sans approximer une boite tournee ni soustraire deux fois la camera. Le test est prudent : une boite douteuse est
 * gardee.
 */
final class SceneryFrustum {
    /** Marge autour des boites, en unites des sommets (rien ne disparait au bord de l'ecran). */
    private static final float MARGIN = 1.0F;

    private final FrustumIntersection frustum = new FrustumIntersection();
    private final Matrix4f matrix = new Matrix4f();
    private boolean valid;

    /** Boite vide, a agrandir avec {@link #include}. Ordre : min x, y, z, max x, y, z. */
    static float[] emptyBox() {
        return new float[]{Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
    }

    /** Agrandit {@code box} avec les positions des sommets du maillage (avant son envoi a la carte graphique). */
    static void include(float[] box, MeshData mesh) {
        if (box == null) return;
        var state = mesh.drawState();
        int stride = state.format().getVertexSize();
        int offset = state.format().getOffset(VertexFormatElement.POSITION);
        if (offset < 0) return;
        ByteBuffer vertices = mesh.vertexBuffer().duplicate().order(ByteOrder.nativeOrder());
        int base = vertices.position();
        for (int vertex = 0; vertex < state.vertexCount(); vertex++) {
            int at = base + vertex * stride + offset;
            float x = vertices.getFloat(at), y = vertices.getFloat(at + 4), z = vertices.getFloat(at + 8);
            box[0] = Math.min(box[0], x);
            box[1] = Math.min(box[1], y);
            box[2] = Math.min(box[2], z);
            box[3] = Math.max(box[3], x);
            box[4] = Math.max(box[4], y);
            box[5] = Math.max(box[5], z);
        }
    }

    /**
     * Met a jour le champ de vision pour cette matrice de dessin. Vrai s'il a change depuis le dernier appel (la liste
     * des sections visibles est alors a refaire) : une seule fois par image pour toutes les couches.
     */
    boolean update(Matrix4f pose, Matrix4f projection) {
        Matrix4f next = new Matrix4f(projection).mul(pose);
        if (valid && next.equals(matrix, 1.0E-6F)) return false;
        matrix.set(next);
        frustum.set(matrix, false);
        valid = true;
        return true;
    }

    /** Force un nouveau calcul de la liste au prochain {@link #update} (sections ajoutees ou retirees). */
    void invalidate() {
        valid = false;
    }

    /** Vrai si la boite peut etre visible (boite vide ou inconnue : gardee). */
    boolean visible(float[] box) {
        if (box == null || box[0] > box[3]) return true;
        return frustum.testAab(box[0] - MARGIN, box[1] - MARGIN, box[2] - MARGIN,
                box[3] + MARGIN, box[4] + MARGIN, box[5] + MARGIN);
    }
}

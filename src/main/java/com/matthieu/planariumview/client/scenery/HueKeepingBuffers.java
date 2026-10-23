package com.matthieu.planariumview.client.scenery;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

/**
 * Blocs speciaux du decor geant (boites a outils de Create...) : certains rendus recalculent la lumiere qu'on leur
 * donne a partir des seuls niveaux de lumiere (comme les modeles animes de Create), et perdent la couleur de
 * Contraption Lights qu'elle contient. On remet la couleur du bloc sur leurs sommets qui n'en ont plus.
 */
final class HueKeepingBuffers {
    /** Bits de couleur de Contraption Lights dans chaque moitie de la lumiere (sans les niveaux, 0xF0). */
    private static final int LEVEL_BITS = 0xF0;

    private HueKeepingBuffers() {
    }

    /** {@code buffers}, ou une enveloppe qui garde la couleur de {@code light} si elle en a une. */
    static MultiBufferSource of(MultiBufferSource buffers, int light) {
        int hueU = light & 0xFFFF & ~LEVEL_BITS;
        int hueV = (light >>> 16) & ~LEVEL_BITS;
        if (hueU == 0 && hueV == 0) {
            return buffers;
        }
        return type -> new Consumer(buffers.getBuffer(type), hueU, hueV);
    }

    private record Consumer(VertexConsumer inner, int hueU, int hueV) implements VertexConsumer {
        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            inner.addVertex(x, y, z);
            return this;
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
            // Pleine lumiere (LightTexture.FULL_BRIGHT : flammes, trappe allumee, tuyaux chauds) : une lueur, pas une
            // surface eclairee. Avec la couleur du bloc, elle prenait la teinte des torches et paraissait terne.
            if ((u & ~LEVEL_BITS) == 0 && (v & ~LEVEL_BITS) == 0 && !(u == LEVEL_BITS && v == LEVEL_BITS)) {
                // Lumiere sans couleur : celle du bloc.
                u |= hueU;
                v |= hueV;
            }
            inner.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            inner.setNormal(x, y, z);
            return this;
        }
    }
}

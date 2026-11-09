package com.matthieu.planariumview.integration.ars_nouveau;

import com.hollingsworth.arsnouveau.client.renderer.tile.PlanariumRenderer.StructureRenderData;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * Libere la memoire native et les tampons GPU d'un rendu d'Ars (miniature ou decor). Ars 5.13.4 a remplace
 * {@code PlanariumRenderer.clearByteBuffers} par {@code StructureRenderData.close()} : on fait le meme travail avec
 * les champs publics, presents dans les deux versions. Fermer deux fois est sans danger.
 */
public final class ArsRenderData {
    private static final Logger LOGGER = LogUtils.getLogger();

    private ArsRenderData() {
    }

    public static void free(StructureRenderData data) {
        if (data == null) {
            return;
        }
        try {
            for (MeshData mesh : data.meshDatas.values()) {
                if (mesh != null) {
                    mesh.close();
                }
            }
            data.meshDatas.clear();
            data.sortStates.clear();
            data.bufferBuilders.clear();
            for (ByteBufferBuilder builder : data.builders.values()) {
                builder.close();
            }
            for (VertexBuffer buffer : data.vertexBuffers.values()) {
                buffer.close();
            }
        } catch (RuntimeException | LinkageError e) {
            LOGGER.debug("[Planarium Panorama] Erreur en liberant un rendu d'Ars", e);
        }
    }
}

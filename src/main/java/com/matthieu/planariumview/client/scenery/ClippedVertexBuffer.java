package com.matthieu.planariumview.client.scenery;

import com.matthieu.planariumview.network.SubLevelTransforms;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.system.MemoryUtil;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;

/** Clips only scenery sections touching the jar, keeping their original shaders, textures and lighting. */
final class ClippedVertexBuffer extends VertexBuffer {
    private MeshData.DrawState state;
    private AABB bounds;
    private int sourceId, sourceBytes;
    private ByteBuffer source;
    private VertexBuffer clipped;
    private ByteBufferBuilder work;
    private Matrix4d lastTransform;
    private boolean sorted, empty;

    ClippedVertexBuffer() { super(Usage.STATIC); }

    /** Diagnostic temporaire : decoupes et relectures de la carte graphique depuis la derniere image. */
    static int rebuilds, readbacks;

    @Override public void upload(MeshData mesh) {
        releaseClip();
        state = mesh.drawState();
        sorted = mesh.indexBuffer() != null;
        ByteBuffer vertices = mesh.vertexBuffer().duplicate().order(ByteOrder.nativeOrder());
        sourceBytes = vertices.remaining();
        int stride = state.format().getVertexSize(), offset = state.format().getOffset(VertexFormatElement.POSITION);
        Vector3d min = new Vector3d(Double.POSITIVE_INFINITY), max = new Vector3d(Double.NEGATIVE_INFINITY);
        for (int vertex = 0; vertex < state.vertexCount(); vertex++) {
            int at = vertex * stride + offset;
            Vector3d point = new Vector3d(vertices.getFloat(at), vertices.getFloat(at + 4), vertices.getFloat(at + 8));
            min.min(point); max.max(point);
        }
        bounds = new AABB(min.x,min.y,min.z,max.x,max.y,max.z);
        // Copie des sommets gardee jusqu'au premier dessin : la relire depuis la carte graphique (glGetBufferSubData)
        // bloquait l'image (gels d'une demi-seconde). Liberee des que la section s'avere loin du bocal.
        source = MemoryUtil.memAlloc(sourceBytes).order(ByteOrder.nativeOrder());
        MemoryUtil.memCopy(vertices, source);
        super.upload(mesh);
        sourceId = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
    }

    private void releaseSource() {
        if (source != null) { MemoryUtil.memFree(source); source=null; }
    }

    @Override public void drawWithShader(Matrix4f model, Matrix4f projection, ShaderInstance shader) {
        if (SceneryClip.fromView == null || state.mode() != VertexFormat.Mode.QUADS) {
            releaseSource();
            super.drawWithShader(model,projection,shader);
            return;
        }
        Matrix4d transform = new Matrix4d(SceneryClip.fromView).mul(new Matrix4d(model));
        if (shader.CHUNK_OFFSET != null) {
            var offset = shader.CHUNK_OFFSET.getFloatBuffer();
            transform.translate(offset.get(0),offset.get(1),offset.get(2));
        }
        if (!SubLevelTransforms.bounds(transform,bounds.inflate(1e-5)).intersects(JarClip.INTERIOR)) {
            releaseClip();
            super.drawWithShader(model,projection,shader);
            return;
        }
        // Tolerance en unites du bocal : les erreurs d'arrondi de la camera ne doivent pas tout redecouper a chaque image.
        if (lastTransform == null || !lastTransform.equals(transform,1e-2)) rebuild(transform);
        if (!empty) {
            clipped.bind();
            try { clipped.drawWithShader(model,projection,shader); }
            finally { bind(); }
        }
    }

    private void rebuild(Matrix4d transform) {
        rebuilds++;
        if (source == null) {
            readbacks++;
            source = MemoryUtil.memAlloc(sourceBytes).order(ByteOrder.nativeOrder());
            int previous = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
            try {
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,sourceId);
                GL15.glGetBufferSubData(GL15.GL_ARRAY_BUFFER,0,source);
            } finally { GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,previous); }
        }
        if (work == null) work = new ByteBufferBuilder(Math.max(4096,sourceBytes));
        work.clear();
        BufferBuilder out = new BufferBuilder(work,VertexFormat.Mode.QUADS,state.format());
        int stride = state.format().getVertexSize();
        ArrayList<JarClip.Vertex> quad = new ArrayList<>(4);
        for (int i=0;i<state.vertexCount();i+=4) {
            quad.clear();
            for (int j=0;j<4;j++) quad.add(new JarClip.Vertex(read((i+j)*stride),transform));
            JarClip.quad(quad,face -> face.forEach(vertex -> vertex.emit(out)));
        }
        MeshData mesh = out.build();
        empty = mesh == null;
        if (mesh != null) {
            if (sorted) {
                Vector3d eye = new Matrix4d(transform).invert().transformPosition(new Vector3d(
                        SceneryClip.camera.x,SceneryClip.camera.y,SceneryClip.camera.z));
                mesh.sortQuads(work,VertexSorting.byDistance((float)eye.x,(float)eye.y,(float)eye.z));
            }
            if (clipped == null) clipped = new VertexBuffer(Usage.DYNAMIC);
            clipped.bind();
            try { clipped.upload(mesh); }
            finally { bind(); }
        }
        lastTransform = new Matrix4d(transform);
    }

    private float[] read(int vertex) {
        float[] data = new float[]{0,0,0,255,255,255,255,0,0,0,0,0,0,0,1,0};
        readFloats(vertex,VertexFormatElement.POSITION,data,0,3);
        int color = state.format().getOffset(VertexFormatElement.COLOR);
        if (color >= 0) for (int i=0;i<4;i++) data[3+i] = source.get(vertex+color+i)&255;
        readFloats(vertex,VertexFormatElement.UV0,data,7,2);
        readShorts(vertex,VertexFormatElement.UV1,data,9);
        readShorts(vertex,VertexFormatElement.UV2,data,11);
        int normal = state.format().getOffset(VertexFormatElement.NORMAL);
        if (normal >= 0) for (int i=0;i<3;i++) data[13+i] = source.get(vertex+normal+i)/127F;
        return data;
    }

    private void readFloats(int vertex,VertexFormatElement element,float[] data,int start,int count) {
        int offset = state.format().getOffset(element);
        if (offset >= 0) for (int i=0;i<count;i++) data[start+i] = source.getFloat(vertex+offset+i*4);
    }

    private void readShorts(int vertex,VertexFormatElement element,float[] data,int start) {
        int offset = state.format().getOffset(element);
        if (offset >= 0) for (int i=0;i<2;i++) data[start+i] = source.getShort(vertex+offset+i*2)&65535;
    }

    @Override public void uploadIndexBuffer(ByteBufferBuilder.Result indices) {
        super.uploadIndexBuffer(indices);
        // Re-sort the clipped translucent copy along with the original when the camera moves.
        lastTransform=null;
    }

    private void releaseClip() {
        if (source != null) { MemoryUtil.memFree(source); source=null; }
        if (clipped != null) { clipped.close(); clipped=null; }
        if (work != null) { work.close(); work=null; }
        lastTransform=null;
    }

    @Override public void close() { releaseClip(); super.close(); }
}

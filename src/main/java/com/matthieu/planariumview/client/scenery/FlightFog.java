package com.matthieu.planariumview.client.scenery;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Vanilla terrain shaders measure fog from Position + ChunkOffset, before applying ModelViewMat. */
public final class FlightFog {
    public record Frame(Matrix4f modelView, Vector3f chunkOffset) {
        /** Section origins are integral offsets from one shared, nearby world origin. */
        public Frame section(int x, int y, int z) {
            return new Frame(modelView, new Vector3f(chunkOffset).add(x, y, z));
        }
    }

    private FlightFog() {}

    /** Move the translation into ChunkOffset; call once per frame, before adding section offsets. */
    public static Frame frame(Matrix4f transform) {
        Matrix4f modelView = new Matrix4f(transform).setTranslation(0, 0, 0);
        Vector3f offset = transform.getTranslation(new Vector3f());
        new Matrix4f(modelView).invertAffine().transformDirection(offset);
        // Flight vertices stay in block units. A common binary grid keeps integer terrain edges exactly equal
        // after Position + ChunkOffset, even on opposite sides of zero or a power-of-two boundary. The maximum
        // displacement per axis is 1/65536 of a source block (less than .0005 at the giant scenery scale).
        offset.set(snap(offset.x), snap(offset.y), snap(offset.z));
        return new Frame(modelView, offset);
    }

    private static float snap(float value) { return Math.round(value * 32768.0F) / 32768.0F; }
}

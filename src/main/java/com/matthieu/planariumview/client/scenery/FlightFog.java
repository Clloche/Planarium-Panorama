package com.matthieu.planariumview.client.scenery;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Vanilla terrain shaders measure fog from Position + ChunkOffset, before applying ModelViewMat. */
public final class FlightFog {
    public record Frame(Matrix4f modelView, Vector3f chunkOffset) {}

    private FlightFog() {}

    /** Move the translation into ChunkOffset while preserving every vertex's position on screen. */
    public static Frame frame(Matrix4f transform) {
        Matrix4f modelView = new Matrix4f(transform).setTranslation(0, 0, 0);
        Vector3f offset = transform.getTranslation(new Vector3f());
        new Matrix4f(modelView).invertAffine().transformDirection(offset);
        return new Frame(modelView, offset);
    }
}

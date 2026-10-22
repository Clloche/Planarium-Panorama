package com.matthieu.planariumview.client.scenery;

import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Matrix4f;

/** Coordinate frame used only while drawing the Planarium's own scenery meshes. */
final class SceneryClip {
    static Matrix4d fromView;
    static Vec3 camera;

    private SceneryClip() {}

    static void begin(Camera eye, Matrix4f view) {
        camera = eye.getPosition();
        fromView = new Matrix4d().translation(camera.x, camera.y, camera.z).mul(new Matrix4d(view).invert());
    }

    static void end() { fromView = null; camera = null; }
}
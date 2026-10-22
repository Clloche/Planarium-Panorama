package com.matthieu.planariumview.client.scenery;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4dc;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Subtracts the actual pocket volume from exterior faces, preserving partial faces and texture coordinates. */
public final class JarClip {
    public static final AABB INTERIOR = new AABB(-1, 0, -1, 33, 33, 33);
    private static final double[] LIMITS = {-1, 33, 0, 33, -1, 33};

    private JarClip() {}

    /** Position, RGBA, UV, overlay, packed light components and normal, in the renderer's original format. */
    public static final class Vertex {
        final float[] data;
        final Vector3d jar;

        public Vertex(float[] data, Matrix4dc toJar) {
            this(data, toJar.transformPosition(new Vector3d(data[0], data[1], data[2])));
        }

        private Vertex(float[] data, Vector3d jar) { this.data = data; this.jar = jar; }

        private Vertex lerp(Vertex other, double t) {
            float[] next = new float[16];
            for (int i = 0; i < next.length; i++) {
                // Overlay and colored-light bit fields are discrete, not numeric colors to blend.
                next[i] = i >= 9 && i <= 12 ? (t < .5 ? data[i] : other.data[i])
                        : (float) (data[i] + (other.data[i] - data[i]) * t);
            }
            return new Vertex(next, new Vector3d(jar).lerp(other.jar, t));
        }

        public void emit(VertexConsumer out) {
            out.addVertex(data[0], data[1], data[2]).setColor(Math.round(data[3]), Math.round(data[4]),
                    Math.round(data[5]), Math.round(data[6])).setUv(data[7], data[8])
                    .setUv1((int) data[9], (int) data[10]).setUv2((int) data[11], (int) data[12])
                    .setNormal(data[13], data[14], data[15]);
        }
    }

    public static void quad(List<Vertex> vertices, Consumer<List<Vertex>> output) {
        for (int plane = 0; plane < 6; plane++) {
            final int side = plane;
            if (vertices.stream().allMatch(vertex -> distance(vertex, side) <= 0)) {
                output.accept(vertices);
                return;
            }
        }
        // Liquid surfaces can be nonplanar. Keep the same diagonal as Minecraft's original quad.
        triangle(List.of(vertices.get(0), vertices.get(1), vertices.get(2)), output);
        triangle(List.of(vertices.get(2), vertices.get(3), vertices.get(0)), output);
    }

    private static void triangle(List<Vertex> polygon, Consumer<List<Vertex>> output) {
        for (int plane = 0; plane < 6 && !polygon.isEmpty(); plane++) {
            List<Vertex> outside = half(polygon, plane, false);
            for (int i = 1; i + 1 < outside.size(); i++) {
                // A degenerate quad encodes one triangle in the existing QUADS render buffers.
                output.accept(List.of(outside.get(0), outside.get(i), outside.get(i + 1), outside.get(i + 1)));
            }
            polygon = half(polygon, plane, true);
        }
    }

    private static List<Vertex> half(List<Vertex> polygon, int plane, boolean inside) {
        List<Vertex> result = new ArrayList<>();
        Vertex previous = polygon.getLast();
        double before = distance(previous, plane) * (inside ? 1 : -1);
        for (Vertex vertex : polygon) {
            double after = distance(vertex, plane) * (inside ? 1 : -1);
            if ((before >= 0) != (after >= 0)) result.add(previous.lerp(vertex, before / (before - after)));
            if (after >= 0) result.add(vertex);
            previous = vertex;
            before = after;
        }
        return result;
    }

    private static double distance(Vertex vertex, int plane) {
        return (vertex.jar.get(plane / 2) - LIMITS[plane]) * (plane % 2 == 0 ? 1 : -1);
    }
}

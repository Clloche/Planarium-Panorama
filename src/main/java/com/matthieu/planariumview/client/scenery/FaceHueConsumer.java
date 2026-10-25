package com.matthieu.planariumview.client.scenery;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;

/**
 * Couleur de Contraption Lights sur les faces des blocs du decor geant, comme dans le vrai monde avec Sodium : la
 * couleur de la case devant la face, sinon celle du bloc. Le rendu de Minecraft ne prend que la case du bloc pour une
 * face en retrait (boite a outils de Create...), et Contraption Lights n'y met pas de couleur : le bloc restait sans
 * couleur dans le decor. Seulement pour les faces eclairees qui n'ont pas deja une couleur.
 */
public final class FaceHueConsumer implements VertexConsumer {
    /** Bits de couleur de Contraption Lights (tout sauf les niveaux de lumiere). */
    private static final int COLOR_BITS = ~0x00F000F0;
    private static final Direction[] AROUND = {Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST,
            Direction.WEST, Direction.DOWN};

    private final VertexConsumer inner;
    private final BlockAndTintGetter world;
    private final BlockPos pos;
    /** Couleur deja cherchee pour chaque face (index de Direction, 6 = sans face), ou null. */
    private final Integer[] hues = new Integer[7];

    FaceHueConsumer(VertexConsumer inner, BlockAndTintGetter world, BlockPos pos) {
        this.inner = inner;
        this.world = world;
        this.pos = pos;
    }

    @Override
    public void putBulkData(PoseStack.Pose pose, BakedQuad quad, float[] brightness, float red, float green, float blue,
                            float alpha, int[] lightmap, int packedOverlay, boolean readAlpha) {
        int[] light = lightmap;
        for (int i = 0; i < lightmap.length; i++) {
            int value = lightmap[i];
            if ((value & COLOR_BITS) != 0 || (value & 0xF0) == 0) {
                // Deja une couleur, ou pas de lumiere de bloc (pas de couleur possible).
                continue;
            }
            int hue = hue(quad.getDirection());
            if (hue == 0) {
                break;
            }
            if (light == lightmap) {
                light = lightmap.clone();
            }
            light[i] = value | hue;
        }
        inner.putBulkData(pose, quad, brightness, red, green, blue, alpha, light, packedOverlay, readAlpha);
    }

    private int hue(Direction face) {
        int index = face == null ? 6 : face.ordinal();
        Integer known = hues[index];
        if (known == null) {
            known = face == null ? 0 : hueAt(world, pos.relative(face));
            if (known == 0) {
                known = hueAt(world, pos);
            }
            hues[index] = known;
        }
        return known;
    }

    /** Couleur (bits de couleur seulement) de Contraption Lights a cet endroit, 0 sans couleur. */
    static int hueAt(BlockAndTintGetter world, BlockPos pos) {
        try {
            return LevelRenderer.getLightColor(world, pos) & COLOR_BITS;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Couleur du bloc, sinon celle d'une case voisine (le dessus d'abord) : pour les blocs speciaux sans couleur. */
    public static int hueAround(BlockAndTintGetter world, BlockPos pos) {
        int hue = hueAt(world, pos);
        for (int i = 0; hue == 0 && i < AROUND.length; i++) {
            hue = hueAt(world, pos.relative(AROUND[i]));
        }
        return hue;
    }

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
        inner.setUv2(u, v);
        return this;
    }

    @Override
    public VertexConsumer setNormal(float x, float y, float z) {
        inner.setNormal(x, y, z);
        return this;
    }
}

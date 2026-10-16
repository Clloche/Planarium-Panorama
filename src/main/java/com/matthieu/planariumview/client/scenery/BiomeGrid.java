package com.matthieu.planariumview.client.scenery;

import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;

/**
 * Grille de biomes en 3D, a la resolution de Minecraft (un biome par cube de 4 x 4 x 4 blocs),
 * couvrant le gabarit plus une marge de 2 blocs pour le melange des couleurs.
 * Calculee de la meme facon cote serveur et cote client a partir du coin du gabarit et de sa taille.
 */
public record BiomeGrid(int minQx, int minQy, int minQz, int sizeX, int sizeY, int sizeZ) {

    public static final int MARGIN = 2;

    public static BiomeGrid of(BlockPos origin, int size) {
        int minQx = QuartPos.fromBlock(origin.getX() - MARGIN);
        int minQy = QuartPos.fromBlock(origin.getY() - MARGIN);
        int minQz = QuartPos.fromBlock(origin.getZ() - MARGIN);
        int maxQx = QuartPos.fromBlock(origin.getX() + size - 1 + MARGIN);
        int maxQy = QuartPos.fromBlock(origin.getY() + size - 1 + MARGIN);
        int maxQz = QuartPos.fromBlock(origin.getZ() + size - 1 + MARGIN);
        return new BiomeGrid(minQx, minQy, minQz, maxQx - minQx + 1, maxQy - minQy + 1, maxQz - minQz + 1);
    }

    public int volume() {
        return sizeX * sizeY * sizeZ;
    }

    /** Index dans le tableau pour des coordonnees de quart absolues (bornees a la grille). */
    public int index(int qx, int qy, int qz) {
        int x = Math.max(0, Math.min(sizeX - 1, qx - minQx));
        int y = Math.max(0, Math.min(sizeY - 1, qy - minQy));
        int z = Math.max(0, Math.min(sizeZ - 1, qz - minQz));
        return (x * sizeY + y) * sizeZ + z;
    }
}

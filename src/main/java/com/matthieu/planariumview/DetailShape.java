package com.matthieu.planariumview;

/**
 * Forme de la zone detaillee du decor : un cylindre (rayon a l'horizontale, meme hauteur qu'avant au-dessus et en
 * dessous), plus un cube. Le serveur (anneau lointain, qui saute exactement cette zone) et le client (blocs dessines)
 * utilisent la meme regle : sinon il resterait des trous, ou des blocs en double, aux coins.
 */
public final class DetailShape {
    private DetailShape() {
    }

    /** Vrai si la colonne (dx, dz), par rapport au Planarium, est dans le cercle de ce rayon. */
    public static boolean horizontal(int dx, int dz, int radius) {
        // r * (r + 1) : un cercle un peu plus plein (comme un rayon de r + 0,5), sans pointes aux 4 axes.
        return (long) dx * dx + (long) dz * dz <= (long) radius * radius + radius;
    }

    /** Meme cercle pour une position non entiere (entites, particules). */
    public static boolean horizontal(double dx, double dz, double radius) {
        return dx * dx + dz * dz <= radius * radius + radius;
    }
}

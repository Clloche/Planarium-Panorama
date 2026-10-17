package com.matthieu.planariumview.compat;

/**
 * Swinging Lanterns (optionnel) : ses lanternes qui se balancent sont un bloc special dessine a part, avec leur chaine.
 * Dans la miniature et le decor, ce dessin lit le vrai monde (il n'y trouve pas la chaine) : la lanterne se balancait a
 * cote de la lanterne immobile, et la chaine disparaissait si on la cachait. On garde donc la lanterne et la chaine
 * immobiles, sans le dessin qui se balance.
 */
public final class SwingingLanternsCompat {
    private static final String PACKAGE = "dev.rilith.swinginglanterns.";

    private SwingingLanternsCompat() {
    }

    /** Vrai pour le dessin d'une lanterne qui se balance (a ne pas faire dans la miniature ni le decor). */
    public static boolean skip(Object renderer) {
        return renderer != null && renderer.getClass().getName().startsWith(PACKAGE);
    }
}

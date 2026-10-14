package com.matthieu.planariumview;

import net.minecraft.client.resources.model.BakedModel;

import java.lang.reflect.Method;

/**
 * Modeles du "Fabric Renderer API" (par le Forgified Fabric API sur NeoForge), par exemple les blocs couverts de neige
 * de Snow! Real Magic. Ces modeles ne donnent pas leurs faces par getQuads : ils les fabriquent pendant le rendu du jeu
 * (ModelBlockRenderer.tesselateBlock). La copie du rendu d'Ars ne les voyait donc pas (seulement la couche de neige).
 * Pour eux, on passe par le rendu du jeu. Sans Fabric API, rien ne change.
 */
public final class FabricModels {
    private static boolean checked;
    private static Class<?> fabricModel;
    private static Method isVanillaAdapter;

    private FabricModels() {
    }

    /** Vrai si ce modele fabrique ses faces lui-meme pendant le rendu (et doit passer par le rendu du jeu). */
    public static boolean needsGameRenderer(BakedModel model) {
        if (!checked) {
            checked = true;
            try {
                fabricModel = Class.forName("net.fabricmc.fabric.api.renderer.v1.model.FabricBakedModel");
                isVanillaAdapter = fabricModel.getMethod("isVanillaAdapter");
            } catch (Throwable e) {
                fabricModel = null;
            }
        }
        if (fabricModel == null || model == null || !fabricModel.isInstance(model)) {
            return false;
        }
        try {
            return !(Boolean) isVanillaAdapter.invoke(model);
        } catch (Throwable e) {
            return false;
        }
    }
}

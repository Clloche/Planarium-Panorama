package com.matthieu.planariumview.client.scenery;

import com.hollingsworth.arsnouveau.client.renderer.tile.PlanariumRenderer.StructureRenderData;
import com.matthieu.planariumview.network.OutsideViewPayload;
import net.minecraft.resources.ResourceLocation;

/** Donnees partagees du decor ; les caches et la gestion des ressources restent dans leurs classes responsables. */
final class SceneryState {
    /** Dimension de poche a laquelle appartient le decor recu. */
    static ResourceLocation currentDim;
    /** Rayon de la capture, en blocs du monde d'origine. */
    static int currentRadius;
    /** Decor actuellement utilise par le rendu. */
    static StructureRenderData renderData;
    /** Informations de la derniere capture appliquee au decor. */
    static OutsideViewPayload lastInfo;
    /** Dernieres informations recues, parfois encore en attente d'application. */
    static OutsideViewPayload latestInfo;

    private SceneryState() {
    }
}

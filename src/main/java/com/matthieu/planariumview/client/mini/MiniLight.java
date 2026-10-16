package com.matthieu.planariumview.client.mini;

import com.matthieu.planariumview.client.scenery.GiantSprites;
import com.matthieu.planariumview.client.scenery.LitRenderingWorld;
import com.matthieu.planariumview.config.Config;
import com.matthieu.planariumview.network.MiniLightPayload;

import com.hollingsworth.arsnouveau.common.block.tile.PlanariumTile;
import com.hollingsworth.arsnouveau.client.renderer.tile.PlanariumRenderer;
import com.hollingsworth.arsnouveau.client.renderer.tile.PlanariumRenderer.StructureRenderData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;

/**
 * Cote client : vraie lumiere pour la miniature du bocal dessinee par Ars Nouveau dans le Planarium.
 */
public final class MiniLight {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<ResourceLocation, byte[]> LIGHT = new HashMap<>();

    private MiniLight() {
    }

    public static void receive(MiniLightPayload payload) {
        LIGHT.put(payload.pocketDim(), payload.light());
        PlanariumFloor.setLight(payload.pocketDim(), payload.light());
        // Force Ars a reconstruire la miniature de ce bocal avec la nouvelle lumiere.
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, payload.pocketDim());
        StructureRenderData old = PlanariumRenderer.structureRenderData.remove(key);
        if (old != null) {
            try {
                PlanariumRenderer.clearByteBuffers(old);
                for (VertexBuffer buffer : old.vertexBuffers.values()) {
                    buffer.close();
                }
            } catch (Exception e) {
                LOGGER.debug("[Planarium View] Erreur en liberant la miniature", e);
            }
        }
    }

    public static void clear() {
        LIGHT.clear();
        PlanariumFloor.clear();
    }

    /** Appele a la fin du constructeur de StructureRenderData d'Ars (via mixin). */
    public static void apply(StructureRenderData data, StructureTemplate template) {
        // Un faux monde a nous pour la vraie lumiere et/ou les couleurs de biome (voir MiniOutsideLight.apply).
        if (data.statePosCache == null) {
            return;
        }
        for (Map.Entry<ResourceKey<Level>, PlanariumTile.ClientDimEntry> entry : PlanariumTile.clientTemplates.entrySet()) {
            if (entry.getValue().template() != template) {
                continue;
            }
            GiantSprites.collectMini(data);
            // Lumieres placees dans le bocal : elles eclairent le plateau du Planarium vu de dehors (PlanariumFloor).
            try {
                PlanariumFloor.setEmitters(entry.getKey().location(), data.statePosCache);
            } catch (Exception e) {
                LOGGER.debug("[Planarium View] Lumieres du bocal ignorees", e);
            }
            if (!Config.realLighting() && !Config.biomeColors()) {
                return;
            }
            byte[] light = Config.realLighting() ? LIGHT.get(entry.getKey().location()) : null;
            if (light != null || Config.biomeColors()) {
                LitRenderingWorld world = new LitRenderingWorld(Minecraft.getInstance().level, data.statePosCache);
                if (light != null) {
                    world.setLight(light, MiniLightPayload.SIZE_X, MiniLightPayload.SIZE_Y, MiniLightPayload.SIZE_Z);
                }
                data.fakeRenderingWorld = world;
            }
            return;
        }
    }

    /** Force Ars a reconstruire toutes les miniatures (quand une option de lumiere change). */
    public static void rebuildAll() {
        for (ResourceKey<Level> key : new java.util.ArrayList<>(PlanariumRenderer.structureRenderData.keySet())) {
            StructureRenderData old = PlanariumRenderer.structureRenderData.remove(key);
            if (old == null) {
                continue;
            }
            try {
                PlanariumRenderer.clearByteBuffers(old);
                for (VertexBuffer buffer : old.vertexBuffers.values()) {
                    buffer.close();
                }
            } catch (Exception e) {
                LOGGER.debug("[Planarium View] Erreur en liberant la miniature", e);
            }
        }
    }
}

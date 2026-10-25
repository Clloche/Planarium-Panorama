package com.matthieu.planariumview.client.mini.entities;

import com.hollingsworth.arsnouveau.client.renderer.tile.PlanariumRenderer;
import com.hollingsworth.arsnouveau.client.renderer.tile.PlanariumRenderer.StructureRenderData;
import com.hollingsworth.arsnouveau.common.block.tile.PlanariumTile;
import com.matthieu.planariumview.client.scenery.entities.GiantEntities;
import com.matthieu.planariumview.client.scenery.LitRenderingWorld;
import com.matthieu.planariumview.compat.contraption_lights.ColorCompat;
import com.matthieu.planariumview.config.Config;
import com.matthieu.planariumview.network.entities.MiniEntitiesPayload;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Porte-armures, tableaux et cadres dans la miniature du Planarium : Ars ne dessine que les blocs du bocal. Le serveur
 * envoie les entites du bocal (MiniEntitiesPayload) ; on les recree dans le faux monde de la miniature et on les dessine
 * 32 fois plus petites, comme les blocs.
 */
public final class MiniEntities {
    /** Ars : le gabarit du bocal commence au bloc (0, 1, 0). */
    private static final BlockPos ORIGIN = new BlockPos(0, 1, 0);
    private static final Map<ResourceLocation, ListTag> RECEIVED = new HashMap<>();
    /** Entites recreees pour chaque miniature (refaites quand Ars refait la miniature ou que la liste change). */
    private static final Map<StructureRenderData, Built> BUILT = Collections.synchronizedMap(new WeakHashMap<>());

    private record Built(ListTag source, Object world, boolean fakeLighting, boolean coloredLighting,
                         List<Entity> entities, Map<Entity, Integer> lights) {
    }

    private MiniEntities() {
    }

    public static void receive(MiniEntitiesPayload payload) {
        ListTag list = payload.entities().getList("entities", Tag.TAG_COMPOUND);
        if (list.isEmpty()) {
            RECEIVED.remove(payload.pocketDim());
        } else {
            RECEIVED.put(payload.pocketDim(), list);
        }
    }

    /** Dessine les entites de la miniature de ce Planarium (pose d'Ars : le coin du bloc du Planarium). */
    public static void render(PlanariumTile tile, float partialTick, PoseStack poseStack, MultiBufferSource buffers,
                              int packedLight) {
        if (tile == null || tile.key == null || !Config.enabled() || RECEIVED.isEmpty()) {
            return;
        }
        ListTag list = RECEIVED.get(tile.key.location());
        StructureRenderData data = PlanariumRenderer.structureRenderData.get(tile.key);
        if (list == null || data == null || data.fakeRenderingWorld == null) {
            return;
        }
        Built built = BUILT.get(data);
        boolean fakeLighting = Config.realLighting() && data.fakeRenderingWorld instanceof LitRenderingWorld;
        boolean coloredLighting = fakeLighting && Config.coloredLight();
        if (built == null || built.source() != list || built.world() != data.fakeRenderingWorld
                || built.fakeLighting() != fakeLighting || built.coloredLighting() != coloredLighting) {
            CompoundTag tag = new CompoundTag();
            tag.put(GiantEntities.KEY, list);
            List<Entity> entities = GiantEntities.create(data.fakeRenderingWorld, tag, ORIGIN);
            Map<Entity, Integer> lights = new IdentityHashMap<>();
            if (fakeLighting) {
                ColorCompat.begin(data.fakeRenderingWorld);
                try {
                    for (Entity entity : entities) {
                        lights.put(entity, GiantEntities.packedLight(data.fakeRenderingWorld, entity, 0.0F));
                    }
                } finally {
                    ColorCompat.end();
                }
            }
            built = new Built(list, data.fakeRenderingWorld, fakeLighting, coloredLighting, entities, lights);
            BUILT.put(data, built);
        }
        if (built.entities().isEmpty()) {
            return;
        }
        // Meme pose que les blocs de la miniature (PlanariumRenderer d'Ars).
        float pad = 0.0025F;
        float scale = (1.0F - 2.0F * pad) / 32.0F;
        float offset = pad + ((1.0F - 2.0F * pad) - 32 * scale) * 0.5F;
        // Lumiere : celle du bocal si la vraie lumiere de la miniature est connue, sinon celle de l'endroit du Planarium.
        boolean useFakeLighting = Config.realLighting() && data.fakeRenderingWorld instanceof LitRenderingWorld;
        poseStack.pushPose();
        poseStack.translate(offset, offset, offset);
        poseStack.scale(scale, scale, scale);
        poseStack.translate(0.0F, 26.0F, 0.0F);
        for (Entity entity : built.entities()) {
            try {
                Integer light = useFakeLighting ? built.lights().get(entity) : packedLight;
                GiantEntities.render(entity, data.fakeRenderingWorld, partialTick, poseStack, buffers, light);
            } catch (Exception ignored) {
                // une entite moddee qui suppose un vrai monde : on la saute
            }
        }
        poseStack.popPose();
    }

    public static void clear() {
        RECEIVED.clear();
        BUILT.clear();
    }
}

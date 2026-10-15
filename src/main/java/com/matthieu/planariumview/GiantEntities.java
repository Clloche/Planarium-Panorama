package com.matthieu.planariumview;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Tableaux, cadres, noeuds de laisse et porte-armures du decor geant. Ce ne sont pas des blocs mais des entites :
 * la photo des blocs ne les contenait pas. Le serveur les ajoute a la photo ; le client les recree (sans les ajouter
 * a aucun monde) dans le faux monde du decor et les dessine 32 fois plus gros, comme les blocs speciaux.
 */
public final class GiantEntities {
    private static final Logger LOGGER = LoggerFactory.getLogger("planariumview");
    /** Cle ajoutee a la photo des blocs (ignoree par Minecraft quand il la relit). */
    static final String KEY = "planariumview_entities";
    /** Champs qui changent tout seuls : retires pour que la photo ne paraisse pas changer a chaque fois. */
    private static final String[] VOLATILE = {"UUID", "Motion", "FallDistance", "Fire", "Air", "OnGround", "PortalCooldown",
            "HurtTime", "HurtByTimestamp", "DeathTime", "AbsorptionAmount", "Brain", "FallFlying", "Health"};

    private GiantEntities() {
    }

    /** Serveur : decorations de la zone captee, avec leur position dans le monde d'origine. */
    static void capture(ServerLevel level, BlockPos min, int size, CompoundTag templateTag) {
        AABB box = new AABB(min.getX(), min.getY(), min.getZ(), min.getX() + size, min.getY() + size, min.getZ() + size);
        ListTag list = new ListTag();
        for (Entity entity : level.getEntitiesOfClass(Entity.class, box, GiantEntities::decoration)) {
            CompoundTag tag = new CompoundTag();
            if (entity.save(tag)) {
                for (String key : VOLATILE) {
                    tag.remove(key);
                }
                list.add(tag);
            }
        }
        if (!list.isEmpty()) {
            templateTag.put(KEY, list);
        }
    }

    private static boolean decoration(Entity entity) {
        return entity instanceof HangingEntity || entity instanceof ArmorStand;
    }

    // ------------------------------------------------------------------ client

    private static Object builtFor;
    private static List<Entity> entities = Collections.emptyList();

    /** Recree les entites dans le faux monde du decor (positions ramenees a celles de la photo). */
    static List<Entity> entities(Object renderData, Level fakeWorld, CompoundTag templateTag, BlockPos origin) {
        if (renderData == builtFor) {
            return entities;
        }
        builtFor = renderData;
        entities = new ArrayList<>();
        if (templateTag == null || fakeWorld == null || origin == null || !templateTag.contains(KEY, Tag.TAG_LIST)) {
            return entities;
        }
        ListTag list = templateTag.getList(KEY, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag tag = list.getCompound(i).copy();
            try {
                ListTag pos = tag.getList("Pos", Tag.TAG_DOUBLE);
                if (pos.size() == 3) {
                    ListTag shifted = new ListTag();
                    shifted.add(DoubleTag.valueOf(pos.getDouble(0) - origin.getX()));
                    shifted.add(DoubleTag.valueOf(pos.getDouble(1) - origin.getY()));
                    shifted.add(DoubleTag.valueOf(pos.getDouble(2) - origin.getZ()));
                    tag.put("Pos", shifted);
                }
                if (tag.contains("TileX")) {
                    tag.putInt("TileX", tag.getInt("TileX") - origin.getX());
                    tag.putInt("TileY", tag.getInt("TileY") - origin.getY());
                    tag.putInt("TileZ", tag.getInt("TileZ") - origin.getZ());
                }
                EntityType.create(tag, fakeWorld).ifPresent(entity -> {
                    if (entity instanceof ArmorStand stand) {
                        stand.setYBodyRot(stand.getYRot());
                        stand.setYHeadRot(stand.getYRot());
                        stand.yBodyRotO = stand.getYRot();
                        stand.yHeadRotO = stand.getYRot();
                    }
                    entities.add(entity);
                });
            } catch (Exception e) {
                LOGGER.debug("[Planarium Panorama] Entite du decor ignoree : {}", tag.getString("id"), e);
            }
        }
        if (!entities.isEmpty()) {
            LOGGER.info("[Planarium Panorama] Decor geant : {} tableaux, cadres ou porte-armures", entities.size());
        }
        return entities;
    }

    static void clear() {
        builtFor = null;
        entities = Collections.emptyList();
    }

    /**
     * Dessine une entite du decor. La pose est deja agrandie et placee au coin de la photo (comme les blocs speciaux) :
     * il ne reste qu'a se placer a la position de l'entite.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    static void render(Entity entity, Level fakeWorld, float partialTick, PoseStack poseStack, MultiBufferSource buffers) {
        EntityRenderer renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity);
        if (renderer == null) {
            return;
        }
        Vec3 offset = renderer.getRenderOffset(entity, partialTick);
        int light = LevelRenderer.getLightColor(fakeWorld, BlockPos.containing(entity.getLightProbePosition(partialTick)));
        poseStack.pushPose();
        poseStack.translate(entity.getX() + offset.x, entity.getY() + offset.y, entity.getZ() + offset.z);
        renderer.render(entity, entity.getYRot(), partialTick, poseStack, buffers, light);
        poseStack.popPose();
    }
}

package com.matthieu.planariumview.client.scenery;

import com.matthieu.planariumview.compat.ClTag;
import com.matthieu.planariumview.compat.ColorCompat;
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
import net.minecraft.world.level.BlockAndTintGetter;
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
    public static final String KEY = "planariumview_entities";
    /** Champs qui changent tout seuls : retires pour que la photo ne paraisse pas changer a chaque fois. */
    private static final String[] VOLATILE = {"UUID", "Motion", "FallDistance", "Fire", "Air", "OnGround", "PortalCooldown",
            "HurtTime", "HurtByTimestamp", "DeathTime", "AbsorptionAmount", "Brain", "FallFlying", "Health"};

    private GiantEntities() {
    }

    /** Serveur : decorations de la zone captee, avec leur position dans le monde d'origine. */
    public static void capture(ServerLevel level, BlockPos min, int size, CompoundTag templateTag) {
        AABB box = new AABB(min.getX(), min.getY(), min.getZ(), min.getX() + size, min.getY() + size, min.getZ() + size);
        ListTag list = list(level, box);
        if (!list.isEmpty()) {
            templateTag.put(KEY, list);
        }
    }

    /** Serveur : decorations dans cette zone (aussi pour la miniature du bocal, voir MiniEntitiesPayload). */
    public static ListTag list(ServerLevel level, AABB box) {
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
        return list;
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
        entities = create(fakeWorld, templateTag, origin);
        if (!entities.isEmpty()) {
            LOGGER.debug("[Planarium Panorama] Decor geant : {} tableaux, cadres ou porte-armures", entities.size());
        }
        return entities;
    }

    /** Recree les entites de la photo dans un faux monde (positions moins {@code origin}). */
    public static List<Entity> create(Level fakeWorld, CompoundTag templateTag, BlockPos origin) {
        List<Entity> entities = new ArrayList<>();
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
                        // Pas de tick dans le faux monde : on fixe nous-memes l'orientation du corps et de la tete,
                        // l'ancienne et la nouvelle (le rendu passe de l'une a l'autre a chaque image). setYBodyRot du
                        // porte-armure ne change que les anciennes : avec un corps reste a 0, il tournait sur lui-meme.
                        float yaw = stand.getYRot();
                        stand.yRotO = yaw;
                        stand.yBodyRot = yaw;
                        stand.yBodyRotO = yaw;
                        stand.yHeadRot = yaw;
                        stand.yHeadRotO = yaw;
                        // Il "tremble" aussi pendant 5 ticks apres un coup (heure du monde moins lastHit).
                        stand.lastHit = Long.MIN_VALUE / 2;
                    }
                    entities.add(entity);
                });
            } catch (Exception e) {
                LOGGER.debug("[Planarium Panorama] Entite du decor ignoree : {}", tag.getString("id"), e);
            }
        }
        return entities;
    }

    public static void clear() {
        builtFor = null;
        entities = Collections.emptyList();
    }

    /**
     * Dessine une entite du decor. La pose est deja agrandie et placee au coin de la photo (comme les blocs speciaux) :
     * il ne reste qu'a se placer a la position de l'entite.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    static void render(Entity entity, Level fakeWorld, float partialTick, PoseStack poseStack, MultiBufferSource buffers) {
        render(entity, fakeWorld, partialTick, poseStack, buffers, null);
    }

    /** Comme render, avec une lumiere imposee ({@code light}, ou null pour celle du faux monde). */
    public static void render(Entity entity, Level fakeWorld, float partialTick, PoseStack poseStack, MultiBufferSource buffers,
                              Integer light) {
        EntityRenderer renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity);
        if (renderer == null) {
            return;
        }
        Vec3 offset = renderer.getRenderOffset(entity, partialTick);
        int packed = light != null ? light
                : packedLight(fakeWorld, entity, partialTick);
        poseStack.pushPose();
        poseStack.translate(entity.getX() + offset.x, entity.getY() + offset.y, entity.getZ() + offset.z);
        renderer.render(entity, entity.getYRot(), partialTick, poseStack, buffers, packed);
        poseStack.popPose();
    }

    /**
     * Lumiere d'une entite du decor, a precalculer pendant que ColorCompat pointe vers le faux monde.
     * EntityRenderDispatcher ajoute normalement la couleur de Contraption Lights; le rendu direct ci-dessus ne passe
     * pas par cette etape. Le resultat est donc calcule pendant la preparation et reutilise par image.
     */
    public static int packedLight(BlockAndTintGetter fakeWorld, Entity entity, float partialTick) {
        BlockPos pos = BlockPos.containing(entity.getLightProbePosition(partialTick));
        int light = LevelRenderer.getLightColor(fakeWorld, pos) & 0x00F000F0;
        if (ColorCompat.building()) {
            light = ClTag.retag(light, fakeWorld, pos);
            if ((light & 0xF0) != 0 && (light & ~0x00F000F0) == 0) {
                light |= FaceHueConsumer.hueAround(fakeWorld, pos);
            }
        }
        return light;
    }
}

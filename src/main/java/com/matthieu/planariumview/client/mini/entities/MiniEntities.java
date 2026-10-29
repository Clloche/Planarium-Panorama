package com.matthieu.planariumview.client.mini.entities;

import com.hollingsworth.arsnouveau.client.renderer.tile.PlanariumRenderer;
import com.hollingsworth.arsnouveau.client.renderer.tile.PlanariumRenderer.StructureRenderData;
import com.hollingsworth.arsnouveau.common.block.tile.PlanariumTile;
import com.matthieu.planariumview.client.scenery.entities.GiantEntities;
import com.matthieu.planariumview.client.scenery.entities.OutsideWings;
import com.matthieu.planariumview.client.scenery.LitRenderingWorld;
import com.matthieu.planariumview.compat.contraption_lights.ColorCompat;
import com.matthieu.planariumview.config.Config;
import com.matthieu.planariumview.network.entities.MiniEntitiesPayload;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Squid;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Entites dans la miniature du Planarium : Ars ne dessine que les blocs du bocal. Le serveur envoie les entites du bocal
 * (MiniEntitiesPayload) : les porte-armures, tableaux et cadres (cle "entities"), et les mobs (cle "mobs"), a leur
 * derniere position connue. On les recree dans le faux monde de la miniature et on les dessine 32 fois plus petits,
 * comme les blocs.
 *
 * <p>Ces copies ne font pas tourner la poche : elles ont seulement de petites animations de repos calculees ici
 * (respiration et ailes par leur age, regards, tentacules), sans IA ni simulation du serveur.
 */
public final class MiniEntities {
    /** Ars : le gabarit du bocal commence au bloc (0, 1, 0). */
    private static final BlockPos ORIGIN = new BlockPos(0, 1, 0);
    private static final Map<ResourceLocation, CompoundTag> RECEIVED = new HashMap<>();
    /** Decorations recreees pour chaque miniature (refaites quand Ars refait la miniature ou que la liste change). */
    private static final Map<StructureRenderData, Built> BUILT = Collections.synchronizedMap(new WeakHashMap<>());
    /** Mobs de chaque miniature, gardes d'une liste a l'autre (meme UUID : meme copie, ses animations continuent). */
    private static final Map<StructureRenderData, Mobs> MOBS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final RandomSource RANDOM = RandomSource.create();
    /** Les animations ne tournent que pour les miniatures dessinees depuis moins d'une seconde. */
    private static final int ANIMATED_TICKS = 20;
    private static final int MAX_MINIS = 4;
    private static final int MAX_MOBS = 64;
    private static final double RANGE_SQUARED = 8.0 * 8.0;
    private static long ticks;

    private record Built(ListTag source, Object world, boolean fakeLighting, boolean coloredLighting,
                         List<Entity> entities, Map<Entity, Integer> lights) {
    }

    /** Mobs d'une miniature, construits depuis la liste {@code source}. */
    private static final class Mobs {
        final ResourceLocation pocket;
        final Level world;
        ListTag source;
        boolean fakeLighting;
        boolean coloredLighting;
        final Map<UUID, MiniMob> mobs = new LinkedHashMap<>();
        long drawnAt = Long.MIN_VALUE / 2;
        double distanceSquared = Double.POSITIVE_INFINITY;
        boolean selected;

        Mobs(ResourceLocation pocket, Level world) {
            this.pocket = pocket;
            this.world = world;
        }
    }

    /** Un mob fige et ses animations de repos. */
    private static final class MiniMob {
        final LivingEntity entity;
        final OutsideWings wings = new OutsideWings();
        Integer light;
        /** Ecart du regard par rapport au corps, et temps avant d'en changer. */
        float look;
        int lookTimer = RANDOM.nextInt(60);

        MiniMob(LivingEntity entity) {
            this.entity = entity;
        }

        void tick() {
            LivingEntity mob = entity;
            // L'age fait respirer les modeles et battre les ailes des abeilles, allays, vex et phantoms.
            mob.tickCount++;
            mob.yRotO = mob.getYRot();
            mob.xRotO = mob.getXRot();
            mob.yBodyRotO = mob.yBodyRot;
            mob.yHeadRotO = mob.yHeadRot;
            if (--lookTimer <= 0) {
                lookTimer = 40 + RANDOM.nextInt(100);
                look = RANDOM.nextInt(3) == 0 ? 0.0F : (RANDOM.nextFloat() * 2.0F - 1.0F) * 50.0F;
            }
            mob.yHeadRot = Mth.approachDegrees(mob.yHeadRot, mob.yBodyRot + look, 3.0F);
            // Poulet, perroquet (selon OnGround), chauve-souris (au repos ou en vol).
            wings.tick(mob);
            if (mob instanceof Squid squid) {
                // Comme Squid.aiStep dans l'eau : une poussee des tentacules, puis un repos.
                float phase = (mob.tickCount % 50) / 50.0F;
                squid.oldTentacleAngle = squid.tentacleAngle;
                squid.tentacleAngle = Mth.sin(phase * phase * Mth.PI) * Mth.PI * 0.25F;
            }
        }
    }

    private MiniEntities() {
    }

    /** Client : les animations de repos des mobs (une fois au demarrage, voir ClientView.init). */
    public static void init() {
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> tick());
    }

    public static void receive(MiniEntitiesPayload payload) {
        CompoundTag tag = payload.entities();
        if (tag.getList("entities", Tag.TAG_COMPOUND).isEmpty() && tag.getList("mobs", Tag.TAG_COMPOUND).isEmpty()) {
            RECEIVED.remove(payload.pocketDim());
            synchronized (MOBS) {
                MOBS.values().removeIf(mobs -> mobs.pocket.equals(payload.pocketDim()));
            }
        } else {
            RECEIVED.put(payload.pocketDim(), tag);
        }
    }

    /** Dessine les entites de la miniature de ce Planarium (pose d'Ars : le coin du bloc du Planarium). */
    public static void render(PlanariumTile tile, float partialTick, PoseStack poseStack, MultiBufferSource buffers,
                              int packedLight) {
        if (tile == null || tile.key == null || !Config.enabled() || RECEIVED.isEmpty()) {
            return;
        }
        CompoundTag received = RECEIVED.get(tile.key.location());
        StructureRenderData data = PlanariumRenderer.structureRenderData.get(tile.key);
        if (received == null || data == null || data.fakeRenderingWorld == null) {
            return;
        }
        boolean fakeLighting = Config.realLighting() && data.fakeRenderingWorld instanceof LitRenderingWorld;
        boolean coloredLighting = fakeLighting && Config.coloredLight();
        Built built = decorations(data, received.getList("entities", Tag.TAG_COMPOUND), fakeLighting, coloredLighting);
        double distance = tile.getLevel() == null ? Double.POSITIVE_INFINITY
                : com.matthieu.planariumview.compat.sable.SubLevelCompat.worldPosition(tile.getLevel(), tile.getBlockPos().getCenter())
                        .distanceToSqr(Minecraft.getInstance().gameRenderer.getMainCamera().getPosition());
        Mobs mobs = Config.miniatureMobs() && distance <= RANGE_SQUARED
                ? mobs(tile.key.location(), data, received.getList("mobs", Tag.TAG_COMPOUND), fakeLighting, coloredLighting) : null;
        if (mobs != null) {
            mobs.distanceSquared = mobs.drawnAt == ticks ? Math.min(mobs.distanceSquared, distance) : distance;
            mobs.drawnAt = ticks;
        }
        if (built.entities().isEmpty() && (mobs == null || mobs.mobs.isEmpty())) {
            return;
        }
        // Meme pose que les blocs de la miniature (PlanariumRenderer d'Ars).
        float pad = 0.0025F;
        float scale = (1.0F - 2.0F * pad) / 32.0F;
        float offset = pad + ((1.0F - 2.0F * pad) - 32 * scale) * 0.5F;
        poseStack.pushPose();
        poseStack.translate(offset, offset, offset);
        poseStack.scale(scale, scale, scale);
        poseStack.translate(0.0F, 26.0F, 0.0F);
        try {
            // Lumiere : celle du bocal si la vraie lumiere de la miniature est connue, sinon celle de l'endroit du Planarium.
            for (Entity entity : built.entities()) {
                try {
                    Integer light = fakeLighting ? built.lights().get(entity) : Integer.valueOf(packedLight);
                    GiantEntities.render(entity, data.fakeRenderingWorld, partialTick,
                            GiantEntities.copyPose(poseStack.last()), buffers, light);
                } catch (Exception ignored) {
                    // une entite moddee qui suppose un vrai monde : on la saute
                }
            }
            if (mobs != null && mobs.selected) {
                for (MiniMob mob : mobs.mobs.values()) {
                    try {
                        Integer light = fakeLighting ? mob.light : Integer.valueOf(packedLight);
                        GiantEntities.render(mob.entity, data.fakeRenderingWorld, partialTick,
                                GiantEntities.copyPose(poseStack.last()), buffers, light);
                    } catch (Exception ignored) {
                        // un mob modde qui suppose un vrai monde : on le saute
                    }
                }
            }
        } finally {
            poseStack.popPose();
        }
    }

    /** Porte-armures, tableaux et cadres : recrees quand la liste, le faux monde ou l'eclairage change. */
    private static Built decorations(StructureRenderData data, ListTag list, boolean fakeLighting, boolean coloredLighting) {
        Built built = BUILT.get(data);
        if (built != null && built.source() == list && built.world() == data.fakeRenderingWorld
                && built.fakeLighting() == fakeLighting && built.coloredLighting() == coloredLighting) {
            return built;
        }
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
        return built;
    }

    /**
     * Mobs : quand la liste change, un mob deja present (meme UUID, meme type) est relu dans sa copie au lieu d'etre
     * recree, pour que ses animations continuent sans sursaut.
     */
    private static Mobs mobs(ResourceLocation pocket, StructureRenderData data, ListTag list, boolean fakeLighting, boolean coloredLighting) {
        Mobs mobs = MOBS.get(data);
        if (mobs != null && mobs.source == list && mobs.world == data.fakeRenderingWorld
                && mobs.fakeLighting == fakeLighting && mobs.coloredLighting == coloredLighting) {
            return mobs;
        }
        if (mobs == null || mobs.world != data.fakeRenderingWorld) {
            mobs = new Mobs(pocket, data.fakeRenderingWorld);
        }
        Map<UUID, MiniMob> previous = new HashMap<>(mobs.mobs);
        Map<UUID, MiniMob> next = new LinkedHashMap<>();
        for (int i = 0; i < list.size() && next.size() < MAX_MOBS; i++) {
            CompoundTag tag = GiantEntities.shifted(list.getCompound(i), ORIGIN);
            UUID id = tag.hasUUID("UUID") ? tag.getUUID("UUID") : UUID.randomUUID();
            MiniMob mob = reuse(previous.remove(id), tag);
            if (mob == null) {
                mob = create(mobs.world, tag);
            }
            if (mob != null) {
                next.put(id, mob);
            }
        }
        if (fakeLighting) {
            ColorCompat.begin(data.fakeRenderingWorld);
            try {
                for (MiniMob mob : next.values()) {
                    mob.light = GiantEntities.packedLight(data.fakeRenderingWorld, mob.entity, 0.0F);
                }
            } finally {
                ColorCompat.end();
            }
        }
        // Liste remplacee d'un coup : la boucle d'animation (tick) ne voit jamais une liste a moitie construite.
        synchronized (MOBS) {
            mobs.mobs.clear();
            mobs.mobs.putAll(next);
        }
        mobs.source = list;
        mobs.fakeLighting = fakeLighting;
        mobs.coloredLighting = coloredLighting;
        MOBS.put(data, mobs);
        return mobs;
    }

    /** Meme mob qu'avant : on relit sa sauvegarde dans sa copie (position, apparence), sans toucher a son regard. */
    private static MiniMob reuse(MiniMob mob, CompoundTag tag) {
        if (mob == null || !EntityType.getKey(mob.entity.getType()).toString().equals(tag.getString("id"))) {
            return null;
        }
        try {
            float headOffset = Mth.clamp(Mth.wrapDegrees(mob.entity.yHeadRot - mob.entity.yBodyRot), -50.0F, 50.0F);
            mob.entity.load(tag);
            GiantEntities.faceSaved(mob.entity);
            mob.entity.yHeadRot = mob.entity.yBodyRot + headOffset;
            mob.entity.yHeadRotO = mob.entity.yHeadRot;
            return mob;
        } catch (Exception e) {
            return null;
        }
    }

    private static MiniMob create(Level world, CompoundTag tag) {
        try {
            Entity entity = EntityType.create(tag, world).orElse(null);
            if (!(entity instanceof LivingEntity living)) {
                return null;
            }
            GiantEntities.faceSaved(living);
            return new MiniMob(living);
        } catch (Exception e) {
            return null;
        }
    }

    /** Chaque tick client : les mobs des miniatures dessinees recemment bougent un peu. */
    private static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.isPaused()) {
            return;
        }
        ticks++;
        if (!Config.miniatureMobs()) {
            return;
        }
        List<Mobs> recent = new ArrayList<>();
        synchronized (MOBS) {
            for (Mobs mobs : MOBS.values()) {
                mobs.selected = false;
                if (ticks - mobs.drawnAt <= ANIMATED_TICKS && !mobs.mobs.isEmpty()) {
                    recent.add(mobs);
                }
            }
        }
        recent.sort(Comparator.comparingDouble(mobs -> mobs.distanceSquared));
        List<MiniMob> animated = new ArrayList<>();
        for (int i = 0; i < Math.min(MAX_MINIS, recent.size()); i++) {
            Mobs mobs = recent.get(i);
            mobs.selected = true;
            animated.addAll(mobs.mobs.values());
        }
        for (MiniMob mob : animated) {
            try {
                mob.tick();
            } catch (Exception ignored) {
                // un mob modde qui n'aime pas etre anime hors d'un vrai monde : il reste fige
            }
        }
    }

    public static void clear() {
        RECEIVED.clear();
        BUILT.clear();
        MOBS.clear();
        ticks = 0;
    }
}

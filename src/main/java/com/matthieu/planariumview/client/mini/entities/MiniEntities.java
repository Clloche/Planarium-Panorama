package com.matthieu.planariumview.client.mini.entities;

import com.hollingsworth.arsnouveau.client.renderer.tile.PlanariumRenderer;
import com.hollingsworth.arsnouveau.client.renderer.tile.PlanariumRenderer.StructureRenderData;
import com.hollingsworth.arsnouveau.common.block.tile.PlanariumTile;
import com.matthieu.planariumview.client.scenery.entities.GiantEntities;
import com.matthieu.planariumview.client.scenery.entities.OutsideWings;
import com.matthieu.planariumview.client.scenery.LitRenderingWorld;
import com.matthieu.planariumview.compat.contraption_lights.ColorCompat;
import com.matthieu.planariumview.config.Config;
import com.matthieu.planariumview.client.scenery.entities.OutsideAquatic;
import com.matthieu.planariumview.network.entities.MiniEntitiesPayload;
import com.matthieu.planariumview.network.entities.MiniLivePayload;
import com.matthieu.planariumview.network.entities.MiniEntityTypes;
import com.matthieu.planariumview.mixin.entities.SceneryEntityAccessor;
import com.google.common.collect.ImmutableList;
import com.matthieu.planariumview.network.entities.MiniWatchPayload;
import com.matthieu.planariumview.network.entities.MobEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;
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
import java.util.HashSet;
import java.util.Set;
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
        int mobLimit;
        boolean vehicles;
        final Map<UUID, MiniMob> mobs = new LinkedHashMap<>();
        long drawnAt = Long.MIN_VALUE / 2;
        double distanceSquared = Double.POSITIVE_INFINITY;
        boolean selected;
        /** Le Planarium de cette miniature (pour la demander vivante au serveur, voir updateWatch). */
        BlockPos tilePos;

        Mobs(ResourceLocation pocket, Level world) {
            this.pocket = pocket;
            this.world = world;
        }
    }

    /** Un mob fige et ses animations de repos. */
    private static final class MiniMob {
        final UUID id;
        final Entity entity;
        UUID mount;
        final OutsideWings wings = new OutsideWings();
        Integer light;
        /** Ecart du regard par rapport au corps, et temps avant d'en changer. */
        float look;
        int lookTimer = RANDOM.nextInt(60);

        MiniMob(UUID id, Entity entity) {
            this.id = id;
            this.entity = entity;
        }

        /**
         * Mob vivant (MiniLive) : sa position et ses angles viennent du serveur (voir LiveMob.apply). Ici, seulement ce
         * que les ticks feraient pour le rendu : l'age, la marche des pattes selon sa vitesse, l'eau et le sol (ailes,
         * nage), les tentacules.
         */
        void tickLive(LiveMob state) {
            Entity mob = entity;
            mob.tickCount++;
            float vx = (state.x - state.px) / SEND_TICKS;
            float vy = (state.y - state.py) / SEND_TICKS;
            float vz = (state.z - state.pz) / SEND_TICKS;
            // Comme LivingEntity.calculateEntityAnimation : la distance parcourue par tick fait marcher les pattes.
            boolean inWater = (state.flags & MiniLivePayload.Entry.IN_WATER) != 0;
            OutsideAquatic.apply(mob, new MobEnvironment(inWater,
                    (state.flags & MiniLivePayload.Entry.UNDERWATER) != 0, inWater,
                    (state.flags & MiniLivePayload.Entry.ON_GROUND) != 0, vx, vy, vz, null));
            if (mob instanceof LivingEntity living) {
                living.walkAnimation.update(Math.min(Mth.sqrt(vx * vx + vz * vz) * 4.0F, 1.0F), 0.4F);
                wings.tick(living);
                tentacles(living);
            }
        }

        private static void tentacles(LivingEntity mob) {
            if (mob instanceof Squid squid) {
                // Comme Squid.aiStep dans l'eau : une poussee des tentacules, puis un repos.
                float phase = (mob.tickCount % 50) / 50.0F;
                squid.oldTentacleAngle = squid.tentacleAngle;
                squid.tentacleAngle = Mth.sin(phase * phase * Mth.PI) * Mth.PI * 0.25F;
            }
        }

        void tick() {
            if (!(entity instanceof LivingEntity mob)) {
                entity.tickCount++;
                return;
            }
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
            tentacles(mob);
            // Un mob qui vient de cesser d'etre vivant arrete doucement de marcher.
            if (mob.walkAnimation.speed() > 0.01F) {
                mob.walkAnimation.update(0.0F, 0.4F);
            }
        }
    }

    /** Un envoi du serveur tous les 4 ticks (voir MiniLive) : on interpole entre les deux derniers. */
    private static final int SEND_TICKS = 4;
    private static final long LIVE_INTERVAL_NANOS = SEND_TICKS * 50_000_000L;
    /** Plus de nouvelles depuis une seconde : la miniature reprend l'instantane anime. */
    private static final long LIVE_STALE_NANOS = 1_000_000_000L;
    /** La miniature regardee est renvoyee au serveur toutes les 2 secondes (il l'oublie apres 3). */
    private static final int WATCH_REFRESH_TICKS = 40;

    /** Mobs vivants recus pour chaque bocal (MiniLivePayload). */
    private static final Map<ResourceLocation, LiveScene> LIVE = new HashMap<>();
    /** Un bocal vide peut aussi etre regarde (four, redstone, futurs mobs). */
    private static final CompoundTag EMPTY_SNAPSHOT = new CompoundTag();
    static {
        EMPTY_SNAPSHOT.put("entities", new ListTag());
        EMPTY_SNAPSHOT.put("mobs", new ListTag());
    }
    private static BlockPos watching;
    private static Level watchingWorld;
    private static int watchingLimit = -1;
    private static boolean watchingVehicles;
    private static long watchSentAt = Long.MIN_VALUE / 2;

    private static final class LiveScene {
        final Map<UUID, LiveMob> mobs = new HashMap<>();
        long receivedAt;
    }

    /** Les deux dernieres positions recues d'un mob vivant (position dans le bocal, angles en degres). */
    private static final class LiveMob {
        float px, py, pz, pBody, pHead, pPitch;
        float x, y, z, body, head, pitch;
        int flags;
        long at;

        void accept(MiniLivePayload.Entry entry, long now, boolean first) {
            if (first) {
                x = entry.x();
                y = entry.y();
                z = entry.z();
                body = entry.bodyYaw();
                head = entry.headYaw();
                pitch = entry.pitch();
            }
            px = x;
            py = y;
            pz = z;
            pBody = body;
            pHead = head;
            pPitch = pitch;
            x = entry.x();
            y = entry.y();
            z = entry.z();
            body = entry.bodyYaw();
            head = entry.headYaw();
            pitch = entry.pitch();
            flags = entry.flags();
            at = now;
        }

        /** Place la copie entre les deux dernieres positions (ancienne et nouvelle valeur egales : pas de double lissage). */
        void apply(Entity entity, long now) {
            float a = Mth.clamp((now - at) / (float) LIVE_INTERVAL_NANOS, 0.0F, 1.0F);
            double ex = Mth.lerp(a, px, x) - ORIGIN.getX();
            double ey = Mth.lerp(a, py, y) - ORIGIN.getY();
            double ez = Mth.lerp(a, pz, z) - ORIGIN.getZ();
            entity.setPos(ex, ey, ez);
            entity.xo = ex;
            entity.yo = ey;
            entity.zo = ez;
            entity.xOld = ex;
            entity.yOld = ey;
            entity.zOld = ez;
            float b = Mth.rotLerp(a, pBody, body);
            float h = Mth.rotLerp(a, pHead, head);
            float p = Mth.lerp(a, pPitch, pitch);
            entity.setYRot(b);
            entity.yRotO = b;
            if (entity instanceof LivingEntity living) {
                living.yBodyRot = b;
                living.yBodyRotO = b;
                living.yHeadRot = h;
                living.yHeadRotO = h;
            }
            entity.setXRot(p);
            entity.xRotO = p;
        }
    }

    private MiniEntities() {
    }

    /** Positions des mobs d'un bocal vivant (voir MiniLive). */
    public static void receiveLive(MiniLivePayload payload) {
        long now = System.nanoTime();
        LiveScene scene = LIVE.computeIfAbsent(payload.pocketDim(), key -> new LiveScene());
        Set<UUID> seen = new HashSet<>();
        for (MiniLivePayload.Entry entry : payload.entries()) {
            LiveMob mob = scene.mobs.get(entry.id());
            boolean first = mob == null || now - scene.receivedAt > LIVE_STALE_NANOS;
            if (first) {
                mob = new LiveMob();
                scene.mobs.put(entry.id(), mob);
            }
            mob.accept(entry, now, first);
            seen.add(entry.id());
        }
        scene.mobs.keySet().retainAll(seen);
        scene.receivedAt = now;
    }

    /** Mobs vivants de ce bocal, ou null (option OFF, rien recu, ou plus de nouvelles depuis une seconde). */
    private static LiveScene live(ResourceLocation pocket, long now) {
        if (!Config.miniatureLiveView()) {
            return null;
        }
        LiveScene scene = LIVE.get(pocket);
        return scene != null && now - scene.receivedAt <= LIVE_STALE_NANOS ? scene : null;
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
        if (tile == null || tile.key == null || !Config.enabled()) {
            return;
        }
        CompoundTag received = RECEIVED.getOrDefault(tile.key.location(), EMPTY_SNAPSHOT);
        StructureRenderData data = PlanariumRenderer.structureRenderData.get(tile.key);
        if (data == null || data.fakeRenderingWorld == null) {
            return;
        }
        boolean fakeLighting = Config.realLighting() && data.fakeRenderingWorld instanceof LitRenderingWorld;
        boolean coloredLighting = fakeLighting && Config.coloredLight();
        Built built = decorations(data, received.getList("entities", Tag.TAG_COMPOUND), fakeLighting, coloredLighting);
        double distance = tile.getLevel() == null ? Double.POSITIVE_INFINITY
                : com.matthieu.planariumview.compat.sable.SubLevelCompat.worldPosition(tile.getLevel(), tile.getBlockPos().getCenter())
                        .distanceToSqr(Minecraft.getInstance().gameRenderer.getMainCamera().getPosition());
        Mobs mobs = Config.miniatureEntities() && distance <= RANGE_SQUARED
                ? mobs(tile.key.location(), data, received.getList("mobs", Tag.TAG_COMPOUND), fakeLighting, coloredLighting) : null;
        if (mobs != null) {
            if (mobs.drawnAt != ticks || distance <= mobs.distanceSquared) {
                mobs.tilePos = tile.getBlockPos().immutable();
            }
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
                long now = System.nanoTime();
                LiveScene live = live(mobs.pocket, now);
                for (MiniMob mob : mobs.mobs.values()) {
                    try {
                        LiveMob state = live == null ? null : live.mobs.get(mob.id);
                        if (state != null) {
                            state.apply(mob.entity, now);
                        }
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
        int mobLimit = Config.miniatureMobLimit();
        boolean vehicles = Config.miniatureVehicles();
        if (mobs != null && mobs.source == list && mobs.world == data.fakeRenderingWorld
                && mobs.fakeLighting == fakeLighting && mobs.coloredLighting == coloredLighting
                && mobs.mobLimit == mobLimit && mobs.vehicles == vehicles) {
            return mobs;
        }
        if (mobs == null || mobs.world != data.fakeRenderingWorld) {
            mobs = new Mobs(pocket, data.fakeRenderingWorld);
        }
        Map<UUID, MiniMob> previous = new HashMap<>(mobs.mobs);
        Map<UUID, MiniMob> next = new LinkedHashMap<>();
        for (CompoundTag source : selectedTags(list, mobLimit, vehicles)) {
            CompoundTag tag = GiantEntities.shifted(source, ORIGIN);
            UUID id = tag.hasUUID("UUID") ? tag.getUUID("UUID") : UUID.randomUUID();
            MiniMob mob = reuse(previous.remove(id), tag);
            if (mob == null) {
                mob = create(mobs.world, tag, id);
            }
            if (mob != null) {
                mob.mount = tag.hasUUID(MiniEntityTypes.MOUNT) ? tag.getUUID(MiniEntityTypes.MOUNT) : null;
                next.put(id, mob);
            }
        }
        linkMounts(next);
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
        mobs.mobLimit = mobLimit;
        mobs.vehicles = vehicles;
        MOBS.put(data, mobs);
        return mobs;
    }

    /** Meme mob qu'avant : on relit sa sauvegarde dans sa copie (position, apparence), sans toucher a son regard. */
    private static MiniMob reuse(MiniMob mob, CompoundTag tag) {
        if (mob == null || !EntityType.getKey(mob.entity.getType()).toString().equals(tag.getString("id"))) {
            return null;
        }
        try {
            float headOffset = mob.entity instanceof LivingEntity living
                    ? Mth.clamp(Mth.wrapDegrees(living.yHeadRot - living.yBodyRot), -50.0F, 50.0F) : 0;
            mob.entity.load(tag);
            faceSaved(mob.entity);
            if (mob.entity instanceof LivingEntity living) {
                living.yHeadRot = living.yBodyRot + headOffset;
                living.yHeadRotO = living.yHeadRot;
            }
            return mob;
        } catch (Exception e) {
            return null;
        }
    }

    private static MiniMob create(Level world, CompoundTag tag, UUID id) {
        try {
            Entity entity = EntityType.create(tag, world).orElse(null);
            if (entity == null || !MiniEntityTypes.supported(entity)) {
                return null;
            }
            faceSaved(entity);
            return new MiniMob(id, entity);
        } catch (Exception e) {
            return null;
        }
    }

    private static void faceSaved(Entity entity) {
        if (entity instanceof LivingEntity living) GiantEntities.faceSaved(living);
        entity.yRotO = entity.getYRot();
        entity.xRotO = entity.getXRot();
        entity.xo = entity.xOld = entity.getX();
        entity.yo = entity.yOld = entity.getY();
        entity.zo = entity.zOld = entity.getZ();
    }

    private static List<CompoundTag> selectedTags(ListTag list, int mobLimit, boolean vehicles) {
        List<CompoundTag> selected = new ArrayList<>();
        int mobs = 0, vehicleCount = 0;
        for (int i = 0; i < list.size(); i++) {
            CompoundTag tag = list.getCompound(i);
            if (MiniEntityTypes.vehicle(tag)) {
                if (!vehicles || vehicleCount >= MiniEntityTypes.MAX_VEHICLES) continue;
                vehicleCount++;
            } else {
                if (mobs >= mobLimit) continue;
                mobs++;
            }
            selected.add(tag);
        }
        return selected;
    }

    /** Relations visuelles seulement : aucun evenement de montee ni simulation dans le faux monde. */
    private static void linkMounts(Map<UUID, MiniMob> copies) {
        Map<Entity, List<Entity>> passengers = new IdentityHashMap<>();
        for (MiniMob copy : copies.values()) {
            var access = (SceneryEntityAccessor) copy.entity;
            access.planariumview$setVehicle(null);
            access.planariumview$setPassengers(ImmutableList.of());
        }
        for (MiniMob copy : copies.values()) {
            MiniMob mount = copies.get(copy.mount);
            if (mount == null || mount == copy) continue;
            ((SceneryEntityAccessor) copy.entity).planariumview$setVehicle(mount.entity);
            passengers.computeIfAbsent(mount.entity, ignored -> new ArrayList<>()).add(copy.entity);
        }
        passengers.forEach((mount, riders) -> ((SceneryEntityAccessor) mount).planariumview$setPassengers(ImmutableList.copyOf(riders)));
    }

    /** Chaque tick client : les mobs des miniatures dessinees recemment bougent un peu. */
    private static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.isPaused()) {
            return;
        }
        ticks++;
        LIVE.values().removeIf(scene -> System.nanoTime() - scene.receivedAt > LIVE_STALE_NANOS);
        updateWatch(mc);
        if (!Config.miniatureEntities()) {
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
        long now = System.nanoTime();
        for (int i = 0; i < Math.min(MAX_MINIS, recent.size()); i++) {
            Mobs mobs = recent.get(i);
            mobs.selected = true;
            LiveScene live = live(mobs.pocket, now);
            List<MiniMob> animated = new ArrayList<>(mobs.mobs.values());
            for (MiniMob mob : animated) {
                try {
                    LiveMob state = live == null ? null : live.mobs.get(mob.id);
                    if (state != null) {
                        mob.tickLive(state);
                    } else {
                        mob.tick();
                    }
                } catch (Exception ignored) {
                    // un mob modde qui n'aime pas etre anime hors d'un vrai monde : il reste fige
                }
            }
            // Un mob vivant change de place : sa lumiere aussi (deux fois par seconde suffit a cette taille).
            if (live != null && mobs.fakeLighting && ticks % 10 == 0) {
                ColorCompat.begin(mobs.world);
                try {
                    for (MiniMob mob : animated) {
                        mob.light = GiantEntities.packedLight(mobs.world, mob.entity, 0.0F);
                    }
                } finally {
                    ColorCompat.end();
                }
            }
        }
    }

    /**
     * Demande au serveur de faire vivre la miniature la plus proche dessinee en ce moment (8 blocs au plus), renvoyee
     * toutes les 2 secondes ; ou plus aucune. Le serveur decide (option du monde, limite, distance verifiee).
     */
    private static void updateWatch(Minecraft mc) {
        if (watchingWorld != mc.level) {
            watchingWorld = mc.level;
            watching = null;
            synchronized (MOBS) {
                MOBS.values().forEach(mobs -> mobs.drawnAt = Long.MIN_VALUE / 2);
            }
        }
        BlockPos wanted = null;
        if (Config.miniatureLiveView()) {
            double best = Double.POSITIVE_INFINITY;
            synchronized (MOBS) {
                for (Mobs mobs : MOBS.values()) {
                    if (ticks - mobs.drawnAt <= 2 && mobs.tilePos != null && mobs.distanceSquared <= RANGE_SQUARED
                            && mobs.distanceSquared < best) {
                        best = mobs.distanceSquared;
                        wanted = mobs.tilePos;
                    }
                }
            }
        }
        boolean changed = wanted == null ? watching != null : !wanted.equals(watching);
        int mobLimit = Config.miniatureMobLimit();
        boolean vehicles = Config.miniatureVehicles();
        changed |= wanted != null && (watchingLimit != mobLimit || watchingVehicles != vehicles);
        if (!changed && (wanted == null || ticks - watchSentAt < WATCH_REFRESH_TICKS)) {
            return;
        }
        var connection = mc.getConnection();
        if (connection == null || !connection.hasChannel(MiniWatchPayload.TYPE)) {
            return;
        }
        PacketDistributor.sendToServer(new MiniWatchPayload(wanted != null, wanted != null ? wanted : BlockPos.ZERO, mobLimit, vehicles));
        watching = wanted;
        watchingLimit = mobLimit;
        watchingVehicles = vehicles;
        watchSentAt = ticks;
    }

    public static void clear() {
        RECEIVED.clear();
        BUILT.clear();
        MOBS.clear();
        LIVE.clear();
        watching = null;
        watchingWorld = null;
        watchingLimit = -1;
        watchingVehicles = false;
        watchSentAt = Long.MIN_VALUE / 2;
        ticks = 0;
    }
}

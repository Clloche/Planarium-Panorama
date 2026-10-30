package com.matthieu.planariumview.client.mini.entities;

import com.hollingsworth.arsnouveau.client.renderer.tile.PlanariumRenderer;
import com.hollingsworth.arsnouveau.client.renderer.tile.PlanariumRenderer.StructureRenderData;
import com.hollingsworth.arsnouveau.common.block.tile.PlanariumTile;
import com.matthieu.planariumview.client.mini.MiniParticles;
import com.matthieu.planariumview.client.scenery.ClientView;
import com.matthieu.planariumview.client.scenery.entities.AmbientEffects;
import com.matthieu.planariumview.client.scenery.entities.AquaticEffects;
import com.matthieu.planariumview.client.scenery.entities.GiantEntities;
import com.matthieu.planariumview.client.scenery.entities.MobAnimationTimeline;
import com.matthieu.planariumview.client.scenery.entities.MobAppearance;
import com.matthieu.planariumview.client.scenery.entities.MobEffects;
import com.matthieu.planariumview.client.scenery.entities.MobShadows;
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
import com.matthieu.planariumview.network.entities.MobAnimations;
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
 * (respiration et ailes par leur age, regards, tentacules), sans IA ni simulation du serveur. Dans une miniature
 * vivante (MiniLive), elles recoivent en plus ce que recoivent les mobs du dehors : apparence (en feu, effets...),
 * coups, attaques, mort, et leurs particules a l'echelle de la miniature (voir MiniParticles).
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
        /** Version de l'apparence vivante deja appliquee (voir LiveMob), -1 apres une relecture de l'instantane. */
        int appliedData = -1;
        int appliedEquipment = -1;
        /** A suivi le serveur (coups, attaque, mort, feu) : a remettre au calme quand le direct s'arrete. */
        boolean animated;
        /** Plus dans l'instantane (mort), garde tant que le direct le montre mourir. */
        boolean orphan;
        /** Parti en nuage apres sa mort : plus dessine, meme si l'instantane le contient encore un moment. */
        boolean gone;

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
            tickLive(state, null, System.nanoTime());
        }

        /** {@code tile} : le Planarium dessine, pour les particules (null : sans particules). */
        void tickLive(LiveMob state, BlockPos tile, long now) {
            Entity mob = entity;
            mob.tickCount++;
            if (state.data.length > 0 && state.dataVersion != appliedData) {
                appliedData = state.dataVersion;
                MobAppearance.data(mob, state.data, mob.registryAccess());
            }
            if (state.equipment.length > 0 && state.equipmentVersion != appliedEquipment && mob instanceof LivingEntity living) {
                appliedEquipment = state.equipmentVersion;
                MobAppearance.equipment(living, state.equipment, mob.registryAccess());
            }
            float vx = (state.x - state.px) / SEND_TICKS;
            float vy = (state.y - state.py) / SEND_TICKS;
            float vz = (state.z - state.pz) / SEND_TICKS;
            // Comme LivingEntity.calculateEntityAnimation : la distance parcourue par tick fait marcher les pattes.
            boolean inWater = (state.flags & MiniLivePayload.Entry.IN_WATER) != 0;
            OutsideAquatic.apply(mob, state.environment != null ? state.environment : new MobEnvironment(inWater,
                    (state.flags & MiniLivePayload.Entry.UNDERWATER) != 0, inWater,
                    (state.flags & MiniLivePayload.Entry.ON_GROUND) != 0, vx, vy, vz, null));
            if (mob instanceof LivingEntity living) {
                living.walkAnimation.update(Math.min(Mth.sqrt(vx * vx + vz * vz) * 4.0F, 1.0F), 0.4F);
                wings.tick(living);
                // Le serveur envoie la vraie pose des calmars avec l'eau ; sinon, la poussee calculee ici.
                if (state.environment == null || state.environment.squid() == null) tentacles(living);
                state.timeline.advance((int) Math.floor(state.time(now)));
                state.timeline.apply(living);
                animated = true;
                // Placee pour cet instant (le dessin le refait a chaque image) : les particules partent du mob.
                state.apply(mob, now);
                int ink = state.timeline.takeInk();
                if (tile != null && !state.timeline.ended() && Config.sceneryParticles()) {
                    effects(living, tile, ink);
                }
                if (state.timeline.takePoof() && tile != null && Config.sceneryParticles()) {
                    MiniParticles.mobDeath(tile, mob.getX(), mob.getY(), mob.getZ(), mob.getBbWidth(), mob.getBbHeight());
                }
                if (state.timeline.ended()) gone = true;
            }
        }

        /** Memes particules que les mobs du dehors (MobEffects, AmbientEffects, AquaticEffects), dans la miniature. */
        private static void effects(LivingEntity mob, BlockPos tile, int ink) {
            AquaticEffects.Sink sink = (type, x, y, z, vx, vy, vz) -> MiniParticles.mobParticle(tile, type, x, y, z, vx, vy, vz);
            MobEffects.Emission potion = MobEffects.sample(mob, mob.getRandom());
            if (potion != null) {
                MiniParticles.mobParticle(tile, potion.options(), potion.x(), potion.y(), potion.z(), 1.0, 1.0, 1.0);
            }
            AmbientEffects.sample(mob, mob.getRandom(), sink);
            AquaticEffects.sample(mob, mob.getYRot(), mob.getXRot(), mob.getRandom(), sink);
            if (ink > 0 && mob instanceof Squid squid) {
                for (int i = 0; i < ink; i++) AquaticEffects.ink(squid, squid.yBodyRot, mob.getRandom(), sink);
            }
        }

        /** Le direct s'arrete : plus de coup, de mort, d'attaque ni de feu laisses sur la copie figee. */
        void calm() {
            if (!animated) return;
            animated = false;
            if (entity instanceof LivingEntity living) MobAnimationTimeline.calm(living);
            entity.setSharedFlagOnFire(false);
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
            calm();
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
    /** Un mob mort sans nuage recu (retire autrement) n'est pas garde plus de 2 secondes. */
    private static final int MAX_DEPARTING_TICKS = 40;
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
        /** Identifiant reseau -> UUID, pour les evenements (coup, mort...). */
        final Map<Integer, UUID> ids = new HashMap<>();
        long receivedAt;
    }

    /** Les deux dernieres positions recues d'un mob vivant (position dans le bocal, angles en degres). */
    private static final class LiveMob {
        float px, py, pz, pBody, pHead, pPitch;
        float x, y, z, body, head, pitch;
        int flags;
        long at;
        /** Tick du serveur du dernier envoi : l'horloge des animations (voir time). */
        int tick;
        /** Tick du serveur depuis lequel il n'est plus envoye (mort), ou -1. */
        int heldSince = -1;
        MobEnvironment environment;
        /** Derniere apparence recue (donnees synchronisees, equipement) et son numero de version. */
        byte[] data = new byte[0];
        byte[] equipment = new byte[0];
        int dataVersion;
        int equipmentVersion;
        final MobAnimationTimeline timeline = new MobAnimationTimeline();

        /**
         * Instant dessine, en ticks du serveur : la position est interpolee entre l'avant-dernier et le dernier envoi,
         * donc un intervalle d'envoi en retard sur le dernier tick recu.
         */
        double time(long now) {
            return tick - SEND_TICKS + Mth.clamp((now - at) / 50_000_000.0, 0.0, SEND_TICKS);
        }

        /** Mort (ou sa mort recue, pas encore jouee) et pas encore parti en nuage. */
        boolean departing() {
            return timeline.departing();
        }

        /** Plus de position : reste sur place, mais son horloge avance avec les envois (fin de l'animation de mort). */
        void hold(long now, int serverTick) {
            if (heldSince < 0) heldSince = serverTick;
            px = x;
            py = y;
            pz = z;
            pBody = body;
            pHead = head;
            pPitch = pitch;
            at = now;
            tick = serverTick;
        }

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
            heldSince = -1;
            environment = entry.environment();
            if (entry.data() != null && entry.data().length > 0) {
                data = entry.data();
                dataVersion++;
            }
            if (entry.equipment() != null && entry.equipment().length > 0) {
                equipment = entry.equipment();
                equipmentVersion++;
            }
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
        // Debordement cote serveur : l'historique en attente est remplace par les etats de ce paquet. Vide avant
        // d'ajouter ces etats, sinon ils seraient effaces avec lui.
        if (payload.resetAnimations()) scene.mobs.values().forEach(mob -> mob.timeline.resetPending());
        Set<UUID> seen = new HashSet<>();
        for (MiniLivePayload.Entry entry : payload.entries()) {
            LiveMob mob = scene.mobs.get(entry.id());
            boolean first = mob == null || now - scene.receivedAt > LIVE_STALE_NANOS;
            if (first) {
                mob = new LiveMob();
                scene.mobs.put(entry.id(), mob);
            }
            mob.accept(entry, now, first);
            mob.tick = payload.tick();
            if (entry.living() != null) mob.timeline.snapshot(payload.tick(), entry.living());
            if (entry.entityId() >= 0) scene.ids.put(entry.entityId(), entry.id());
            seen.add(entry.id());
        }
        // Evenements avant le menage : un mob absent de ce paquet (parti apres sa mort) recoit encore sa mort et son nuage.
        for (MobAnimations.Event event : payload.events()) {
            UUID id = scene.ids.get(event.entityId());
            LiveMob mob = id == null ? null : scene.mobs.get(id);
            if (mob != null) mob.timeline.event(event);
        }
        // Un mob qui vient de mourir n'est plus envoye apres son nuage : il reste jusqu'a la fin de son animation,
        // immobile, sur l'horloge des envois.
        scene.mobs.entrySet().removeIf(item -> !seen.contains(item.getKey()) && (!item.getValue().departing()
                || item.getValue().heldSince >= 0 && payload.tick() - item.getValue().heldSince > MAX_DEPARTING_TICKS));
        for (Map.Entry<UUID, LiveMob> item : scene.mobs.entrySet()) {
            if (!seen.contains(item.getKey())) item.getValue().hold(now, payload.tick());
        }
        scene.ids.values().retainAll(scene.mobs.keySet());
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
        // Flammes face a la camera, autour de la verticale de la miniature (inclinee sur une structure Sable).
        var flameRotation = MobEffects.flameRotation(poseStack.last().pose(),
                Minecraft.getInstance().gameRenderer.getMainCamera().rotation());
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
                // Ombres comme dans le decor geant ; leur fondu suit la vraie distance (32 fois plus petite).
                MobShadows.Frame shadows = MobShadows.frame(poseStack.last().pose(), ClientView.ARS_SCALE, fakeLighting);
                for (MiniMob mob : mobs.mobs.values()) {
                    try {
                        LiveMob state = live == null ? null : live.mobs.get(mob.id);
                        if (mob.gone) continue; // Parti en nuage : il ne reste que les particules.
                        if (state != null) {
                            if (state.timeline.ended()) continue;
                            state.apply(mob.entity, now);
                        } else if (mob.orphan) {
                            continue;
                        }
                        Integer light = fakeLighting ? mob.light : Integer.valueOf(packedLight);
                        PoseStack pose = GiantEntities.copyPose(poseStack.last());
                        GiantEntities.render(mob.entity, data.fakeRenderingWorld, partialTick, pose, buffers, light,
                                flameRotation);
                        MobShadows.render(shadows, mob.entity, data.fakeRenderingWorld, pose, buffers);
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
        // Un mob mort quitte l'instantane avant la fin de son animation : le direct le garde jusqu'a son nuage.
        LiveScene live = live(pocket, System.nanoTime());
        if (live != null) {
            for (MiniMob mob : previous.values()) {
                LiveMob state = live.mobs.get(mob.id);
                if (state != null && !state.timeline.ended()) {
                    mob.orphan = true;
                    next.put(mob.id, mob);
                }
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
            mob.appliedData = -1;
            mob.appliedEquipment = -1;
            mob.orphan = false;
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
                    if (mob.orphan && (state == null || state.timeline.ended())) {
                        synchronized (MOBS) {
                            mobs.mobs.remove(mob.id);
                        }
                        continue;
                    }
                    if (state != null) {
                        mob.tickLive(state, mobs.tilePos, now);
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

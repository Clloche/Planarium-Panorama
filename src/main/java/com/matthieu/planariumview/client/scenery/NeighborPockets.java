package com.matthieu.planariumview.client.scenery;

import com.matthieu.planariumview.DetailShape;
import com.matthieu.planariumview.config.Config;
import com.matthieu.planariumview.network.NeighborPocketsPayload;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Interieur des Planariums voisins (voir NeighborPocketsServer) : une photo figee de leur poche, dessinee a l'echelle
 * 1:1 a la place qu'ils occupent dans le decor geant. Un bloc du decor fait la taille d'une poche : des Planariums
 * colles forment un seul paysage a travers le verre. Purement visuel : pas d'entites, de particules, de blocs speciaux
 * ni de mises a jour.
 *
 * Les poches sont placees dans le repere du bocal (decalage du voisin x GIANT), pas dans celui du terrain : sur une
 * structure Sable, elles bougent donc avec elle, en vol aussi. Construites section par section (16 x 16 x 16) avec un
 * petit budget par image, comme l'anneau lointain.
 */
public final class NeighborPockets {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long BUDGET_NANOS = 2_000_000L;
    /** Positions locales des blocs recus : 0 = case {@link NeighborPocketsPayload#ORIGIN} de la poche. */
    private static final BlockPos ORIGIN = NeighborPocketsPayload.ORIGIN;

    private static final class Pocket {
        final BlockPos offset;
        final FarRing.FarWorld world;
        final Map<Long, List<BlockPos>> pendingBlocks;
        final ArrayDeque<Long> pending = new ArrayDeque<>();
        final Map<Long, Map<RenderType, VertexBuffer>> sections = new HashMap<>();
        /** Ciel de l'endroit du Planarium voisin deja lu dans le decor (voir FarRing.FarWorld.skyCap). */
        boolean outsideRead;
        /** Derniere lecture de ce ciel : relu chaque seconde (un plafond ouvert ou ferme au-dessus du voisin). */
        long outsideCheckedAt;

        Pocket(BlockPos offset, FarRing.FarWorld world, Map<Long, List<BlockPos>> pendingBlocks) {
            this.offset = offset;
            this.world = world;
            this.pendingBlocks = pendingBlocks;
            pending.addAll(pendingBlocks.keySet());
        }

        /** Coin de la poche dans le repere du bocal. */
        double baseX() {
            return offset.getX() * (double) ClientView.GIANT + ORIGIN.getX();
        }

        double baseY() {
            return offset.getY() * (double) ClientView.GIANT + ORIGIN.getY();
        }

        double baseZ() {
            return offset.getZ() * (double) ClientView.GIANT + ORIGIN.getZ();
        }
    }

    /** Une section a dessiner dans la couche translucide, avec sa distance a la camera. */
    private record Translucent(VertexBuffer buffer, Matrix4f model, double distance) {
    }

    private static ResourceLocation pocket;
    private static final List<Pocket> pockets = new ArrayList<>();

    private NeighborPockets() {
    }

    public static void receive(NeighborPocketsPayload payload) {
        clear();
        if (payload.entries().isEmpty() || Minecraft.getInstance().level == null) {
            ClientView.neighborPocketsChanged(payload.pocketDim());
            return;
        }
        try {
            for (NeighborPocketsPayload.Entry entry : payload.entries()) {
                Map<Long, List<BlockPos>> bySection = new HashMap<>();
                // Au-dessus de la boite (2 cases au-dessus du plafond de verre) : le ciel de la poche.
                FarRing.FarWorld world = FarRing.fill(new FarRing.FarWorld(ORIGIN, 34, 0), entry.data(),
                        pos -> bySection.computeIfAbsent(BlockPos.asLong(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4),
                                ignored -> new ArrayList<>()).add(pos));
                pockets.add(new Pocket(entry.offset(), world, bySection));
            }
            pocket = payload.pocketDim();
            LOGGER.info("[Planarium Panorama] Planariums voisins recus : {}", pockets.size());
        } catch (RuntimeException e) {
            LOGGER.warn("[Planarium Panorama] Planariums voisins illisibles", e);
            clear();
        }
        ClientView.neighborPocketsChanged(payload.pocketDim());
    }

    /** Adjust only the giant shells whose interiors are actually displayed by the client. */
    static BakedModel model(BakedModel model, BlockState state, BlockPos offset) {
        if (!(state.getBlock() instanceof com.hollingsworth.arsnouveau.common.block.Planarium) || !visible()) return model;
        Pocket entry = displayed(offset);
        if (entry == null) return model;
        int shared = 0;
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos adjacent = offset.relative(side);
            if (adjacent.equals(BlockPos.ZERO) || displayed(adjacent) != null) shared |= 1 << side.ordinal();
        }
        return new NeighborPlanariumModel(model, entry.world, shared);
    }

    private static Pocket displayed(BlockPos offset) {
        for (int i = 0; i < shown(); i++) {
            Pocket entry = pockets.get(i);
            if (entry.offset.equals(offset) && inView(offset)) return entry;
        }
        return null;
    }

    /** Oublie les poches (sortie du bocal, decor efface, nouvelle photo). */
    public static void clear() {
        for (Pocket entry : pockets) {
            entry.sections.values().forEach(buffers -> buffers.values().forEach(VertexBuffer::close));
        }
        pockets.clear();
        pocket = null;
    }

    private static boolean visible() {
        if (pocket == null || !Config.enabled()) return false;
        var level = Minecraft.getInstance().level;
        return level != null && pocket.equals(level.dimension().location()) && pocket.equals(ClientView.currentDimension());
    }

    /** Nombre de poches dessinees : l'option du joueur (les plus proches d'abord, deja dans l'ordre). */
    private static int shown() {
        return Math.min(Config.neighborPlanariums(), pockets.size());
    }

    /** Dans le cylindre du decor detaille actuellement dessine (rayon limite par le brouillard, voir viewRadius). */
    private static boolean inView(BlockPos offset) {
        return Math.abs(offset.getY()) <= ClientView.nearRadius()
                && DetailShape.horizontal(offset.getX(), offset.getZ(), ClientView.viewRadius());
    }

    /** Construit des sections dans le budget de l'image (au moins une), la poche la plus proche d'abord. */
    static void buildPending(Vec3 camera) {
        if (!visible()) return;
        long start = System.nanoTime();
        int shown = shown();
        for (int i = 0; i < shown; i++) {
            Pocket entry = pockets.get(i);
            Vec3 local = camera.subtract(entry.offset.getX() * (double) ClientView.GIANT,
                    entry.offset.getY() * (double) ClientView.GIANT, entry.offset.getZ() * (double) ClientView.GIANT);
            if (!entry.outsideRead) {
                // Avant la premiere section : sans le decor autour, on ne sait pas encore s'il est dans le noir.
                if (!readOutside(entry, start)) continue;
            } else if (start - entry.outsideCheckedAt >= 1_000_000_000L) {
                int skyCap = entry.world.skyCap;
                byte[] outsideBlock = entry.world.outsideBlock;
                if (readOutside(entry, start) && (entry.world.skyCap != skyCap
                        || !java.util.Arrays.equals(entry.world.outsideBlock, outsideBlock))) {
                    // La lumiere autour du voisin a change : ses sections sont refaites (les anciennes restent dessinees
                    // jusqu'a leur remplacement).
                    entry.pending.clear();
                    entry.pending.addAll(entry.pendingBlocks.keySet());
                }
            }
            while (!entry.pending.isEmpty()) {
                if (System.nanoTime() - start >= BUDGET_NANOS) return;
                Long key = entry.pending.poll();
                // Les blocs restent gardes : la section peut etre refaite si la lumiere autour du voisin change.
                List<BlockPos> blocks = entry.pendingBlocks.get(key);
                if (blocks == null) continue;
                try {
                    // Sommets en coordonnees de la poche voisine (locale + ORIGIN).
                    Map<RenderType, VertexBuffer> previous = entry.sections.put(key, FarRing.buildPocket(entry.world, blocks,
                            -ORIGIN.getX(), -ORIGIN.getY(), -ORIGIN.getZ(), local));
                    if (previous != null) previous.values().forEach(VertexBuffer::close);
                } catch (RuntimeException e) {
                    LOGGER.debug("[Planarium Panorama] Section d'un Planarium voisin ignoree", e);
                }
            }
        }
    }

    /**
     * Lumiere autour du bloc du Planarium voisin, dans le decor detaille, comme pour une miniature (MiniOutsideLight) :
     * son ciel (le plus clair de ses 6 voisins), car sa poche a un ciel plein qui l'eclairait en plein jour au fond
     * d'une grotte ; et la lumiere des lampes a cote de chacune de ses faces, qui entre dans la poche (JarLight).
     * Faux si le decor n'est pas encore la (on reessaie a l'image suivante).
     */
    private static boolean readOutside(Pocket entry, long now) {
        LitRenderingWorld scenery = ClientView.sceneryWorld();
        if (scenery == null) return false;
        int radius = ClientView.nearRadius();
        BlockPos at = entry.offset.offset(radius, radius, radius);
        int sky = 0;
        for (Direction side : Direction.values()) {
            sky = Math.max(sky, scenery.getBrightness(net.minecraft.world.level.LightLayer.SKY, at.relative(side)));
        }
        entry.world.skyCap = sky;
        BlockPos[] positions = com.matthieu.planariumview.client.jar.JarWalls.samplePositions(at);
        int[] faces = new int[positions.length];
        int center = scenery.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, at);
        boolean lit = center > 0;
        for (int i = 0; i < positions.length; i++) {
            faces[i] = scenery.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, positions[i]);
            lit |= faces[i] > 0;
        }
        entry.world.outsideBlock = lit ? com.matthieu.planariumview.client.jar.JarLight.computeTable(faces, center) : null;
        entry.outsideRead = true;
        entry.outsideCheckedAt = now;
        return true;
    }

    /**
     * Dessine une couche (l'etat de rendu est deja en place, voir ClientView.draw). {@code jarPose} : vue de la camera
     * en coordonnees du bocal, sans le placement du terrain ; {@code camera} : position de la camera dans le bocal.
     * La couche translucide (verre, eau) va du plus loin au plus proche, toutes poches confondues : sinon le verre d'un
     * bocal pouvait cacher l'eau ou le verre d'un bocal derriere lui.
     */
    static void drawLayer(RenderType type, Matrix4f jarPose, Matrix4f projection, Vec3 camera) {
        if (!visible()) return;
        int shown = shown();
        float giant = ClientView.GIANT;
        boolean translucent = type == RenderType.translucent();
        List<Translucent> sorted = translucent ? new ArrayList<>() : null;
        try {
            for (int i = 0; i < shown; i++) {
                Pocket entry = pockets.get(i);
                if (entry.sections.isEmpty() || !inView(entry.offset)) continue;
                Matrix4f model = new Matrix4f(jarPose).translate(entry.offset.getX() * giant,
                        entry.offset.getY() * giant, entry.offset.getZ() * giant);
                for (Map.Entry<Long, Map<RenderType, VertexBuffer>> section : entry.sections.entrySet()) {
                    VertexBuffer buffer = section.getValue().get(type);
                    if (buffer == null || buffer.getFormat() == null) continue;
                    if (translucent) {
                        long key = section.getKey();
                        double dx = entry.baseX() + BlockPos.getX(key) * 16 + 8 - camera.x;
                        double dy = entry.baseY() + BlockPos.getY(key) * 16 + 8 - camera.y;
                        double dz = entry.baseZ() + BlockPos.getZ(key) * 16 + 8 - camera.z;
                        sorted.add(new Translucent(buffer, model, dx * dx + dy * dy + dz * dz));
                        continue;
                    }
                    buffer.bind();
                    buffer.drawWithShader(model, projection, RenderSystem.getShader());
                }
            }
            if (translucent) {
                sorted.sort(Comparator.comparingDouble(Translucent::distance).reversed());
                for (Translucent section : sorted) {
                    section.buffer().bind();
                    section.buffer().drawWithShader(section.model(), projection, RenderSystem.getShader());
                }
            }
        } finally {
            VertexBuffer.unbind();
        }
    }
}

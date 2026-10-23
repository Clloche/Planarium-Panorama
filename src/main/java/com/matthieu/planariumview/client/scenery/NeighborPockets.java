package com.matthieu.planariumview.client.scenery;

import com.matthieu.planariumview.DetailShape;
import com.matthieu.planariumview.config.Config;
import com.matthieu.planariumview.network.NeighborPocketsPayload;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Interieur des Planariums voisins (voir NeighborPocketsServer) : une photo figee de leur poche, dessinee a l'echelle
 * 1:1 a la place qu'ils occupent dans le decor geant. Un bloc du decor fait la taille d'une poche : des Planariums
 * colles forment un seul paysage a travers le verre. Purement visuel : pas d'entites, de particules, de blocs speciaux
 * ni de mises a jour.
 *
 * Les poches sont placees dans le repere du bocal (decalage du voisin x GIANT), pas dans celui du terrain : sur une
 * structure Sable, elles bougent donc avec elle, en vol aussi.
 */
public final class NeighborPockets {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Positions locales des blocs recus : 0 = case {@link NeighborPocketsPayload#ORIGIN} de la poche. */
    private static final BlockPos ORIGIN = NeighborPocketsPayload.ORIGIN;

    private static final class Pocket {
        final BlockPos offset;
        final FarRing.FarWorld world;
        final List<BlockPos> blocks;
        Map<RenderType, VertexBuffer> buffers;

        Pocket(BlockPos offset, FarRing.FarWorld world, List<BlockPos> blocks) {
            this.offset = offset;
            this.world = world;
            this.blocks = blocks;
        }
    }

    private static ResourceLocation pocket;
    private static final List<Pocket> pockets = new ArrayList<>();

    private NeighborPockets() {
    }

    public static void receive(NeighborPocketsPayload payload) {
        clear();
        if (payload.entries().isEmpty() || Minecraft.getInstance().level == null) {
            return;
        }
        try {
            for (NeighborPocketsPayload.Entry entry : payload.entries()) {
                List<BlockPos> blocks = new ArrayList<>();
                // Au-dessus de la boite (2 cases au-dessus du plafond de verre) : le ciel de la poche.
                FarRing.FarWorld world = FarRing.fill(new FarRing.FarWorld(ORIGIN, 34, 0), entry.data(), blocks::add);
                pockets.add(new Pocket(entry.offset(), world, blocks));
            }
            pocket = payload.pocketDim();
            LOGGER.info("[Planarium Panorama] Planariums voisins recus : {}", pockets.size());
        } catch (RuntimeException e) {
            LOGGER.warn("[Planarium Panorama] Planariums voisins illisibles", e);
            clear();
        }
    }

    /** Oublie les poches (sortie du bocal, decor efface, nouvelle photo). */
    public static void clear() {
        for (Pocket entry : pockets) {
            if (entry.buffers != null) entry.buffers.values().forEach(VertexBuffer::close);
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
        int view = ClientView.viewRadius();
        return Math.abs(offset.getY()) <= ClientView.nearRadius()
                && DetailShape.horizontal(offset.getX(), offset.getZ(), view);
    }

    /** Construit au plus une poche par image (quelques milliers de blocs). */
    static void buildPending(Vec3 camera) {
        if (!visible()) return;
        int shown = shown();
        for (int i = 0; i < shown; i++) {
            Pocket entry = pockets.get(i);
            if (entry.buffers != null) continue;
            BlockPos offset = entry.offset;
            float giant = ClientView.GIANT;
            Vec3 local = camera.subtract(offset.getX() * giant, offset.getY() * giant, offset.getZ() * giant);
            try {
                long start = System.nanoTime();
                // Sommets en coordonnees de la poche voisine (locale + ORIGIN).
                entry.buffers = FarRing.buildPocket(entry.world, entry.blocks, -ORIGIN.getX(), -ORIGIN.getY(), -ORIGIN.getZ(), local);
                LOGGER.debug("[Planarium Panorama] Planarium voisin {} construit : {} blocs, {} ms", offset,
                        entry.blocks.size(), (System.nanoTime() - start) / 1_000_000L);
            } catch (RuntimeException e) {
                LOGGER.debug("[Planarium Panorama] Planarium voisin ignore", e);
                entry.buffers = Map.of();
            }
            return;
        }
    }

    /**
     * Dessine une couche (l'etat de rendu est deja en place, voir ClientView.draw). {@code jarPose} : vue de la camera
     * en coordonnees du bocal, sans le placement du terrain.
     */
    static void drawLayer(RenderType type, Matrix4f jarPose, Matrix4f projection) {
        if (!visible()) return;
        int shown = shown();
        float giant = ClientView.GIANT;
        try {
            for (int i = 0; i < shown; i++) {
                Pocket entry = pockets.get(i);
                if (entry.buffers == null || !inView(entry.offset)) continue;
                VertexBuffer buffer = entry.buffers.get(type);
                if (buffer == null || buffer.getFormat() == null) continue;
                Matrix4f model = new Matrix4f(jarPose).translate(entry.offset.getX() * giant,
                        entry.offset.getY() * giant, entry.offset.getZ() * giant);
                buffer.bind();
                buffer.drawWithShader(model, projection, RenderSystem.getShader());
            }
        } finally {
            VertexBuffer.unbind();
        }
    }
}

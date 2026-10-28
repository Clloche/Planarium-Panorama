package com.matthieu.planariumview.client.scenery;

import com.hollingsworth.arsnouveau.client.renderer.tile.PlanariumRenderer;
import com.hollingsworth.arsnouveau.client.renderer.tile.PlanariumRenderer.StructureRenderData;
import com.matthieu.planariumview.client.jar.WallColors;
import com.matthieu.planariumview.client.mini.MiniLight;
import com.matthieu.planariumview.client.mini.MiniParticles;
import com.matthieu.planariumview.client.scenery.SceneryMeshes.SectionMesh;
import com.matthieu.planariumview.client.scenery.SceneryPatcher.BlockChange;
import com.matthieu.planariumview.client.scenery.entities.GiantEntities;
import com.matthieu.planariumview.client.scenery.entities.OutsideMobs;
import com.matthieu.planariumview.client.sky.LunarClient;
import com.matthieu.planariumview.client.sky.SkySync;
import com.matthieu.planariumview.client.sky.WeatherView;
import com.matthieu.planariumview.client.sound.OutsideAmbience;
import com.matthieu.planariumview.client.sound.OutsideRecordClient;
import com.matthieu.planariumview.client.sound.OutsideSoundClient;
import com.matthieu.planariumview.compat.puddle_flood.PuddlesCompat;
import com.matthieu.planariumview.network.OutsideViewDeltaPayload;
import com.matthieu.planariumview.network.OutsideViewPayload;
import com.mojang.blaze3d.vertex.VertexBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static com.matthieu.planariumview.client.scenery.ClientView.shownEdge;
import static com.matthieu.planariumview.client.scenery.SceneryMeshes.builtViewRadius;
import static com.matthieu.planariumview.client.scenery.SceneryMeshes.currentSectionStates;
import static com.matthieu.planariumview.client.scenery.SceneryMeshes.forceAllSections;
import static com.matthieu.planariumview.client.scenery.SceneryMeshes.pendingSectionData;
import static com.matthieu.planariumview.client.scenery.SceneryMeshes.pendingSectionSet;
import static com.matthieu.planariumview.client.scenery.SceneryMeshes.pendingSectionStates;
import static com.matthieu.planariumview.client.scenery.SceneryMeshes.pendingSections;
import static com.matthieu.planariumview.client.scenery.SceneryMeshes.renderedBiomePalette;
import static com.matthieu.planariumview.client.scenery.SceneryMeshes.renderedBiomes;
import static com.matthieu.planariumview.client.scenery.SceneryMeshes.renderedLight;
import static com.matthieu.planariumview.client.scenery.SceneryMeshes.renderedOrigin;
import static com.matthieu.planariumview.client.scenery.SceneryMeshes.sectionMeshes;
import static com.matthieu.planariumview.client.scenery.SceneryPatcher.MAX_PATCH_BLOCKS;
import static com.matthieu.planariumview.client.scenery.SceneryPatcher.diffTemplates;
import static com.matthieu.planariumview.client.scenery.SceneryPatcher.loadTemplate;
import static com.matthieu.planariumview.client.scenery.SceneryPatcher.readPos;
import static com.matthieu.planariumview.client.scenery.SceneryPatcher.sceneryEntityLight;
import static com.matthieu.planariumview.client.scenery.SceneryPatcher.statePosByPos;
import static com.matthieu.planariumview.client.scenery.SceneryState.currentDim;
import static com.matthieu.planariumview.client.scenery.SceneryState.currentRadius;
import static com.matthieu.planariumview.client.scenery.SceneryState.lastInfo;
import static com.matthieu.planariumview.client.scenery.SceneryState.latestInfo;
import static com.matthieu.planariumview.client.scenery.SceneryState.renderData;

/** Reception des photos du decor, comparaisons asynchrones et nettoyage des ressources. */
final class SceneryReceiver {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClientView.class);

    static StructureTemplate pendingTemplate;
    static byte[] pendingLight;
    static OutsideViewPayload pendingInfo;
    /** Derniere photo recue, gardee pour reconstruire le rendu quand une option change. */
    private static StructureTemplate lastTemplate;
    /** Photo brute (NBT) de la derniere reception : sert a trouver les blocs qui ont change, sans tout relire. */
    private static net.minecraft.nbt.CompoundTag lastTemplateTag;
    private static byte[] lastLight;
    /** Blocs changes recus mais pas encore appliques au decor (null : rien en attente). */
    static Map<BlockPos, BlockChange> pendingPatch;
    static byte[] pendingPatchLight;
    static OutsideViewPayload pendingPatchInfo;
    /** Temps passe a comparer les photos recues depuis la derniere correction (pour le log). */
    static long pendingDiffNanos;
    /**
     * Les photos sont comparees sur un fil a part (10 a 20 ms pour 130 000 blocs), une a la fois et dans l'ordre.
     * Le numero de generation change a chaque reconstruction complete : les comparaisons encore en cours
     * deviennent alors inutiles et leur resultat est ignore.
     */
    private static final java.util.concurrent.ExecutorService DIFF_THREAD =
            java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "Planarium Panorama - comparaison du decor");
                thread.setDaemon(true);
                // Travail de fond : laisse passer le rendu quand le processeur est charge (comme ModernFix).
                thread.setPriority(Thread.NORM_PRIORITY - 1);
                return thread;
            });
    private static int diffGeneration;
    /**
     * Blocs recus par envoi partiel depuis la derniere photo complete : la photo gardee ne les contient pas.
     * Ils sont reappliques apres une reconstruction complete, et reverifies contre la photo complete suivante.
     */
    private static final Map<BlockPos, BlockChange> deltaOverlay = new HashMap<>();

    private SceneryReceiver() {
    }

    static void receive(OutsideViewPayload payload) {
        if (!payload.active()) {
            dispose();
            return;
        }
        boolean changedDimension = currentDim != null && !currentDim.equals(payload.pocketDim());
        boolean changedRadius = currentDim != null && currentRadius != payload.radius();
        if (changedDimension || changedRadius || (currentDim == null && renderData != null)) {
            disposeRender();
        }
        // Meme decor qu'avant (meme endroit, memes biomes) : on ne corrigera que les blocs qui ont change.
        // La comparaison se fait hors du fil de rendu ; le resultat revient ensuite par onDiffReady.
        boolean patchable = renderData != null && pendingTemplate == null && lastTemplateTag != null && latestInfo != null
                && java.util.Objects.equals(latestInfo.origin(), payload.origin())
                && java.util.Objects.equals(latestInfo.biomePalette(), payload.biomePalette())
                && java.util.Arrays.equals(latestInfo.biomes(), payload.biomes());
        currentDim = payload.pocketDim();
        currentRadius = payload.radius();
        net.minecraft.nbt.CompoundTag base = lastTemplateTag;
        // La nouvelle photo complete remplace les envois partiels recus avant elle ; leurs positions sont
        // reverifiees contre cette photo (un bloc a pu revenir a son etat d'avant).
        Set<BlockPos> overlayPositions = new HashSet<>(deltaOverlay.keySet());
        deltaOverlay.clear();
        lastTemplateTag = payload.template();
        latestInfo = payload;
        WeatherView.set(payload);
        lastLight = payload.light();
        if (patchable) {
            lastTemplate = null;
            int generation = diffGeneration;
            net.minecraft.nbt.CompoundTag next = payload.template();
            try {
                DIFF_THREAD.execute(() -> {
                    long start = System.nanoTime();
                    Map<BlockPos, BlockChange> changes = null;
                    try {
                        changes = diffTemplates(base, next, overlayPositions);
                    } catch (Exception e) {
                        LOGGER.debug("[Planarium View] Comparaison des photos impossible; reconstruction complete", e);
                    }
                    Map<BlockPos, BlockChange> result = changes;
                    long nanos = System.nanoTime() - start;
                    Minecraft.getInstance().execute(() -> onDiffReady(generation, payload.pocketDim(), payload, result, nanos));
                });
                return;
            } catch (java.util.concurrent.RejectedExecutionException e) {
                // Fil de comparaison indisponible : reconstruction complete ci-dessous.
            }
        }
        scheduleFullBuild();
    }

    /** Reconstruction complete a partir de la derniere photo recue ; les comparaisons en cours sont abandonnees. */
    private static void scheduleFullBuild() {
        diffGeneration++;
        if (lastTemplateTag == null) {
            return;
        }
        StructureTemplate template = loadTemplate(lastTemplateTag);
        pendingPatch = null;
        pendingPatchLight = null;
        pendingPatchInfo = null;
        pendingDiffNanos = 0L;
        reapplyOverlay();
        pendingTemplate = template;
        pendingLight = lastLight;
        pendingInfo = latestInfo;
        lastTemplate = template;
    }

    /** Resultat d'une comparaison (fil principal, dans l'ordre de reception des photos). */
    private static void onDiffReady(int generation, ResourceLocation dim, OutsideViewPayload payload,
                                    Map<BlockPos, BlockChange> changes, long nanos) {
        if (generation != diffGeneration || currentDim == null || !currentDim.equals(dim)) {
            return;
        }
        if (changes == null || (pendingPatch != null && pendingPatch.size() + changes.size() > MAX_PATCH_BLOCKS)) {
            scheduleFullBuild();
            return;
        }
        if (pendingPatch == null) {
            pendingPatch = new java.util.LinkedHashMap<>();
            // Envoi partiel (sans photo) : la lumiere et les infos restent celles de la derniere photo complete.
            pendingPatchLight = lastLight;
            pendingPatchInfo = latestInfo;
        }
        pendingPatch.putAll(changes);
        if (payload != null) {
            pendingPatchLight = payload.light();
            pendingPatchInfo = payload;
        }
        pendingDiffNanos += nanos;
    }

    /** Apres une reconstruction complete depuis la photo gardee : reappliquer les blocs recus depuis par envoi partiel. */
    private static void reapplyOverlay() {
        if (deltaOverlay.isEmpty()) {
            return;
        }
        pendingPatch = new java.util.LinkedHashMap<>(deltaOverlay);
        pendingPatchLight = lastLight;
        pendingPatchInfo = latestInfo;
    }

    /** Blocs changes envoyes seuls par le serveur (voir {@link OutsideViewDeltaPayload}). */
    static void receiveDelta(OutsideViewDeltaPayload payload) {
        if (currentDim == null || !currentDim.equals(payload.pocketDim()) || currentRadius != payload.radius()
                || latestInfo == null || !java.util.Objects.equals(latestInfo.origin(), payload.origin())
                || lastTemplateTag == null) {
            // Pas de photo complete correspondante ici : la prochaine photo complete remettra tout a jour.
            return;
        }
        Map<BlockPos, BlockChange> changes = new java.util.LinkedHashMap<>();
        try {
            net.minecraft.nbt.ListTag blocks = payload.changes().getList("blocks", net.minecraft.nbt.Tag.TAG_COMPOUND);
            for (int i = 0; i < blocks.size(); i++) {
                net.minecraft.nbt.CompoundTag entry = blocks.getCompound(i);
                BlockPos pos = readPos(entry);
                if (pos == null) continue;
                BlockState state = entry.contains("state", net.minecraft.nbt.Tag.TAG_COMPOUND)
                        ? net.minecraft.nbt.NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), entry.getCompound("state"))
                        : null;
                net.minecraft.nbt.CompoundTag nbt = entry.contains("nbt", net.minecraft.nbt.Tag.TAG_COMPOUND)
                        ? entry.getCompound("nbt") : null;
                changes.put(pos, new BlockChange(state, nbt));
            }
        } catch (Exception e) {
            LOGGER.debug("[Planarium View] Blocs changes illisibles; ignores jusqu'a la prochaine photo", e);
            return;
        }
        if (changes.isEmpty()) {
            return;
        }
        deltaOverlay.putAll(changes);
        // Passe par le meme fil que les comparaisons, pour rester dans l'ordre avec les photos deja en cours.
        int generation = diffGeneration;
        ResourceLocation dim = payload.pocketDim();
        try {
            DIFF_THREAD.execute(() -> Minecraft.getInstance().execute(
                    () -> onDiffReady(generation, dim, null, changes, 0L)));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            onDiffReady(generation, dim, null, changes, 0L);
        }
    }

    static void rebuild() {
        if (lastTemplate == null && lastTemplateTag != null) {
            lastTemplate = loadTemplate(lastTemplateTag);
        }
        if (lastTemplate != null && currentDim != null) {
            pendingPatch = null;
            pendingDiffNanos = 0L;
            diffGeneration++;
            reapplyOverlay();
            pendingTemplate = lastTemplate;
            pendingLight = lastLight;
            pendingInfo = latestInfo;
            forceAllSections = true;
        }
        MiniLight.rebuildAll();
    }

    /**
     * Un nouvel anneau lointain est arrive : le decor proche est reconstruit, pour que son bord se raccorde a l'anneau
     * (l'eau du bord ne s'abaisse plus vers le vide). Les sections actuelles restent visibles pendant ce temps.
     */
    static void farRingArrived() {
        if (renderData == null || lastTemplate == null && lastTemplateTag == null || currentDim == null) {
            return;
        }
        if (lastTemplate == null) {
            lastTemplate = loadTemplate(lastTemplateTag);
        }
        pendingPatch = null;
        pendingDiffNanos = 0L;
        diffGeneration++;
        reapplyOverlay();
        pendingTemplate = lastTemplate;
        pendingLight = lastLight;
        pendingInfo = latestInfo;
        forceAllSections = true;
    }

    static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ViewPrefsSender.reset();
        com.matthieu.planariumview.client.sound.MachineSounds.clear();
        OutsideRecordClient.clear();
        SceneryParticles.clear();
        SceneryBubbles.clear();
        MiniParticles.clear();
        dispose();
        OutsideMobs.logout();
        MiniLight.clear();
        com.matthieu.planariumview.client.mini.entities.MiniEntities.clear();
        SubLevelView.clear();
        LunarClient.clear();
    }

    static void dispose() {
        SubLevelView.clearOutside();
        FarRing.clear();
        NeighborPockets.clear();
        FarRing.knownRadius = 0;
        FlightView.clear();
        shownEdge = -1.0F;
        disposeRender();
        lastTemplate = null;
        lastTemplateTag = null;
        lastLight = null;
        lastInfo = null;
        latestInfo = null;
        OutsideAmbience.clear();
        SkySync.clear();
        WeatherView.clear();
        WallColors.clear();
        GiantSprites.clear();
        OutsideMobs.clear();
        OutsideSoundClient.clear();
        // Sortie, changement de bocal ou deconnexion : ne pas garder l'ancien monde client (et ses chunks).
        com.matthieu.planariumview.client.jar.ProjectorLighting.clear();
    }

    private static void disposeRender() {
        SceneryHooks.clearSceneryFrame();
        SceneryParticles.cancelHues();
        PuddlesCompat.clear();
        GiantEntities.clear();
        sceneryEntityLight.clear();
        releaseRenderData(renderData);
        for (SectionMesh mesh : sectionMeshes.values()) mesh.close();
        sectionMeshes.clear();
        pendingSections.clear();
        pendingSectionSet.clear();
        pendingSectionStates = Map.of();
        pendingSectionData = null;
        renderedLight = null;
        renderedBiomes = null;
        renderedOrigin = null;
        renderedBiomePalette = null;
        renderData = null;
        pendingPatch = null;
        pendingPatchLight = null;
        pendingPatchInfo = null;
        pendingDiffNanos = 0L;
        diffGeneration++;
        deltaOverlay.clear();
        statePosByPos = new HashMap<>();
        currentSectionStates = new HashMap<>();
        builtViewRadius = -1;
        pendingTemplate = null;
        forceAllSections = false;
        currentDim = null;
    }

    static void releaseRenderData(StructureRenderData data) {
        if (data == null) {
            return;
        }
        try {
            PlanariumRenderer.clearByteBuffers(data);
            for (VertexBuffer buffer : data.vertexBuffers.values()) {
                buffer.close();
            }
        } catch (Exception e) {
            LOGGER.warn("[Planarium View] Erreur en liberant le rendu", e);
        }
    }

    /** A neighbor snapshot arrived: only rebuild sections containing giant Planarium shells. */
    static void neighborPocketsChanged(ResourceLocation dim) {
        if (currentDim == null || !currentDim.equals(dim)) return;
        if (pendingTemplate != null) {
            forceAllSections = true;
            return;
        }
        if (renderData == null) return;
        for (var entry : currentSectionStates.entrySet()) {
            if (entry.getValue().stream().noneMatch(state -> state.state.getBlock()
                    instanceof com.hollingsworth.arsnouveau.common.block.Planarium)) continue;
            if (pendingSectionSet.add(entry.getKey())) pendingSections.addLast(entry.getKey());
        }
        if (!pendingSections.isEmpty()) {
            pendingSectionData = renderData;
            pendingSectionStates = currentSectionStates;
        }
    }
}

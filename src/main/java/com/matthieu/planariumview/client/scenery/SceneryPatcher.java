package com.matthieu.planariumview.client.scenery;

import com.hollingsworth.arsnouveau.client.renderer.tile.PlanariumRenderer.StructureRenderData;
import com.hollingsworth.arsnouveau.client.renderer.world.CulledStatePos;
import com.matthieu.planariumview.client.scenery.SceneryMeshes.SectionMesh;
import com.matthieu.planariumview.client.scenery.SceneryMeshes.SectionPos;
import com.matthieu.planariumview.client.scenery.entities.GiantEntities;
import com.matthieu.planariumview.compat.contraption_lights.ColorCompat;
import com.matthieu.planariumview.compat.puddle_flood.PuddlesCompat;
import com.matthieu.planariumview.config.Config;
import com.matthieu.planariumview.network.OutsideViewPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.matthieu.planariumview.client.scenery.ClientView.inDetail;
import static com.matthieu.planariumview.client.scenery.ClientView.updateRoundDetail;
import static com.matthieu.planariumview.client.scenery.ClientView.viewRadius;
import static com.matthieu.planariumview.client.scenery.ClientView.visibleSceneryPos;
import static com.matthieu.planariumview.client.scenery.SceneryMeshes.builtViewRadius;
import static com.matthieu.planariumview.client.scenery.SceneryMeshes.changedSections;
import static com.matthieu.planariumview.client.scenery.SceneryMeshes.currentSectionStates;
import static com.matthieu.planariumview.client.scenery.SceneryMeshes.forceAllSections;
import static com.matthieu.planariumview.client.scenery.SceneryMeshes.groupSections;
import static com.matthieu.planariumview.client.scenery.SceneryMeshes.markAffectedSections;
import static com.matthieu.planariumview.client.scenery.SceneryMeshes.markBox;
import static com.matthieu.planariumview.client.scenery.SceneryMeshes.markLightChanges;
import static com.matthieu.planariumview.client.scenery.SceneryMeshes.pendingSectionData;
import static com.matthieu.planariumview.client.scenery.SceneryMeshes.pendingSectionSet;
import static com.matthieu.planariumview.client.scenery.SceneryMeshes.pendingSectionStates;
import static com.matthieu.planariumview.client.scenery.SceneryMeshes.pendingSections;
import static com.matthieu.planariumview.client.scenery.SceneryMeshes.renderedBiomePalette;
import static com.matthieu.planariumview.client.scenery.SceneryMeshes.renderedBiomes;
import static com.matthieu.planariumview.client.scenery.SceneryMeshes.renderedLight;
import static com.matthieu.planariumview.client.scenery.SceneryMeshes.renderedOrigin;
import static com.matthieu.planariumview.client.scenery.SceneryMeshes.sectionMeshes;
import static com.matthieu.planariumview.client.scenery.SceneryState.currentRadius;
import static com.matthieu.planariumview.client.scenery.SceneryState.renderData;

/** Construction du decor et application des changements de blocs, de lumiere et de biomes. */
final class SceneryPatcher {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClientView.class);

    /** Au-dela de ce nombre de blocs changes, on reconstruit tout le decor plutot que de le corriger. */
    static final int MAX_PATCH_BLOCKS = 4096;

    /** Blocs du decor par position, gardes pour corriger le decor sans le reconstruire. */
    static Map<BlockPos, CulledStatePos> statePosByPos = new HashMap<>();

    /** Augmente a chaque changement du decor (les feux de camp sont alors recherches de nouveau). */
    static int sceneryVersion;

    /**
     * Part de l'air relie au Planarium (dans la photo) qui est a ciel ouvert (lumiere du ciel complete).
     * Une entree de caverne donne sur beaucoup d'air a ciel ouvert ; une caverne fermee, ou percee d'un petit trou, tres peu.
     */
    static volatile float openSkyShare = 1.0F;

    static final Map<BlockPos, Integer> blockEntityLight = new java.util.HashMap<>();

    /** Lumiere des entites du decor, calculee avec la source CL du faux monde et reutilisee pendant le rendu. */
    static final Map<net.minecraft.world.entity.Entity, Integer> sceneryEntityLight = new java.util.IdentityHashMap<>();

    /** Nouvel etat d'un bloc ({@code state} null : le bloc a disparu de la photo). */
    record BlockChange(BlockState state, net.minecraft.nbt.CompoundTag nbt) {
    }

    private SceneryPatcher() {
    }

    static StructureRenderData build(StructureTemplate template, byte[] light, OutsideViewPayload info,
                                             int radius, Vec3 camera) {
        long buildStart = System.nanoTime();
        // Gabarit vide : Ars ne construit pas son propre faux monde (aussitot remplace par le notre ci-dessous).
        StructureRenderData data = LitRenderingWorld.asGiant(() -> new StructureRenderData(new StructureTemplate(), 0L));
        data.statePosCache = statesOf(template);
        long templateNanos = System.nanoTime() - buildStart;
        long stageStart = System.nanoTime();
        // Remplace le faux monde d'Ars (lumiere 15 partout) par le notre, avec la vraie lumiere.
        // (et avec les couleurs du vrai biome pour l'herbe, les feuilles et l'eau).
        LitRenderingWorld lit = LitRenderingWorld.giant(Minecraft.getInstance().level, data.statePosCache);
        lit.useFarRingBeyond(radius);
        if (Config.realLighting() && light != null && light.length > 0) {
            lit.setLight(light, radius * 2 + 3);
        }
        if (info != null && Config.biomeColors()) {
            var registry = Minecraft.getInstance().level.registryAccess()
                    .registryOrThrow(net.minecraft.core.registries.Registries.BIOME);
            java.util.List<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> palette = new java.util.ArrayList<>();
            for (ResourceLocation id : info.biomePalette()) {
                var holder = registry.getHolder(net.minecraft.resources.ResourceKey.create(
                        net.minecraft.core.registries.Registries.BIOME, id));
                palette.add(holder.isPresent() ? holder.get()
                        : registry.getHolderOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS));
            }
            lit.setBiomes(palette, info.biomes(), radius * 2 + 1, info.origin());
            LOGGER.debug("[Planarium Panorama] Couleurs de biome : {} ({})",
                    lit.hasBiomes() ? "OK" : "donnees invalides", info.biomePalette());
        }
        data.fakeRenderingWorld = lit;
        openSkyShare = computeOpenSkyShare(lit, radius);
        long sceneryDataNanos = System.nanoTime() - stageStart;
        // On n'affiche pas le Planarium lui-meme ni le bloc juste au-dessus : ils occuperaient la poche.
        BlockPos planarium = new BlockPos(radius, radius, radius);
        BlockPos above = planarium.above();
        data.statePosCache.removeIf(p -> p.pos.equals(planarium) || p.pos.equals(above));
        // Rayon de vue limite par la distance d'affichage : on ne garde que les blocs dans ce rayon (a l'horizontale).
        int view = viewRadius();
        builtViewRadius = view;
        // Decor detaille en cylindre (voir DetailShape) : les coins sont dessines par l'anneau lointain.
        updateRoundDetail();
        data.statePosCache.removeIf(p -> !inDetail(p.pos.getX() - radius, p.pos.getZ() - radius, view));
        PuddlesCompat.prepare(lit, info);

        long specialLightNanos;
        ColorCompat.begin(data.fakeRenderingWorld);
        try {
            stageStart = System.nanoTime();
            computeBlockEntityLight(data, info);
            specialLightNanos = System.nanoTime() - stageStart;
        } finally {
            ColorCompat.end();
        }

        Map<SectionPos, List<CulledStatePos>> sectionStates = groupSections(data.statePosCache);
        for (BlockPos pos : PuddlesCompat.extraPositions(lit)) {
            if (visibleSceneryPos(pos, radius, view)) sectionStates.computeIfAbsent(SectionPos.of(pos), ignored -> new ArrayList<>());
        }
        currentSectionStates = sectionStates;
        Map<BlockPos, CulledStatePos> byPos = new HashMap<>(data.statePosCache.size() * 2);
        for (CulledStatePos state : data.statePosCache) byPos.put(state.pos, state);
        statePosByPos = byPos;
        sceneryVersion++;
        Set<SectionPos> carriedSections = new HashSet<>(pendingSectionSet);
        Set<SectionPos> dirtySections = changedSections(renderData, data, sectionStates, light, info, radius);
        dirtySections.addAll(carriedSections);
        for (SectionPos existing : new HashSet<>(sectionMeshes.keySet())) {
            if (!sectionStates.containsKey(existing)) {
                SectionMesh removed = sectionMeshes.remove(existing);
                if (removed != null) removed.close();
            }
        }
        pendingSections.clear();
        pendingSectionSet.clear();
        pendingSectionData = data;
        pendingSectionStates = sectionStates;
        dirtySections.removeIf(key -> !sectionStates.containsKey(key));
        List<SectionPos> orderedSections = new ArrayList<>(dirtySections);
        orderedSections.sort(Comparator.comparingDouble(section -> section.distanceFromPlanariumSqr(radius)));
        for (SectionPos section : orderedSections) {
            pendingSections.addLast(section);
            pendingSectionSet.add(section);
        }
        if (pendingSections.isEmpty()) pendingSectionData = null;
        renderedLight = light == null ? null : light.clone();
        renderedBiomePalette = info == null ? null : info.biomePalette();
        renderedBiomes = info == null || info.biomes() == null ? null : info.biomes().clone();
        renderedOrigin = info == null ? null : info.origin();
        forceAllSections = false;
        stageStart = System.nanoTime();
        GiantSprites.collect(data);
        PuddlesCompat.collectSprites(lit);
        long spritesNanos = System.nanoTime() - stageStart;
        LOGGER.debug("[Planarium Panorama] Preparation de {} blocs : total={} ms, gabarit={} ms, donnees={} ms, " +
                        "lumiere speciale={} ms, sections a retesseler={}, sprites={} ms",
                data.statePosCache.size(), (System.nanoTime() - buildStart) / 1_000_000L,
                templateNanos / 1_000_000L, sceneryDataNanos / 1_000_000L,
                specialLightNanos / 1_000_000L, pendingSections.size(), spritesNanos / 1_000_000L);
        return data;
    }

    /** Lit une photo recue (NBT) en gabarit. */
    static StructureTemplate loadTemplate(net.minecraft.nbt.CompoundTag tag) {
        StructureTemplate template = new StructureTemplate();
        template.load(BuiltInRegistries.BLOCK.asLookup(), tag);
        return template;
    }

    /** Blocs du gabarit, comme Ars les prepare (sans construire son faux monde). */
    static ArrayList<CulledStatePos> statesOf(StructureTemplate template) {
        ArrayList<CulledStatePos> states = new ArrayList<>();
        var palettes = ((com.hollingsworth.arsnouveau.common.mixin.structure.StructureTemplateAccessor) template).getPalettes();
        if (palettes.isEmpty()) {
            return states;
        }
        for (StructureTemplate.StructureBlockInfo info : palettes.get(0).blocks()) {
            states.add(new CulledStatePos(info.state(), info.pos(), info.nbt()));
        }
        return states;
    }

    /**
     * Compare deux photos (NBT) et renvoie les blocs qui ont change, ou null si elles ne sont pas comparables
     * (format inattendu, trop de changements) : il faut alors tout reconstruire.
     * Les palettes sont comparees par contenu, car leur ordre change des qu'un nouvel etat de bloc apparait.
     */
    static Map<BlockPos, BlockChange> diffTemplates(net.minecraft.nbt.CompoundTag oldTag,
                                                           net.minecraft.nbt.CompoundTag newTag,
                                                           Set<BlockPos> overlayPositions) {
        int compound = net.minecraft.nbt.Tag.TAG_COMPOUND;
        int list = net.minecraft.nbt.Tag.TAG_LIST;
        if (!oldTag.contains("palette", list) || !newTag.contains("palette", list)
                || !oldTag.contains("blocks", list) || !newTag.contains("blocks", list)) {
            return null;
        }
        net.minecraft.nbt.ListTag oldPalette = oldTag.getList("palette", compound);
        net.minecraft.nbt.ListTag newPalette = newTag.getList("palette", compound);
        net.minecraft.nbt.ListTag oldBlocks = oldTag.getList("blocks", compound);
        net.minecraft.nbt.ListTag newBlocks = newTag.getList("blocks", compound);
        Map<net.minecraft.nbt.Tag, Integer> oldIndex = new HashMap<>();
        for (int i = 0; i < oldPalette.size(); i++) oldIndex.putIfAbsent(oldPalette.get(i), i);
        int[] newToOld = new int[newPalette.size()];
        for (int i = 0; i < newPalette.size(); i++) {
            Integer old = oldIndex.get(newPalette.get(i));
            newToOld[i] = old == null ? -1 : old;
        }
        BlockState[] decoded = new BlockState[newPalette.size()];
        Map<BlockPos, BlockChange> changes = new java.util.LinkedHashMap<>();

        // Cas courant : memes positions dans le meme ordre, seuls des etats changent.
        boolean aligned = oldBlocks.size() == newBlocks.size();
        if (aligned) {
            for (int i = 0; i < newBlocks.size(); i++) {
                net.minecraft.nbt.CompoundTag before = oldBlocks.getCompound(i);
                net.minecraft.nbt.CompoundTag now = newBlocks.getCompound(i);
                // Positions comparees telles quelles (sans les decoder), pour aller vite sur 100 000+ blocs.
                net.minecraft.nbt.Tag posTag = now.get("pos");
                if (posTag == null || !posTag.equals(before.get("pos"))) {
                    aligned = false;
                    break;
                }
                if (!sameBlock(before, now, newToOld)) {
                    BlockPos pos = readPos(now);
                    if (pos == null) return null;
                    changes.put(pos, changeOf(now, newPalette, decoded));
                    if (changes.size() > MAX_PATCH_BLOCKS) return null;
                }
            }
        }
        // Des blocs sont apparus ou ont disparu : comparaison par position.
        if (!aligned) {
            changes.clear();
            Map<BlockPos, net.minecraft.nbt.CompoundTag> old = new HashMap<>(oldBlocks.size() * 2);
            for (int i = 0; i < oldBlocks.size(); i++) {
                net.minecraft.nbt.CompoundTag before = oldBlocks.getCompound(i);
                BlockPos pos = readPos(before);
                if (pos == null) return null;
                old.put(pos, before);
            }
            for (int i = 0; i < newBlocks.size(); i++) {
                net.minecraft.nbt.CompoundTag now = newBlocks.getCompound(i);
                BlockPos pos = readPos(now);
                if (pos == null) return null;
                net.minecraft.nbt.CompoundTag before = old.remove(pos);
                if (before == null || !sameBlock(before, now, newToOld)) {
                    changes.put(pos, changeOf(now, newPalette, decoded));
                    if (changes.size() > MAX_PATCH_BLOCKS) return null;
                }
            }
            for (BlockPos removed : old.keySet()) {
                changes.put(removed, new BlockChange(null, null));
                if (changes.size() > MAX_PATCH_BLOCKS) return null;
            }
        }
        // Blocs recus par envoi partiel apres l'ancienne photo : on prend leur etat dans la nouvelle photo,
        // meme s'il est identique a l'ancienne (le decor affiche, lui, a l'etat de l'envoi partiel).
        if (!overlayPositions.isEmpty()) {
            Set<BlockPos> missing = new HashSet<>(overlayPositions);
            for (int i = 0; i < newBlocks.size() && !missing.isEmpty(); i++) {
                net.minecraft.nbt.CompoundTag now = newBlocks.getCompound(i);
                BlockPos pos = readPos(now);
                if (pos != null && missing.remove(pos)) {
                    changes.put(pos, changeOf(now, newPalette, decoded));
                }
            }
            for (BlockPos removed : missing) {
                changes.put(removed, new BlockChange(null, null));
            }
            if (changes.size() > MAX_PATCH_BLOCKS) return null;
        }
        return changes;
    }

    private static boolean sameBlock(net.minecraft.nbt.CompoundTag before, net.minecraft.nbt.CompoundTag now, int[] newToOld) {
        int state = now.getInt("state");
        int mapped = state >= 0 && state < newToOld.length ? newToOld[state] : -1;
        return mapped >= 0 && mapped == before.getInt("state") && java.util.Objects.equals(before.get("nbt"), now.get("nbt"));
    }

    private static BlockChange changeOf(net.minecraft.nbt.CompoundTag block, net.minecraft.nbt.ListTag palette,
                                        BlockState[] decoded) {
        int index = block.getInt("state");
        if (index < 0 || index >= palette.size()) {
            throw new IllegalStateException("Etat de bloc hors palette : " + index);
        }
        if (decoded[index] == null) {
            decoded[index] = net.minecraft.nbt.NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(),
                    palette.getCompound(index));
        }
        net.minecraft.nbt.CompoundTag nbt = block.contains("nbt", net.minecraft.nbt.Tag.TAG_COMPOUND)
                ? block.getCompound("nbt") : null;
        return new BlockChange(decoded[index], nbt);
    }

    /** Position d'un bloc de la photo (liste de 3 entiers, ou tableau d'entiers). */
    static BlockPos readPos(net.minecraft.nbt.CompoundTag block) {
        if (block.contains("pos", net.minecraft.nbt.Tag.TAG_INT_ARRAY)) {
            int[] pos = block.getIntArray("pos");
            return pos.length == 3 ? new BlockPos(pos[0], pos[1], pos[2]) : null;
        }
        net.minecraft.nbt.ListTag pos = block.getList("pos", net.minecraft.nbt.Tag.TAG_INT);
        return pos.size() == 3 ? new BlockPos(pos.getInt(0), pos.getInt(1), pos.getInt(2)) : null;
    }

    /**
     * Corrige le decor deja construit avec les blocs qui ont change, au lieu de tout reconstruire :
     * faux monde, blocs speciaux, lumiere, puis seules les sections touchees sont retesselees.
     */
    static void applyPatch(StructureRenderData data, Map<BlockPos, BlockChange> patch, byte[] light,
                                   OutsideViewPayload info, int radius, long diffNanos) {
        long start = System.nanoTime();
        sceneryVersion++;
        LitRenderingWorld lit = (LitRenderingWorld) data.fakeRenderingWorld;
        Set<SectionPos> dirty = new HashSet<>();
        BlockPos planarium = new BlockPos(radius, radius, radius);
        BlockPos above = planarium.above();
        int view = builtViewRadius;
        Set<CulledStatePos> removedStates = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

        // Blocs qui arretent ou laissent passer la lumiere avant le changement (pour l'ouverture sur le ciel).
        Set<BlockPos> opaqueBefore = new HashSet<>();
        boolean blockEntitiesChanged = false;
        // Une source de lumiere changee (couleur differente a intensite egale, par exemple) ne change pas la lumiere
        // vanilla : sans ca, seules les sections touchant le bloc etaient refaites et une bande gardait l'ancienne teinte.
        boolean emitterChanged = false;
        for (Map.Entry<BlockPos, BlockChange> entry : patch.entrySet()) {
            BlockPos pos = entry.getKey();
            if (isOpaque(lit, pos)) opaqueBefore.add(pos);
            if (lit.blockEntityMap.containsKey(pos)) blockEntitiesChanged = true;
            if (com.matthieu.planariumview.compat.contraption_lights.ColorCompat.present() && Config.coloredLight()) {
                BlockState before = lit.getBlockState(pos);
                BlockState after = entry.getValue().state();
                if (!java.util.Objects.equals(before, after) && (before.getLightEmission(lit, pos) > 0
                        || after != null && after.getLightEmission(lit, pos) > 0)) {
                    // Portee d'une lumiere coloree : 15 blocs autour de la source.
                    markBox(dirty, pos.getX() - 15, pos.getY() - 15, pos.getZ() - 15,
                            pos.getX() + 15, pos.getY() + 15, pos.getZ() + 15);
                    emitterChanged = true;
                }
            }
        }
        Set<BlockState> newStates = new HashSet<>();

        // 1. Faux monde : les etats captures avant de preparer les blocs speciaux et le rendu.
        for (Map.Entry<BlockPos, BlockChange> entry : patch.entrySet()) {
            if (entry.getValue().state() == null) {
                lit.positions.remove(entry.getKey());
            } else {
                lit.setBlock(entry.getKey(), entry.getValue().state(), 0);
            }
        }
        // Cache de couleurs de Contraption Lights partage par toute la photo : effacer seulement ces sections.
        ColorCompat.blocksChanged(lit, patch.keySet());
        for (Map.Entry<BlockPos, BlockChange> entry : patch.entrySet()) {
            BlockPos pos = entry.getKey();
            BlockChange change = entry.getValue();
            // Meme conservation que dans PlanariumRenderingWorldMixin, y compris apres plantation ou croissance.
            BlockState captured = change.state();
            placeBlockEntity(lit, pos, captured, change.nbt());
            if (lit.blockEntityMap.containsKey(pos)) blockEntitiesChanged = true;
            if (captured != null) newStates.add(captured);

            // 2. Blocs a dessiner (memes filtres que la construction complete).
            boolean kept = !pos.equals(planarium) && !pos.equals(above) && (view < 0
                    || inDetail(pos.getX() - radius, pos.getZ() - radius, Math.min(view, radius)));
            CulledStatePos statePos = statePosByPos.get(pos);
            SectionPos section = SectionPos.of(pos);
            if (change.state() == null || !kept) {
                if (statePos != null) {
                    statePosByPos.remove(pos);
                    removedStates.add(statePos);
                    List<CulledStatePos> sectionList = currentSectionStates.get(section);
                    if (sectionList != null) sectionList.remove(statePos);
                }
            } else if (statePos == null) {
                CulledStatePos added = new CulledStatePos(change.state(), pos, change.nbt());
                data.statePosCache.add(added);
                statePosByPos.put(pos, added);
                currentSectionStates.computeIfAbsent(section, ignored -> new ArrayList<>()).add(added);
            } else {
                statePos.state = change.state();
                statePos.tag = change.nbt();
                statePos.setNeedsUpdate(true);
                statePos.setSkipRender(false);
            }
            // Les faces cachees des voisins dependent de ce bloc.
            for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.values()) {
                CulledStatePos neighbour = statePosByPos.get(pos.relative(direction));
                if (neighbour != null) {
                    neighbour.setNeedsUpdate(true);
                    neighbour.setSkipRender(false);
                }
            }
            markAffectedSections(dirty, pos);
        }
        if (!removedStates.isEmpty()) {
            data.statePosCache.removeIf(removedStates::contains);
        }
        PuddlesCompat.blocksChanged(lit, patch.keySet());

        // 3. Lumiere : seules les sections proches des cases qui ont change.
        boolean lightChanged = !java.util.Arrays.equals(light, renderedLight);
        if (lightChanged) {
            if (!markLightChanges(dirty, light, renderedLight, radius * 2 + 3)) {
                dirty.addAll(currentSectionStates.keySet());
            }
            if (Config.realLighting()) {
                lit.setLight(light == null ? new byte[0] : light, radius * 2 + 3);
            }
            renderedLight = light == null ? null : light.clone();
        }

        // 4. Ce qui depend des blocs ou de la lumiere, recalcule seulement si besoin.
        long stageStart = System.nanoTime();
        boolean opacityChanged = false;
        for (BlockPos pos : patch.keySet()) {
            if (isOpaque(lit, pos) != opaqueBefore.contains(pos)) {
                opacityChanged = true;
                break;
            }
        }
        // Ouverture sur le ciel (1500 rayons) : seulement si la lumiere ou l'opacite d'un bloc a change.
        boolean skyRecomputed = lightChanged || opacityChanged;
        if (skyRecomputed) {
            openSkyShare = computeOpenSkyShare(lit, radius);
        }
        long skyNanos = System.nanoTime() - stageStart;
        stageStart = System.nanoTime();
        if (lightChanged || blockEntitiesChanged || emitterChanged) {
            ColorCompat.begin(lit);
            try {
                computeBlockEntityLight(data, info);
            } finally {
                ColorCompat.end();
            }
        }
        // Textures animees : seulement celles des nouveaux etats, sans reparcourir tout le decor.
        GiantSprites.add(newStates);
        long restNanos = System.nanoTime() - stageStart;

        // 5. Sections a retesseler, les plus proches du Planarium d'abord.
        dirty.retainAll(currentSectionStates.keySet());
        List<SectionPos> ordered = new ArrayList<>(dirty);
        ordered.sort(Comparator.comparingDouble(section -> section.distanceFromPlanariumSqr(radius)));
        for (SectionPos section : ordered) {
            if (pendingSectionSet.add(section)) pendingSections.addLast(section);
        }
        if (!pendingSections.isEmpty()) {
            pendingSectionData = data;
            pendingSectionStates = currentSectionStates;
        }
        com.matthieu.planariumview.client.sound.SceneryWaterfalls.blocksChanged(lit, radius, patch.keySet());
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("[Planarium Panorama] Correction du decor : {} blocs changes, lumiere={}, comparaison (hors rendu)={} ms, " +
                            "correction={} ms (ciel={} ms{}, blocs speciaux et textures={} ms), sections a retesseler={}",
                    patch.size(), lightChanged, String.format(java.util.Locale.ROOT, "%.1f", diffNanos / 1e6),
                    String.format(java.util.Locale.ROOT, "%.1f", (System.nanoTime() - start) / 1e6),
                    String.format(java.util.Locale.ROOT, "%.1f", skyNanos / 1e6), skyRecomputed ? "" : ", non recalcule",
                    String.format(java.util.Locale.ROOT, "%.1f", restNanos / 1e6), dirty.size());
        }
    }

    /** Meme critere que le calcul de l'ouverture sur le ciel : le bloc arrete completement la lumiere. */
    private static boolean isOpaque(LitRenderingWorld world, BlockPos pos) {
        try {
            return world.getBlockState(pos).getLightBlock(world, pos) >= 15;
        } catch (Exception e) {
            return false;
        }
    }

    /** Remplace le bloc special d'une position (ou le met a jour s'il est du meme type). */
    private static void placeBlockEntity(LitRenderingWorld world, BlockPos pos, BlockState state,
                                         net.minecraft.nbt.CompoundTag nbt) {
        BlockEntity existing = world.blockEntityMap.get(pos);
        if (state == null || !state.hasBlockEntity()
                || !(state.getBlock() instanceof net.minecraft.world.level.block.EntityBlock entityBlock)) {
            world.blockEntityMap.remove(pos);
            return;
        }
        try {
            net.minecraft.nbt.CompoundTag data = nbt != null ? nbt : new net.minecraft.nbt.CompoundTag();
            if (existing != null && existing.getBlockState() == state) {
                existing.loadWithComponents(data, world.registryAccess());
                return;
            }
            world.blockEntityMap.remove(pos);
            BlockEntity blockEntity = entityBlock.newBlockEntity(pos, state);
            if (blockEntity == null) {
                return;
            }
            com.matthieu.planariumview.compat.create.CreateCopies.attach(blockEntity, world, Minecraft.getInstance().level);
            blockEntity.onLoad();
            blockEntity.loadWithComponents(data, world.registryAccess());
            world.blockEntityMap.put(pos, blockEntity);
        } catch (Exception e) {
            world.blockEntityMap.remove(pos);
            LOGGER.debug("[Planarium View] Bloc special ignore", e);
        }
    }

    /** Lumiere des blocs speciaux, calculee pendant que Contraption Lights lit notre faux monde (ColorCompat actif). */
    private static void computeBlockEntityLight(StructureRenderData data, OutsideViewPayload info) {
        // Couleur de Contraption Lights pour les particules, la pluie et la neige du decor (voir SceneryParticles.readHues).
        if (data.fakeRenderingWorld instanceof LitRenderingWorld lit) {
            SceneryParticles.readHues(lit, data.statePosCache, true, currentRadius, viewRadius());
        }
        blockEntityLight.clear();
        sceneryEntityLight.clear();
        for (BlockPos pos : data.fakeRenderingWorld.blockEntityMap.keySet()) {
            if (!visibleSceneryPos(pos, currentRadius, viewRadius())) continue;
            try {
                int light = LevelRenderer.getLightColor(data.fakeRenderingWorld, pos);
                if (com.matthieu.planariumview.compat.contraption_lights.ColorCompat.building() && (light & 0xF0) != 0
                        && (light & ~0x00F000F0) == 0) {
                    // Pas de couleur dans la case du bloc (boite a outils de Create...) : celle d'une case voisine.
                    light |= FaceHueConsumer.hueAround(data.fakeRenderingWorld, pos);
                }
                blockEntityLight.put(pos, light);
            } catch (Exception e) {
                LOGGER.debug("[Planarium View] Lumiere de bloc special ignoree", e);
            }
        }
        if (info != null && data.fakeRenderingWorld instanceof net.minecraft.world.level.Level fakeWorld) {
            for (net.minecraft.world.entity.Entity entity : GiantEntities.entities(data, fakeWorld, info.template(), info.origin())) {
                try {
                    sceneryEntityLight.put(entity, GiantEntities.packedLight(fakeWorld, entity, 0.0F));
                } catch (Exception e) {
                    LOGGER.debug("[Planarium View] Lumiere d'entite du decor ignoree", e);
                }
            }
        }
    }

    /**
     * Part des directions, vues du Planarium, qui menent dehors : un rayon compte s'il atteint de l'air
     * dehors (lumiere du ciel 12 ou plus, par une fenetre aussi) ou sort de la zone captee par de l'air bien eclaire par le ciel, sans toucher de bloc opaque.
     * A l'entree d'une caverne, l'ouverture occupe une bonne part de la vue ; au fond, aucune.
     */
    private static float computeOpenSkyShare(LitRenderingWorld lit, int radius) {
        try {
            int size = radius * 2 + 1;
            int rays = 1500;
            int open = 0;
            double center = radius + 0.5;
            double golden = Math.PI * (3.0 - Math.sqrt(5.0));
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int i = 0; i < rays; i++) {
                double dy = 1.0 - 2.0 * (i + 0.5) / rays;
                double ring = Math.sqrt(1.0 - dy * dy);
                double angle = golden * i;
                double dx = Math.cos(angle) * ring;
                double dz = Math.sin(angle) * ring;
                boolean escaped = true;
                int lastSky = 0;
                for (double t = 0.75; t <= radius * 1.8; t += 0.3) {
                    int x = (int) Math.floor(center + dx * t);
                    int y = (int) Math.floor(center + dy * t);
                    int z = (int) Math.floor(center + dz * t);
                    if (x < 0 || y < 0 || z < 0 || x >= size || y >= size || z >= size) {
                        // Sortie de la zone : dehors seulement si l'air au bord recoit le ciel (pas une grande grotte).
                        escaped = lastSky >= 8;
                        break;
                    }
                    if (x == radius && y == radius && z == radius) {
                        continue;
                    }
                    pos.set(x, y, z);
                    if (lit.getBlockState(pos).getLightBlock(lit, pos) >= 15) {
                        escaped = false;
                        break;
                    }
                    lastSky = lit.getBrightness(net.minecraft.world.level.LightLayer.SKY, pos);
                    if (lastSky >= 12) {
                        break;
                    }
                }
                if (escaped) {
                    open++;
                }
            }
            float share = open / (float) rays;
            LOGGER.debug("[Planarium Panorama] Vue vers dehors depuis le Planarium : {} rayons sur {}", open, rays);
            return share;
        } catch (Exception e) {
            return 1.0F;
        }
    }

    /** Une flaque change : retesseler son voisinage avec le meme budget que les mises a jour du serveur. */
    static void visualBlocksChanged(LitRenderingWorld world, Collection<BlockPos> positions) {
        // Pas d'effacement des couleurs de Contraption Lights : une flaque ne bloque ni n'emet de lumiere. Les effacer
        // forcait Contraption Lights a tout recalculer autour de chaque flaque, une reconstruction de section pouvait
        // alors prendre pres d'une seconde (gels sous la pluie).
        if (renderData == null || renderData.fakeRenderingWorld != world || positions.isEmpty()) return;
        Set<SectionPos> dirty = new HashSet<>();
        for (BlockPos pos : positions) {
            if (!visibleSceneryPos(pos, currentRadius, builtViewRadius)) continue;
            if (PuddlesCompat.extraPositions(world).contains(pos)) {
                currentSectionStates.computeIfAbsent(SectionPos.of(pos), ignored -> new ArrayList<>());
            }
            markAffectedSections(dirty, pos);
            CulledStatePos state = statePosByPos.get(pos);
            if (state != null) {
                state.setNeedsUpdate(true);
                state.setSkipRender(false);
            }
            for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.values()) {
                CulledStatePos neighbour = statePosByPos.get(pos.relative(direction));
                if (neighbour != null) {
                    neighbour.setNeedsUpdate(true);
                    neighbour.setSkipRender(false);
                }
            }
        }
        for (SectionPos section : dirty) {
            if ((currentSectionStates.containsKey(section) || sectionMeshes.containsKey(section))
                    && pendingSectionSet.add(section)) pendingSections.addLast(section);
        }
        if (!pendingSections.isEmpty()) {
            pendingSectionData = renderData;
            pendingSectionStates = currentSectionStates;
        }
    }
}

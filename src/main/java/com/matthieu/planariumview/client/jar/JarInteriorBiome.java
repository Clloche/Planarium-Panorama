package com.matthieu.planariumview.client.jar;

import com.matthieu.planariumview.client.sound.OutsideAmbience;
import com.matthieu.planariumview.config.Config;

import com.hollingsworth.arsnouveau.ArsNouveau;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * Cote client seulement : donne aux chunks du bocal le biome qui entoure le Planarium.
 * L'herbe, les feuilles et l'eau de l'interieur prennent donc les couleurs du biome exterieur.
 * Rien n'est modifie sur le serveur ni dans la sauvegarde : les chunks d'origine reviennent
 * des qu'on quitte le bocal ou que le serveur les renvoie.
 */
public final class JarInteriorBiome {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Les chunks qui contiennent le bocal (x et z de -1 a 32). */
    private static final int MIN_CHUNK = -1;
    private static final int MAX_CHUNK = 2;
    private static int tick;
    private static int redrawAt1 = -1;
    private static int redrawAt2 = -1;
    private static Holder<Biome> lastLogged;


    private JarInteriorBiome() {
    }

    public static void init() {
        NeoForge.EVENT_BUS.addListener(JarInteriorBiome::onClientTick);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        ++tick;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (tick == redrawAt1 || tick == redrawAt2) {
            redraw(mc, level);
        }
        if (tick % 20 != 0) {
            return;
        }
        if (level == null || !Config.biomeColors()
                || !level.dimensionTypeRegistration().is(ArsNouveau.DIMENSION_TYPE_KEY)) {
            return;
        }
        Holder<Biome> outside = OutsideAmbience.knownBiome(level);
        if (outside == null) {
            return;
        }
        try {
            apply(mc, level, outside);
        } catch (Exception e) {
            LOGGER.warn("[Planarium Panorama] Impossible d'appliquer le biome exterieur au bocal", e);
        }
    }

    private static void apply(Minecraft mc, ClientLevel level, Holder<Biome> outside) {
        boolean changed = false;
        int changedSections = 0;
        for (int cx = MIN_CHUNK; cx <= MAX_CHUNK; cx++) {
            for (int cz = MIN_CHUNK; cz <= MAX_CHUNK; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null) {
                    continue;
                }
                LevelChunkSection[] sections = chunk.getSections();
                for (int i = 0; i < sections.length; i++) {
                    LevelChunkSection section = sections[i];
                    if (section.getNoiseBiome(0, 0, 0).equals(outside)) {
                        continue;
                    }
                    int sectionY = level.getSectionYFromSectionIndex(i);
                    fill(section, outside, cx, sectionY, cz);
                    mc.levelRenderer.setSectionDirty(cx, sectionY, cz);
                    changed = true;
                    changedSections++;
                }
            }
        }
        if (changed) {
            level.clearTintCaches();
            // Avec Sodium, une section dont le premier rendu est en cours ignore la demande de redessin : elle garde
            // l'ancien biome (l'eau surtout). On redessine donc encore le bocal 2 s et 5 s plus tard.
            redrawAt1 = tick + 40;
            redrawAt2 = tick + 100;
            if (!outside.equals(lastLogged)) {
                lastLogged = outside;
                LOGGER.debug("[Planarium Panorama] Biome de l'interieur du bocal : {} ({} sections)",
                        outside.unwrapKey().map(k -> k.location().toString()).orElse("?"), changedSections);
            }
        }
    }

    /** Redessine toutes les sections du bocal et celles d'a cote (le melange des couleurs deborde sur les voisines). */
    private static void redraw(Minecraft mc, ClientLevel level) {
        if (level == null || !level.dimensionTypeRegistration().is(ArsNouveau.DIMENSION_TYPE_KEY)) {
            return;
        }
        level.clearTintCaches();
        for (int cx = MIN_CHUNK - 1; cx <= MAX_CHUNK + 1; cx++) {
            for (int cz = MIN_CHUNK - 1; cz <= MAX_CHUNK + 1; cz++) {
                for (int sy = -1; sy <= 3; sy++) {
                    mc.levelRenderer.setSectionDirty(cx, sy, cz);
                }
            }
        }
    }

    /**
     * Change les biomes de la section sur place. On modifie le meme conteneur au lieu d'en creer un nouveau :
     * Sodium garde une reference vers ce conteneur pour reconstruire le rendu, il verrait sinon l'ancien.
     */
    @SuppressWarnings("unchecked")
    private static void fill(LevelChunkSection section, Holder<Biome> outside, int cx, int sectionY, int cz) {
        if (section.getBiomes() instanceof PalettedContainer<?> container) {
            PalettedContainer<Holder<Biome>> biomes = (PalettedContainer<Holder<Biome>>) container;
            for (int x = 0; x < 4; x++) {
                for (int y = 0; y < 4; y++) {
                    for (int z = 0; z < 4; z++) {
                        biomes.getAndSet(x, y, z, outside);
                    }
                }
            }
        } else {
            section.fillBiomesFromNoise((x, y, z, sampler) -> outside, null,
                    QuartPos.fromSection(cx), QuartPos.fromSection(sectionY), QuartPos.fromSection(cz));
        }
    }
}

package com.matthieu.planariumview.diagnostics;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Rapport du bilan des mixins (voir MixinHealth) : une ligne par mod au premier monde ouvert (bilan provisoire : des
 * classes d'ecrans ou d'effets ne sont pas encore chargees). Les echecs confirmes plus tard sont ecrits au moment ou leur
 * classe se charge, une seule fois chacun.
 */
public final class MixinHealthReport {
    private static final Logger LOGGER = LoggerFactory.getLogger("planariumview");
    /** Dossier de compatibilite -> nom affiche et identifiants possibles du mod (pour sa version). */
    private static final Map<String, String[]> MODS = Map.ofEntries(
            Map.entry(MixinHealth.CORE, new String[]{"Planarium Panorama (Minecraft, NeoForge)"}),
            Map.entry("alexs_caves", new String[]{"Alex's Caves", "alexscaves"}),
            Map.entry("ambient_sounds", new String[]{"AmbientSounds", "ambientsounds"}),
            Map.entry("ars_nouveau", new String[]{"Ars Nouveau", "ars_nouveau"}),
            Map.entry("contraption_lights", new String[]{"Contraption Lights", "contraptionlights"}),
            Map.entry("create", new String[]{"Create", "create"}),
            Map.entry("distant_horizons", new String[]{"Distant Horizons", "distanthorizons"}),
            Map.entry("dynamic_surroundings", new String[]{"Dynamic Surroundings", "dsurround"}),
            Map.entry("enhanced_celestials", new String[]{"Enhanced Celestials", "enhancedcelestials"}),
            Map.entry("flywheel", new String[]{"Flywheel", "flywheel"}),
            Map.entry("puddle_flood", new String[]{"Puddle Flood", "puddleflood"}),
            Map.entry("travelers_titles", new String[]{"Traveler's Titles", "travelerstitles"}),
            Map.entry("yacl", new String[]{"YACL", "yet_another_config_lib_v3"}));
    private static boolean summarized;

    private MixinHealthReport() {
    }

    public static void init(IEventBus modBus) {
        modBus.addListener(FMLLoadCompleteEvent.class, event -> loadComplete());
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, event -> summary());
    }

    /** A la fin du chargement des mods, seule l'installation est exigee : les cibles peuvent charger plus tard. */
    private static void loadComplete() {
        String reason = MixinHealth.unavailableReason();
        if (reason != null) {
            MixinHealth.unavailable(reason);
        }
    }

    /** Premier monde ouvert (serveur demarre, ou client connecte a un serveur) : bilan provisoire, une seule fois. */
    public static synchronized void summary() {
        if (summarized) return;
        summarized = true;
        String reason = MixinHealth.unavailableReason();
        // Une cible client suffit aussi : rejoindre un serveur distant ne charge pas forcement ServerLevel.
        if (reason == null && !MixinHealth.hasObservedTarget()) {
            reason = "aucune cible de nos mixins observee par l'extension au premier monde ouvert";
        }
        if (reason != null) {
            MixinHealth.unavailable(reason);
            return;
        }
        Map<String, List<MixinHealth.Requirement>> groups = new TreeMap<>((a, b) -> a.equals(b) ? 0
                : a.equals(MixinHealth.CORE) ? -1 : b.equals(MixinHealth.CORE) ? 1 : a.compareTo(b));
        for (MixinHealth.Requirement requirement : MixinHealth.requirements()) {
            groups.computeIfAbsent(requirement.group(), key -> new ArrayList<>()).add(requirement);
        }
        for (Map.Entry<String, List<MixinHealth.Requirement>> entry : groups.entrySet()) {
            report(entry.getKey(), entry.getValue());
        }
    }

    private static void report(String group, List<MixinHealth.Requirement> requirements) {
        int ok = 0;
        int pending = 0;
        int notApplicable = 0;
        List<String> failed = new ArrayList<>();
        for (MixinHealth.Requirement requirement : requirements) {
            switch (requirement.state()) {
                case OK -> ok++;
                case PENDING -> pending++;
                case FAILED -> failed.add(requirement.label());
                case NOT_APPLICABLE -> notApplicable++;
            }
        }
        String[] mod = MODS.getOrDefault(group, new String[]{group});
        String version = version(mod);
        StringBuilder line = new StringBuilder("[Planarium Panorama] Mixins ").append(mod[0]);
        if (version != null) line.append(' ').append(version);
        line.append(" : ").append(ok).append('/').append(requirements.size() - notApplicable).append(" verifies");
        if (pending > 0) line.append(", ").append(pending).append(" pas encore charges");
        if (notApplicable > 0) line.append(", ").append(notApplicable).append(" non applicables");
        if (!failed.isEmpty()) line.append(", ").append(failed.size()).append(" inactifs : ").append(String.join(", ", failed));
        if (failed.isEmpty()) {
            LOGGER.info(line.toString());
        } else {
            LOGGER.warn(line.toString());
        }
    }

    /** Version installee du mod, ou null (dossier "core", ou identifiant inconnu). */
    private static String version(String[] mod) {
        for (int i = 1; i < mod.length; i++) {
            var container = ModList.get().getModContainerById(mod[i]);
            if (container.isPresent()) return container.get().getModInfo().getVersion().toString();
        }
        return null;
    }
}

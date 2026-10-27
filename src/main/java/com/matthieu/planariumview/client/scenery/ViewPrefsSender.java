package com.matthieu.planariumview.client.scenery;

import com.matthieu.planariumview.client.scenery.entities.OutsideMobs;
import com.matthieu.planariumview.config.Config;
import com.matthieu.planariumview.network.SoundPrefsPayload;
import com.matthieu.planariumview.network.SubLevelPrefsPayload;
import com.matthieu.planariumview.network.ViewTogglePayload;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

/** Envoi des preferences de vue au serveur et memoire des valeurs envoyees sur la connexion courante. */
final class ViewPrefsSender {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Dernier etat de l'interrupteur general envoye au serveur (null : rien d'envoye sur cette connexion). */
    private static Boolean sentEnabled;
    /** Dernieres options des sons du monde exterieur envoyees au serveur (null : rien d'envoye sur cette connexion). */
    private static SoundPrefsPayload sentSoundPrefs;
    /** Dernieres options de vue envoyees au serveur (mobs du dehors), null : rien d'envoye sur cette connexion. */
    private static com.matthieu.planariumview.network.entities.ViewPrefsPayload sentViewPrefs;
    private static Boolean sentSubLevelViews;
    /** Nombre de Planariums voisins envoye au serveur (null : rien d'envoye sur cette connexion). */
    private static Integer sentNeighbors;

    private ViewPrefsSender() {
    }

    /** Previent le serveur quand l'interrupteur general change (et a la connexion) : OFF, il ne capture plus rien pour nous. */
    static void sendToggle(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        net.minecraft.client.multiplayer.ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null || Minecraft.getInstance().player == null) {
            return;
        }
        boolean subLevels = Config.subLevelViews();
        if (sentSubLevelViews == null || sentSubLevelViews != subLevels) {
            sentSubLevelViews = subLevels;
            if (!subLevels) SubLevelView.clearStructures();
            try {
                if (connection.hasChannel(SubLevelPrefsPayload.TYPE)) {
                    net.neoforged.neoforge.network.PacketDistributor.sendToServer(new SubLevelPrefsPayload(subLevels));
                }
            } catch (Exception e) {
                LOGGER.debug("[Planarium Panorama] Sub-level preference could not be sent", e);
            }
        }
        int neighbors = Config.neighborPlanariums();
        if (sentNeighbors == null || sentNeighbors != neighbors) {
            sentNeighbors = neighbors;
            try {
                if (connection.hasChannel(com.matthieu.planariumview.network.NeighborPrefsPayload.TYPE)) {
                    net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                            new com.matthieu.planariumview.network.NeighborPrefsPayload(neighbors));
                }
            } catch (Exception e) {
                LOGGER.debug("[Planarium Panorama] Neighbor preference could not be sent", e);
            }
        }
        SoundPrefsPayload prefs = new SoundPrefsPayload(Config.worldSounds(), Config.mobSounds(), Config.blockSounds(),
                Config.explosionSounds());
        if (!prefs.equals(sentSoundPrefs)) {
            sentSoundPrefs = prefs;
            try {
                if (connection.hasChannel(SoundPrefsPayload.TYPE)) {
                    net.neoforged.neoforge.network.PacketDistributor.sendToServer(prefs);
                }
            } catch (Exception e) {
                LOGGER.debug("[Planarium Panorama] Options des sons non envoyees au serveur", e);
            }
        }
        com.matthieu.planariumview.network.entities.ViewPrefsPayload view = new com.matthieu.planariumview.network.entities.ViewPrefsPayload(
                Config.outsideMobs(), Config.outsideVehicles(), OutsideMobs.resyncCounter());
        if (!view.equals(sentViewPrefs)) {
            sentViewPrefs = view;
            try {
                if (connection.hasChannel(com.matthieu.planariumview.network.entities.ViewPrefsPayload.TYPE)) {
                    net.neoforged.neoforge.network.PacketDistributor.sendToServer(view);
                }
            } catch (Exception e) {
                LOGGER.debug("[Planarium Panorama] Options de vue non envoyees au serveur", e);
            }
        }
        boolean enabled = Config.enabled();
        if (sentEnabled != null && sentEnabled == enabled) {
            return;
        }
        sentEnabled = enabled;
        try {
            if (connection.hasChannel(ViewTogglePayload.TYPE)) {
                net.neoforged.neoforge.network.PacketDistributor.sendToServer(new ViewTogglePayload(enabled));
            }
        } catch (Exception e) {
            LOGGER.debug("[Planarium Panorama] Interrupteur general non envoye au serveur", e);
        }
    }

    static void reset() {
        sentEnabled = null;
        sentSoundPrefs = null;
        sentViewPrefs = null;
        sentSubLevelViews = null;
        sentNeighbors = null;
    }
}

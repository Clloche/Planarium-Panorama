package com.matthieu.planariumview;

import com.matthieu.planariumview.client.jar.WallColors;
import com.matthieu.planariumview.client.mini.MiniEntities;
import com.matthieu.planariumview.client.mini.MiniLight;
import com.matthieu.planariumview.client.scenery.ClientView;
import com.matthieu.planariumview.client.sky.LightningView;
import com.matthieu.planariumview.client.sky.LunarClient;
import com.matthieu.planariumview.client.sky.SkySync;
import com.matthieu.planariumview.client.sound.OutsideAmbience;
import com.matthieu.planariumview.client.sound.OutsideRecordClient;
import com.matthieu.planariumview.client.sound.OutsideSoundClient;
import com.matthieu.planariumview.config.Config;
import com.matthieu.planariumview.network.AmbiencePayload;
import com.matthieu.planariumview.network.LightningPayload;
import com.matthieu.planariumview.network.LunarPayload;
import com.matthieu.planariumview.network.MiniEntitiesPayload;
import com.matthieu.planariumview.network.MiniLightPayload;
import com.matthieu.planariumview.network.OutsideRecordPayload;
import com.matthieu.planariumview.network.OutsideSoundPayload;
import com.matthieu.planariumview.network.OutsideViewDeltaPayload;
import com.matthieu.planariumview.network.OutsideViewPayload;
import com.matthieu.planariumview.network.SkyPayload;
import com.matthieu.planariumview.network.SoundPrefsPayload;
import com.matthieu.planariumview.network.ViewTogglePayload;
import com.matthieu.planariumview.network.WallPayload;
import com.matthieu.planariumview.network.SubLevelPayload;
import com.matthieu.planariumview.network.SubLevelPrefsPayload;
import com.matthieu.planariumview.client.scenery.SubLevelView;
import com.matthieu.planariumview.server.ServerView;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddPackFindersEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

@Mod(PlanariumView.MODID)
public class PlanariumView {
    public static final String MODID = "planariumview";

    public PlanariumView(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, Config.COMMON_SPEC);
        container.registerConfig(ModConfig.Type.CLIENT, Config.CLIENT_SPEC);
        modBus.addListener(PlanariumView::registerPayloads);
        modBus.addListener(PlanariumView::addPacks);
        NeoForge.EVENT_BUS.register(ServerView.class);
        com.matthieu.planariumview.compat.SubLevelCompat.available();
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ClientView.init(container);
        }
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("4").optional().playToServer(
                SubLevelPrefsPayload.TYPE, SubLevelPrefsPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof net.minecraft.server.level.ServerPlayer player) {
                        com.matthieu.planariumview.server.SubLevelServer.setEnabled(player.getUUID(), payload.enabled());
                    }
                }));
        event.registrar("4").optional().playToClient(
                SubLevelPayload.TYPE, SubLevelPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> SubLevelView.receive(payload)));
        event.registrar("4").playToClient(
                OutsideViewPayload.TYPE,
                OutsideViewPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> ClientView.receive(payload)));
        // Facultatif : le serveur n'envoie ces blocs changes qu'a un client qui les connait (sinon photo complete).
        event.registrar("4").optional().playToClient(
                OutsideViewDeltaPayload.TYPE,
                OutsideViewDeltaPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> ClientView.receiveDelta(payload)));
        event.registrar("4").playToClient(
                SkyPayload.TYPE,
                SkyPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> SkySync.receive(payload)));
        event.registrar("4").playToClient(
                MiniLightPayload.TYPE,
                MiniLightPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> MiniLight.receive(payload)));
        // Facultatif : un serveur sans cette version du mod ne bloque pas la connexion.
        event.registrar("4").optional().playToClient(
                MiniEntitiesPayload.TYPE,
                MiniEntitiesPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> MiniEntities.receive(payload)));
        event.registrar("4").playToClient(
                WallPayload.TYPE,
                WallPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> WallColors.receive(payload)));
        event.registrar("4").playToClient(
                AmbiencePayload.TYPE,
                AmbiencePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> OutsideAmbience.receive(payload)));
        // Facultatif : un serveur sans cette version du mod ne bloque pas la connexion.
        event.registrar("4").optional().playToClient(
                LunarPayload.TYPE,
                LunarPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> LunarClient.receive(payload)));
        // Facultatif : un serveur sans cette version du mod ne bloque pas la connexion.
        event.registrar("4").optional().playToServer(
                ViewTogglePayload.TYPE,
                ViewTogglePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof net.minecraft.server.level.ServerPlayer player) {
                        ServerView.setEnabled(player, payload.enabled());
                    }
                }));
        event.registrar("4").playToClient(
                LightningPayload.TYPE,
                LightningPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> LightningView.receive(payload)));
        // Facultatif : les options des sons du monde exterieur du client (quels sons envoyer, zone active ou en pause).
        event.registrar("4").optional().playToServer(
                SoundPrefsPayload.TYPE,
                SoundPrefsPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof net.minecraft.server.level.ServerPlayer player) {
                        ServerView.setSoundPrefs(player, payload);
                    }
                }));
        // Facultatif : musique des jukebox du dehors.
        event.registrar("4").optional().playToClient(
                OutsideRecordPayload.TYPE,
                OutsideRecordPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> OutsideRecordClient.receive(payload)));
        // Facultatif pour qu'un client ou serveur plus ancien puisse toujours se connecter.
        event.registrar("4").optional().playToClient(
                OutsideSoundPayload.TYPE,
                OutsideSoundPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> OutsideSoundClient.receive(payload)));
    }

    /** Datapack integre : donne la lumiere du ciel (et l'heure normale) a la dimension des bocaux. */
    private static void addPacks(AddPackFindersEvent event) {
        event.addPackFinders(
                ResourceLocation.fromNamespaceAndPath(MODID, "resourcepacks/real_light"),
                PackType.SERVER_DATA,
                Component.translatable("planariumview.pack.real_light"),
                PackSource.BUILT_IN,
                true,
                Pack.Position.TOP);
    }
}

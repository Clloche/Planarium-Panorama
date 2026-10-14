package com.matthieu.planariumview;

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
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ClientView.init(container);
        }
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("4").playToClient(
                OutsideViewPayload.TYPE,
                OutsideViewPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> ClientView.receive(payload)));
        event.registrar("4").playToClient(
                SkyPayload.TYPE,
                SkyPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> SkySync.receive(payload)));
        event.registrar("4").playToClient(
                MiniLightPayload.TYPE,
                MiniLightPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> MiniLight.receive(payload)));
        event.registrar("4").playToClient(
                WallPayload.TYPE,
                WallPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> WallColors.receive(payload)));
        event.registrar("4").playToClient(
                AmbiencePayload.TYPE,
                AmbiencePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> OutsideAmbience.receive(payload)));
        event.registrar("4").playToClient(
                LightningPayload.TYPE,
                LightningPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> LightningView.receive(payload)));
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

package com.matthieu.planariumview;

import com.hollingsworth.arsnouveau.ArsNouveau;
import com.hollingsworth.arsnouveau.common.block.tile.PlanariumTile;
import com.hollingsworth.arsnouveau.common.world.saved_data.JarDimData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.server.level.TicketType;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Cote serveur : quand un joueur est dans la dimension d'un Planarium, on capture les blocs
 * autour du Planarium dans le monde d'origine et on les envoie au client.
 */
public final class ServerView {
    /** Rayon de recherche du Planarium autour de l'endroit d'ou le joueur est entre. */
    private static final int SEARCH_RADIUS = 8;

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private static final Map<UUID, Track> TRACKS = new HashMap<>();
    private static final Set<ResourceKey<Level>> RELIT = new HashSet<>();
    /** Lumiere des bocaux deja envoyee pour les miniatures, et la version du gabarit d'Ars correspondante. */
    private static final Map<ResourceKey<Level>, byte[]> MINI_LIGHT = new HashMap<>();
    private static final Map<ResourceKey<Level>, Long> MINI_VERSION = new HashMap<>();

    private static final class Track {
        final ResourceKey<Level> pocket;
        final GlobalPos source;
        CompoundTag lastSent;
        byte[] lastLight;
        int[] lastRainTops;
        byte[] lastPrecip;
        long nextRefresh;
        /** Rayon de la derniere photo, et rayon (en chunks) du ticket qui garde la zone chargee. */
        int lastRadius = -1;
        int ticketChunks = -1;
        /** Lumiere des parois du bocal et blocs lumineux qui l'eclairent (voir JarWalls). */
        int[] lastWallLight;
        int[] lastWallSources;
        /** Vrai quand la lumiere du ciel du bocal a ete recalculee (les chunks du bocal etaient charges). */
        boolean relit;
        /** Vrai quand les parois du bocal ont ete remises sans lumiere. */
        boolean wallsCleared;
        /** Dernier diagnostic de la lumiere du ciel ecrit dans le log. */
        String lastSkyDiag;

        Track(ResourceKey<Level> pocket, GlobalPos source) {
            this.pocket = pocket;
            this.source = source;
        }
    }

    private ServerView() {
    }

    private static final Set<String> LOGGED_ERRORS = new HashSet<>();

    /** Ecrit une erreur dans le log une seule fois par type (sinon elle se repeterait chaque seconde). */
    private static void logOnce(String what, Exception e) {
        if (LOGGED_ERRORS.add(what)) {
            LOGGER.warn("[Planarium Panorama] Erreur pendant l'envoi ({}) ; la vue continue sans", what, e);
        }
    }

    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            update(player, true);
        }
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            removeTrack(player.server, player.getUUID());
            update(player, true);
            for (Map.Entry<ResourceKey<Level>, byte[]> entry : MINI_LIGHT.entrySet()) {
                PacketDistributor.sendToPlayer(player, new MiniLightPayload(entry.getKey().location(), entry.getValue()));
            }
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            removeTrack(player.server, player.getUUID());
        }
    }

    /** Distance (en blocs) a laquelle on entend le tonnerre d'un eclair du monde d'origine. */
    private static final double THUNDER_RANGE = 512.0;

    /** Un eclair tombe dans un monde ou se trouve le Planarium d'un joueur dans son bocal : on le lui envoie. */
    @SubscribeEvent
    public static void onEntityJoin(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event) {
        if (TRACKS.isEmpty() || !(event.getEntity() instanceof net.minecraft.world.entity.LightningBolt bolt)
                || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        try {
            for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
                Track track = TRACKS.get(player.getUUID());
                if (track == null || !track.source.dimension().equals(level.dimension())
                        || !player.serverLevel().dimension().equals(track.pocket)) {
                    continue;
                }
                BlockPos planarium = track.source.pos();
                double dx = bolt.getX() - planarium.getX();
                double dy = bolt.getY() - planarium.getY();
                double dz = bolt.getZ() - planarium.getZ();
                if (dx * dx + dz * dz > THUNDER_RANGE * THUNDER_RANGE) {
                    continue;
                }
                PacketDistributor.sendToPlayer(player,
                        new LightningPayload(track.pocket.location(), dx, dy, dz, bolt.seed));
            }
        } catch (Exception e) {
            logOnce("eclair", e);
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % 20 != 0) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            update(player, false);
        }
        try {
            updateMiniatures(server);
        } catch (Exception e) {
            LOGGER.error("[Planarium View] Erreur lors de la capture de la lumiere des miniatures", e);
        }
    }

    private static boolean isPocket(ServerLevel level) {
        return level.dimensionTypeRegistration().is(ArsNouveau.DIMENSION_TYPE_KEY);
    }

    private static void update(ServerPlayer player, boolean force) {
        try {
            doUpdate(player, force);
        } catch (Exception e) {
            LOGGER.error("[Planarium View] Erreur lors de la capture des alentours", e);
        }
    }

    private static void doUpdate(ServerPlayer player, boolean force) {
        ServerLevel level = player.serverLevel();
        UUID id = player.getUUID();
        Track track = TRACKS.get(id);

        if (!isPocket(level)) {
            if (track != null) {
                removeTrack(player.server, id);
                PacketDistributor.sendToPlayer(player, OutsideViewPayload.clear(track.pocket.location()));
            }
            return;
        }

        if (track == null || !track.pocket.equals(level.dimension())) {
            GlobalPos source = findPlanarium(player, level);
            if (source == null) {
                if (track != null) {
                    removeTrack(player.server, id);
                    PacketDistributor.sendToPlayer(player, OutsideViewPayload.clear(track.pocket.location()));
                }
                return;
            }
            if (track != null) {
                removeTrack(player.server, id);
            }
            track = new Track(level.dimension(), source);
            TRACKS.put(id, track);
            force = true;
        }
        // On attend que les chunks du bocal soient charges : sinon le recalcul serait saute.
        if (!track.relit) {
            track.relit = relightPocket(level);
        }

        // Chaque envoi est protege : une erreur (souvent un autre mod) ne doit pas bloquer la capture du decor.
        try {
            sendSky(player, track);
        } catch (Exception e) {
            logOnce("ciel", e);
        }
        // Evenement lunaire d'Enhanced Celestials du monde d'origine (envoye seulement s'il change).
        ServerLevel lunarSource = player.server.getLevel(track.source.dimension());
        if (lunarSource != null) {
            LunarServer.send(player, track.pocket.location(), lunarSource);
        }
        try {
            sendAmbience(player, track);
        } catch (Exception e) {
            logOnce("ambiance", e);
        }
        try {
            // Les parois seulement apres le recalcul de la lumiere du ciel, qui doit partir de donnees a jour.
            if (track.relit) {
                updateWalls(player, track);
                if (player.server.getTickCount() % 20 == 0) {
                    logSkyDiag(level, track);
                }
            }
        } catch (Exception e) {
            logOnce("parois", e);
        }

        int radius = Config.RADIUS.get();
        // Le rayon a change dans la config : nouvelle photo tout de suite, sans ressortir du bocal.
        if (radius != track.lastRadius) {
            force = true;
        }
        keepLoaded(player.server, track, radius);

        long now = player.server.getTickCount();
        int refresh = Config.REFRESH_SECONDS.get() * 20;
        if (!force && (refresh <= 0 || now < track.nextRefresh)) {
            return;
        }
        track.nextRefresh = now + Math.max(refresh, 20);

        Snapshot snap = capture(player.server, track, radius, force);
        if (snap == null || (snap.tag().equals(track.lastSent) && Arrays.equals(snap.light(), track.lastLight)
                && Arrays.equals(snap.rainTops(), track.lastRainTops) && Arrays.equals(snap.precip(), track.lastPrecip))) {
            return;
        }
        track.lastSent = snap.tag();
        track.lastLight = snap.light();
        track.lastRainTops = snap.rainTops();
        track.lastPrecip = snap.precip();
        track.lastRadius = radius;
        PacketDistributor.sendToPlayer(player,
                new OutsideViewPayload(true, track.pocket.location(), radius, snap.tag(), snap.light(),
                        snap.origin(), snap.biomePalette(), snap.biomes(), snap.rainTops(), snap.precip()));
    }

    /** Envoie l'heure, la meteo et les couleurs du ciel du monde d'origine (si ce monde a un ciel). */
    private static void sendSky(ServerPlayer player, Track track) {
        ServerLevel source = player.server.getLevel(track.source.dimension());
        if (source == null) {
            return;
        }
        // Sans ciel (le Nether...) : pas de ciel dans le bocal, mais la lumiere du dehors s'applique quand meme
        // (interieur sombre sans torche a cote du Planarium, comme le decor).
        boolean hasSky = source.dimensionType().hasSkyLight() && !source.dimensionType().hasCeiling();
        Biome biome = source.getBiome(track.source.pos()).value();
        PacketDistributor.sendToPlayer(player, new SkyPayload(
                track.pocket.location(),
                source.getDayTime(),
                source.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT),
                source.getRainLevel(1.0F),
                source.getThunderLevel(1.0F),
                // Lues directement dans les effets du biome : en solo, Dynamic Surroundings modifie
                // Biome.getFogColor pour le client, et son code plante s'il est appele cote serveur.
                biome.getModifiedSpecialEffects().getSkyColor(),
                biome.getModifiedSpecialEffects().getFogColor(),
                SeasonCompat.seasonTicks(source),
                hasSky ? outsideLight(source, track.source.pos(), LightLayer.SKY) : 0,
                outsideLight(source, track.source.pos(), LightLayer.BLOCK),
                // Le ciel de la dimension d'origine (celui de l'Aether, par exemple).
                source.dimensionType().effectsLocation(),
                // Le type de dimension d'origine : sa facon de compter les jours (plus longs dans l'Aether).
                source.dimensionTypeRegistration().unwrapKey().map(net.minecraft.resources.ResourceKey::location)
                        .orElse(ResourceLocation.withDefaultNamespace("overworld")),
                hasSky,
                planariumName(source, track.source.pos())));
    }

    /** Lumiere qui arrive au Planarium : la plus forte parmi sa case et ses 6 voisines (le bocal est en verre). */
    /** Nom donne au Planarium (renomme dans une enclume), ou vide. Sert au titre de Traveler's Titles. */
    private static String planariumName(ServerLevel source, BlockPos pos) {
        if (!source.isLoaded(pos) || !(source.getBlockEntity(pos) instanceof PlanariumTile tile)) {
            return "";
        }
        net.minecraft.network.chat.Component name = tile.getCustomName();
        if (name == null) {
            return "";
        }
        String text = name.getString();
        return text.length() > 64 ? text.substring(0, 64) : text;
    }

    private static int outsideLight(ServerLevel level, BlockPos pos, LightLayer layer) {
        int best = level.getBrightness(layer, pos);
        for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.values()) {
            best = Math.max(best, level.getBrightness(layer, pos.relative(direction)));
        }
        return best;
    }

    /**
     * Retrouve le Planarium par lequel le joueur est entre, a partir de la position
     * memorisee par Ars Nouveau (JarDimData.enteredFrom).
     */
    private static GlobalPos findPlanarium(ServerPlayer player, ServerLevel pocket) {
        JarDimData.RotPos from = JarDimData.from(pocket).getEnteredFrom(player.getUUID());
        if (from == null) {
            return null;
        }
        ServerLevel sourceLevel = player.server.getLevel(from.pos().dimension());
        if (sourceLevel == null) {
            return null;
        }
        BlockPos center = from.pos().pos();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-SEARCH_RADIUS, -SEARCH_RADIUS, -SEARCH_RADIUS),
                center.offset(SEARCH_RADIUS, SEARCH_RADIUS, SEARCH_RADIUS))) {
            BlockEntity be = sourceLevel.getBlockEntity(pos);
            if (be instanceof PlanariumTile tile && pocket.dimension().equals(tile.key)) {
                double d = pos.distSqr(center);
                if (d < bestDist) {
                    bestDist = d;
                    best = pos.immutable();
                }
            }
        }
        return best == null ? null : GlobalPos.of(sourceLevel.dimension(), best);
    }

    private record Snapshot(CompoundTag tag, byte[] light, BlockPos origin, List<ResourceLocation> biomePalette,
                            byte[] biomes, int[] rainTops, byte[] precip) {
    }

    private static Snapshot capture(MinecraftServer server, Track track, int radius, boolean allowLoad) {
        ServerLevel sourceLevel = server.getLevel(track.source.dimension());
        if (sourceLevel == null) {
            return null;
        }
        BlockPos planarium = track.source.pos();
        BlockPos min = planarium.offset(-radius, -radius, -radius);
        BlockPos max = planarium.offset(radius, radius, radius);
        // Pour les rafraichissements, on ne force pas le chargement des chunks d'origine.
        if (!allowLoad && !sourceLevel.hasChunksAt(min, max)) {
            return null;
        }
        // Le Planarium doit toujours etre la et pointer vers cette dimension.
        if (!(sourceLevel.getBlockEntity(planarium) instanceof PlanariumTile tile) || !track.pocket.equals(tile.key)) {
            return null;
        }
        StructureTemplate template = new StructureTemplate();
        int size = radius * 2 + 1;
        template.fillFromWorld(sourceLevel, min, new Vec3i(size, size, size), false, Blocks.AIR);
        // Biomes en 3D (comme Minecraft : un par cube de 4 blocs), pour les couleurs d'herbe, de feuilles et d'eau.
        BiomeGrid grid = BiomeGrid.of(min, size);
        List<ResourceLocation> palette = new ArrayList<>();
        Map<ResourceLocation, Integer> indexOf = new HashMap<>();
        byte[] biomes = new byte[grid.volume()];
        for (int qx = grid.minQx(); qx < grid.minQx() + grid.sizeX(); qx++) {
            for (int qy = grid.minQy(); qy < grid.minQy() + grid.sizeY(); qy++) {
                for (int qz = grid.minQz(); qz < grid.minQz() + grid.sizeZ(); qz++) {
                    ResourceLocation id = sourceLevel.getNoiseBiome(qx, qy, qz).unwrapKey()
                            .map(net.minecraft.resources.ResourceKey::location)
                            .orElse(ResourceLocation.withDefaultNamespace("plains"));
                    Integer index = indexOf.get(id);
                    if (index == null) {
                        if (palette.size() >= 255) {
                            index = 0;
                        } else {
                            index = palette.size();
                            palette.add(id);
                            indexOf.put(id, index);
                        }
                    }
                    biomes[grid.index(qx, qy, qz)] = (byte) (int) index;
                }
            }
        }
        // Meteo : pour chaque colonne, ou la pluie s'arrete (premier bloc qui la bloque) et si c'est de la pluie ou de la neige.
        int[] rainTops = new int[size * size];
        byte[] precip = new byte[size * size];
        boolean hasWeather = sourceLevel.dimensionType().hasSkyLight() && !sourceLevel.dimensionType().hasCeiling();
        BlockPos.MutableBlockPos column = new BlockPos.MutableBlockPos();
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                int wx = min.getX() + x;
                int wz = min.getZ() + z;
                int top = sourceLevel.getHeight(Heightmap.Types.MOTION_BLOCKING, wx, wz);
                rainTops[x * size + z] = top;
                if (hasWeather) {
                    column.set(wx, top, wz);
                    Biome.Precipitation type = SeasonCompat.precipitation(sourceLevel, sourceLevel.getBiome(column), column);
                    precip[x * size + z] = (byte) (type == Biome.Precipitation.RAIN ? 1 : type == Biome.Precipitation.SNOW ? 2 : 0);
                }
            }
        }
        return new Snapshot(template.save(new CompoundTag()), captureLight(sourceLevel, min, size), min, palette, biomes,
                rainTops, precip);
    }

    /**
     * Lumiere du ciel et des blocs pour chaque case du cube, avec une marge d'un bloc
     * (les faces au bord prennent la lumiere de la case voisine). Un octet par case : ciel << 4 | blocs.
     */
    private static byte[] captureLight(ServerLevel level, BlockPos min, int size) {
        return captureLight(level, min.offset(-1, -1, -1), size + 2, size + 2, size + 2);
    }

    /** Lit la lumiere d'une boite qui commence a {@code start} (marge deja incluse). */
    static byte[] captureLight(ServerLevel level, BlockPos start, int sizeX, int sizeY, int sizeZ) {
        byte[] light = new byte[sizeX * sizeY * sizeZ];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int i = 0;
        for (int x = 0; x < sizeX; x++) {
            for (int y = 0; y < sizeY; y++) {
                for (int z = 0; z < sizeZ; z++) {
                    pos.set(start.getX() + x, start.getY() + y, start.getZ() + z);
                    int sky = level.getBrightness(LightLayer.SKY, pos);
                    int block = level.getBrightness(LightLayer.BLOCK, pos);
                    light[i++] = (byte) ((sky << 4) | block);
                }
            }
        }
        return light;
    }

    /**
     * Les bocaux crees avant l'ajout de la lumiere du ciel n'ont pas de donnees de lumiere du ciel.
     * On recalcule l'eclairage des chunks du bocal une fois par session et on le renvoie aux joueurs.
     */
    /**
     * Diagnostic : lumiere du ciel au centre du bocal, du plafond au plancher, et comment Minecraft voit le plafond.
     * Ecrit dans le log seulement quand ca change.
     */
    private static void logSkyDiag(ServerLevel pocket, Track track) {
        StringBuilder line = new StringBuilder("ciel au centre (y 40, 33, 32, 31, 24, 16, 8, 1) :");
        for (int y : new int[]{40, 33, 32, 31, 24, 16, 8, 1}) {
            line.append(' ').append(pocket.getBrightness(net.minecraft.world.level.LightLayer.SKY, new BlockPos(16, y, 16)));
        }
        BlockPos ceiling = new BlockPos(16, JarWalls.MAX_Y, 16);
        net.minecraft.world.level.block.state.BlockState state = pocket.getBlockState(ceiling);
        line.append(" ; plafond ").append(state)
                .append(" bloque ").append(state.getLightBlock(pocket, ceiling))
                .append(" laisse passer ").append(state.propagatesSkylightDown(pocket, ceiling))
                .append(" ; source du ciel la plus basse ")
                .append(pocket.getChunk(ceiling).getSkyLightSources().getLowestSourceY(16 & 15, 16 & 15))
                .append(" ; ciel du monde ").append(pocket.getSkyDarken());
        String text = line.toString();
        if (!text.equals(track.lastSkyDiag)) {
            track.lastSkyDiag = text;
            LOGGER.info("[Planarium Panorama] Diagnostic du bocal : {}", text);
        }
    }

    /** Retourne vrai si c'est fait (ou inutile), faux si les chunks du bocal ne sont pas encore tous charges. */
    private static boolean relightPocket(ServerLevel pocket) {
        if (!pocket.dimensionType().hasSkyLight() || RELIT.contains(pocket.dimension())) {
            return true;
        }
        LevelChunk[] chunks = new LevelChunk[16];
        int n = 0;
        for (int cx = -1; cx <= 2; cx++) {
            for (int cz = -1; cz <= 2; cz++) {
                LevelChunk chunk = pocket.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) {
                    return false;
                }
                chunks[n++] = chunk;
            }
        }
        RELIT.add(pocket.dimension());
        ThreadedLevelLightEngine engine = pocket.getChunkSource().getLightEngine();
        for (LevelChunk chunk : chunks) {
            ChunkPos chunkPos = chunk.getPos();
            // Recalcule d'ou part la lumiere du ciel (les parois du bocal la laissent passer).
            chunk.initializeLightSources();
            engine.lightChunk(chunk, false).thenRunAsync(() -> {
                ClientboundLightUpdatePacket packet = new ClientboundLightUpdatePacket(chunkPos, engine, null, null);
                for (ServerPlayer p : pocket.players()) {
                    p.connection.send(packet);
                }
                // La miniature sera recapturee avec l'eclairage recalcule.
                MINI_VERSION.remove(pocket.dimension());
            }, pocket.getServer());
        }
        LOGGER.info("[Planarium Panorama] Lumiere du ciel du bocal recalculee ({})", pocket.dimension().location());
        return true;
    }

    /**
     * Quand Ars met a jour le gabarit d'un bocal (blocs changes), on lit aussi la lumiere
     * de l'interieur du bocal et on l'envoie aux clients pour la miniature.
     */
    private static void updateMiniatures(MinecraftServer server) {
        for (Map.Entry<ResourceKey<Level>, PlanariumTile.DimManager.Entry> entry : PlanariumTile.dimManager.entries.entrySet()) {
            ResourceKey<Level> key = entry.getKey();
            PlanariumTile.DimManager.Entry dimEntry = entry.getValue();
            if (dimEntry == null || dimEntry.dirty()) {
                continue;
            }
            Long version = MINI_VERSION.get(key);
            if (version != null && version == dimEntry.lastUpdated()) {
                continue;
            }
            ServerLevel pocket = server.getLevel(key);
            if (pocket == null) {
                continue;
            }
            MINI_VERSION.put(key, dimEntry.lastUpdated());
            relightPocket(pocket);
            // Charge les chunks du bocal (comme Ars le fait pour son gabarit) avant de lire la lumiere.
            for (int cx = -1; cx <= 2; cx++) {
                for (int cz = -1; cz <= 2; cz++) {
                    pocket.getChunk(cx, cz);
                }
            }
            byte[] light = captureLight(pocket, new BlockPos(-1, 0, -1),
                    MiniLightPayload.SIZE_X, MiniLightPayload.SIZE_Y, MiniLightPayload.SIZE_Z);
            if (Arrays.equals(light, MINI_LIGHT.get(key))) {
                continue;
            }
            MINI_LIGHT.put(key, light);
            PacketDistributor.sendToAllPlayers(new MiniLightPayload(key.location(), light));
        }
    }

    /** Ticket de chargement : garde la zone autour du Planarium chargee tant qu'un joueur est dans le bocal. */
    private static final TicketType<ChunkPos> VIEW_TICKET =
            TicketType.create("planariumview", java.util.Comparator.comparingLong(ChunkPos::toLong));

    /**
     * Sans joueur a proximite, les chunks autour du Planarium se dechargent et les rafraichissements
     * ne peuvent plus lire les blocs. On les garde charges (sans les faire "tourner") pendant la visite.
     */
    private static void keepLoaded(MinecraftServer server, Track track, int radius) {
        int chunks = Math.floorDiv(radius + 15, 16) + 1;
        if (chunks == track.ticketChunks) {
            return;
        }
        ServerLevel source = server.getLevel(track.source.dimension());
        if (source == null) {
            return;
        }
        ChunkPos center = new ChunkPos(track.source.pos());
        if (track.ticketChunks >= 0) {
            source.getChunkSource().removeRegionTicket(VIEW_TICKET, center, track.ticketChunks, center);
        }
        source.getChunkSource().addRegionTicket(VIEW_TICKET, center, chunks, center);
        track.ticketChunks = chunks;
    }

    private static void removeTrack(MinecraftServer server, UUID id) {
        LunarServer.forget(id);
        Track track = TRACKS.remove(id);
        if (track == null || track.ticketChunks < 0) {
            return;
        }
        ServerLevel source = server.getLevel(track.source.dimension());
        if (source != null) {
            ChunkPos center = new ChunkPos(track.source.pos());
            source.getChunkSource().removeRegionTicket(VIEW_TICKET, center, track.ticketChunks, center);
        }
        track.ticketChunks = -1;
    }

    /**
     * Les parois du bocal emettent la lumiere des blocs qui entourent le Planarium, face par face.
     * On ne touche aux parois que quand cette lumiere change.
     */
    private static void updateWalls(ServerPlayer player, Track track) {
        ServerLevel source = player.server.getLevel(track.source.dimension());
        ServerLevel pocket = player.serverLevel();
        BlockPos pos = track.source.pos();
        if (source == null || !source.hasChunkAt(pos)) {
            return;
        }
        // Les parois n'emettent plus de lumiere : la lumiere et la couleur de l'interieur sont calculees chez le client
        // pour tout le bocal (JarLight, JarColor). On remet a 0 celles des bocaux d'anciennes versions, une fois.
        if (!track.wallsCleared) {
            int[] result = JarWalls.apply(pocket, new int[JarWalls.FACES]);
            if (result[0] == 0) {
                // Les chunks du bocal ne sont pas encore charges (on vient d'entrer) : on reessaie a la prochaine seconde.
                return;
            }
            track.wallsCleared = true;
            if (result[2] > 0) {
                LOGGER.info("[Planarium Panorama] Parois du bocal remises sans lumiere ({} modifiees)", result[2]);
            }
        }
        int[] light = JarWalls.measure(source, pos);
        if (!Arrays.equals(light, track.lastWallLight)) {
            LOGGER.info("[Planarium Panorama] Lumiere autour du Planarium : {}", Arrays.toString(light));
            track.lastWallLight = light;
        }
        // Recalcule aussi les sources (une torche remplacee par une torche des ames a la meme lumiere change la couleur).
        track.lastWallSources = JarWalls.findSources(source, pos);
        // Envoye chaque seconde, meme sans changement : le message envoye a l'entree peut se perdre pendant que le client
        // change de dimension. Le client ne recalcule rien si les valeurs n'ont pas change.
        PacketDistributor.sendToPlayer(player, new WallPayload(track.pocket.location(), light, track.lastWallSources));
    }

    /**
     * Ambiance a l'endroit du Planarium, calculee comme Dynamic Surroundings le ferait
     * pour un joueur debout a la place du Planarium : son biome, s'il est abrite, s'il est sous terre.
     */
    private static void sendAmbience(ServerPlayer player, Track track) {
        ServerLevel source = player.server.getLevel(track.source.dimension());
        if (source == null) {
            return;
        }
        BlockPos pos = track.source.pos();
        if (!source.hasChunkAt(pos)) {
            return;
        }
        ResourceLocation biome = source.getBiome(pos).unwrapKey()
                .map(net.minecraft.resources.ResourceKey::location)
                .orElse(ResourceLocation.withDefaultNamespace("plains"));
        boolean underground = pos.getY() < source.getSeaLevel() - 8;
        PacketDistributor.sendToPlayer(player,
                new AmbiencePayload(track.pocket.location(), biome, isSheltered(source, pos), underground,
                        source.dimension().location(), pos.getY(), source.getSeaLevel(), source.getHeight()));
    }

    /**
     * Meme methode que le "CeilingScanner" de Dynamic Surroundings : on regarde 7 x 7 colonnes autour
     * de la position ; une colonne est couverte s'il y a, au-dessus de la tete, un bloc qui bloque le
     * mouvement (sauf feuilles, cloture, portillon et muret). Les colonnes proches comptent plus.
     */
    private static boolean isSheltered(ServerLevel level, BlockPos pos) {
        final int range = 3;
        float total = 0.0F;
        float score = 0.0F;
        int headY = Math.max(pos.getY() + 1, level.getMinBuildHeight());
        BlockPos.MutableBlockPos working = new BlockPos.MutableBlockPos();
        for (int dx = -range; dx <= range; dx++) {
            for (int dz = -range; dz <= range; dz++) {
                float side = Math.min(range - Math.abs(dx) + 1, range - Math.abs(dz) + 1);
                float points = side * side;
                total += points;
                int x = pos.getX() + dx;
                int z = pos.getZ() + dz;
                int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z);
                boolean covered = false;
                for (int y = top; y > headY; y--) {
                    working.set(x, y, z);
                    net.minecraft.world.level.block.state.BlockState state = level.getBlockState(working);
                    if (state.blocksMotion()
                            && !state.is(net.minecraft.tags.BlockTags.LEAVES)
                            && !state.is(net.minecraft.tags.BlockTags.FENCE_GATES)
                            && !state.is(net.minecraft.tags.BlockTags.FENCES)
                            && !state.is(net.minecraft.tags.BlockTags.WALLS)) {
                        covered = true;
                        break;
                    }
                }
                if (!covered) {
                    score += points;
                }
            }
        }
        float coverage = 1.0F - score / total;
        return coverage > 1.0F - 65.0F / 176.0F;
    }
}

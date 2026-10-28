package com.matthieu.planariumview.server;

import com.matthieu.planariumview.client.jar.JarWalls;
import com.matthieu.planariumview.network.WallPayload;
import com.matthieu.planariumview.server.ServerView.Track;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Eclairage des bocaux, nettoyage des anciennes sources et synchronisation des parois. */
final class PocketLighting {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(ServerView.class);

    private static final Set<ResourceKey<Level>> RELIT = new HashSet<>();

    private PocketLighting() {
    }

    /** Lumiere qui arrive au Planarium : la plus forte parmi sa case et ses 6 voisines (le bocal est en verre). */
    static int outsideLight(ServerLevel level, BlockPos pos, LightLayer layer) {
        int best = level.getBrightness(layer, pos);
        for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.values()) {
            best = Math.max(best, level.getBrightness(layer, pos.relative(direction)));
        }
        return best;
    }

    /**
     * Les bocaux crees avant l'ajout de la lumiere du ciel n'ont pas de donnees de lumiere du ciel.
     * On recalcule l'eclairage des chunks du bocal une fois par session et on le renvoie aux joueurs.
     */
    /**
     * Diagnostic : lumiere du ciel au centre du bocal, du plafond au plancher, et comment Minecraft voit le plafond.
     * Ecrit dans le log seulement quand ca change.
     */
    static void logSkyDiag(ServerLevel pocket, Track track) {
        if (!LOGGER.isDebugEnabled()) return;
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
            LOGGER.debug("[Planarium Panorama] Diagnostic du bocal : {}", text);
        }
    }

    private static final java.util.Set<net.minecraft.resources.ResourceKey<Level>> FLOOR_CLEANED = new java.util.HashSet<>();
    private static final net.minecraft.resources.ResourceLocation AMBERSOL_LIGHT =
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("alexscaves", "ambersol_light");

    /**
     * Alex's Caves : avant le correctif (voir AcAmbersolLightMixin), un ambersol posait des lumieres invisibles a travers
     * le plancher du bocal, qui restaient apres l'avoir casse et eclairaient le plancher. On les retire une fois.
     */
    static void removeLightsBelowFloor(ServerLevel pocket) {
        if (FLOOR_CLEANED.contains(pocket.dimension())
                || !net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(AMBERSOL_LIGHT)) {
            return;
        }
        net.minecraft.world.level.block.Block light = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(AMBERSOL_LIGHT);
        java.util.List<BlockPos> found = new java.util.ArrayList<>();
        for (int cx = -1; cx <= 2; cx++) {
            for (int cz = -1; cz <= 2; cz++) {
                LevelChunk chunk = pocket.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) {
                    return;
                }
                for (int y = pocket.getMinBuildHeight(); y < JarWalls.MIN_Y; y++) {
                    if (chunk.getSection(chunk.getSectionIndex(y)).hasOnlyAir()) {
                        continue;
                    }
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            BlockPos pos = new BlockPos(cx * 16 + x, y, cz * 16 + z);
                            if (chunk.getBlockState(pos).is(light)) {
                                found.add(pos);
                            }
                        }
                    }
                }
            }
        }
        FLOOR_CLEANED.add(pocket.dimension());
        for (BlockPos pos : found) {
            pocket.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
        }
        if (!found.isEmpty()) {
            LOGGER.debug("[Planarium Panorama] {} lumieres d'ambersol retirees sous le bocal ({})", found.size(),
                    pocket.dimension().location());
        }
    }

    /** Retourne vrai si c'est fait (ou inutile), faux si les chunks du bocal ne sont pas encore tous charges. */
    static boolean relightPocket(ServerLevel pocket) {
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
            recheckBlockLight(engine, chunkPos);
            // Recalcule d'ou part la lumiere du ciel (les parois du bocal la laissent passer).
            chunk.initializeLightSources();
            engine.lightChunk(chunk, false).thenRunAsync(() -> {
                ClientboundLightUpdatePacket packet = new ClientboundLightUpdatePacket(chunkPos, engine, null, null);
                for (ServerPlayer p : pocket.players()) {
                    p.connection.send(packet);
                }
                // La miniature sera recapturee avec l'eclairage recalcule.
                MiniatureSync.invalidateLight(pocket.dimension());
            }, pocket.getServer());
        }
        LOGGER.debug("[Planarium Panorama] Lumiere du ciel du bocal recalculee ({})", pocket.dimension().location());
        return true;
    }

    /**
     * lightChunk propage les sources presentes, mais n'efface pas une ancienne source disparue de la sauvegarde.
     * On fait reverifier les maxima de lumiere des blocs par le moteur vanilla : une vraie source reste,
     * un maximum sans source lance la diminution vers ses voisins. Les autres cases suivent cette propagation.
     */
    private static void recheckBlockLight(ThreadedLevelLightEngine engine, ChunkPos chunk) {
        LayerLightEventListener light = engine.getLayerListener(LightLayer.BLOCK);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos neighbor = new BlockPos.MutableBlockPos();
        Direction[] directions = Direction.values();
        for (int sy = engine.getMinLightSection(); sy < engine.getMaxLightSection(); sy++) {
            DataLayer values = light.getDataLayerData(SectionPos.of(chunk, sy));
            if (values == null || values.isEmpty()) continue;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int value = values.get(x, y, z);
                        if (value == 0) continue;
                        pos.set(chunk.getMinBlockX() + x, (sy << 4) + y, chunk.getMinBlockZ() + z);
                        boolean maximum = true;
                        for (Direction direction : directions) {
                            neighbor.setWithOffset(pos, direction);
                            if (light.getLightValue(neighbor) > value) {
                                maximum = false;
                                break;
                            }
                        }
                        if (maximum) engine.checkBlock(pos);
                    }
                }
            }
        }
    }

    /**
     * Les parois du bocal emettent la lumiere des blocs qui entourent le Planarium, face par face.
     * On ne touche aux parois que quand cette lumiere change.
     */
    static void updateWalls(ServerPlayer player, Track track) {
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
                LOGGER.debug("[Planarium Panorama] Parois du bocal remises sans lumiere ({} modifiees)", result[2]);
            }
        }
        int[] light = JarWalls.measure(source, pos);
        if (!pos.equals(track.center)) {
            BlockPos[] samples = JarWalls.samplePositions(pos);
            org.joml.Matrix4d transform = new org.joml.Matrix4d(track.anchor)
                    .translate(-pos.getX(), -pos.getY(), -pos.getZ());
            for (int i = 0; i < samples.length; i++) {
                org.joml.Vector3d point = transform.transformPosition(new org.joml.Vector3d(
                        samples[i].getX() + 0.5, samples[i].getY() + 0.5, samples[i].getZ() + 0.5));
                light[i] = Math.max(light[i], source.getBrightness(LightLayer.BLOCK, BlockPos.containing(point.x, point.y, point.z)));
            }
        }
        if (!Arrays.equals(light, track.lastWallLight)) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("[Planarium Panorama] Lumiere autour du Planarium : {}", Arrays.toString(light));
            }
            track.lastWallLight = light;
        }
        // Recalcule aussi les sources (une torche remplacee par une torche des ames a la meme lumiere change la couleur).
        track.lastWallSources = JarWalls.findSources(source, pos);
        // Envoye chaque seconde, meme sans changement : le message envoye a l'entree peut se perdre pendant que le client
        // change de dimension. Le client ne recalcule rien si les valeurs n'ont pas change.
        PacketDistributor.sendToPlayer(player, new WallPayload(track.pocket.location(), light, track.lastWallSources));
    }

}

package com.matthieu.planariumview.server;

import com.hollingsworth.arsnouveau.common.block.tile.PlanariumTile;
import com.matthieu.planariumview.client.scenery.BiomeGrid;
import com.matthieu.planariumview.client.scenery.entities.GiantEntities;
import com.matthieu.planariumview.compat.serene_seasons.SeasonCompat;
import com.matthieu.planariumview.server.ServerView.Track;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Capture du decor, de sa lumiere et des donnees de biome et de meteo. */
final class SceneryCapture {
    private SceneryCapture() {
    }

    record Snapshot(CompoundTag tag, byte[] light, BlockPos origin, List<ResourceLocation> biomePalette,
                    byte[] biomes, int[] rainTops, byte[] precip) {
    }

    /** Explique en une ligne quelles parties du snapshot ont change depuis le dernier envoi. */
    static String describeSnapshotChanges(Track track, Snapshot current) {
        if (track.lastSent == null) {
            return "premiere capture";
        }
        net.minecraft.nbt.ListTag oldBlocks = track.lastSent.getList("blocks", net.minecraft.nbt.Tag.TAG_COMPOUND);
        net.minecraft.nbt.ListTag newBlocks = current.tag().getList("blocks", net.minecraft.nbt.Tag.TAG_COMPOUND);
        net.minecraft.nbt.ListTag oldPalette = track.lastSent.getList("palette", net.minecraft.nbt.Tag.TAG_COMPOUND);
        net.minecraft.nbt.ListTag newPalette = current.tag().getList("palette", net.minecraft.nbt.Tag.TAG_COMPOUND);
        boolean blockStatesChanged = false;
        boolean blockEntityDataChanged = false;
        String blockExample = null;
        String blockStateExample = null;
        String blockEntityExample = null;
        int firstDifference = 0;
        int sharedSize = Math.min(oldBlocks.size(), newBlocks.size());
        while (firstDifference < sharedSize
                && oldBlocks.getCompound(firstDifference).equals(newBlocks.getCompound(firstDifference))) {
            firstDifference++;
        }
        if (firstDifference < sharedSize) {
            var oldBlock = oldBlocks.getCompound(firstDifference);
            var newBlock = newBlocks.getCompound(firstDifference);
            int[] oldPos = oldBlock.getIntArray("pos");
            int[] newPos = newBlock.getIntArray("pos");
            boolean samePos = Arrays.equals(oldPos, newPos);
            int oldState = oldBlock.getInt("state");
            int newState = newBlock.getInt("state");
            boolean sameState = oldState == newState && oldPalette.equals(newPalette)
                    || paletteState(oldPalette, oldState).equals(paletteState(newPalette, newState));
            if (!samePos || !sameState) {
                blockStatesChanged = true;
                blockExample = worldPosition(newBlock, current.origin());
                blockStateExample = paletteState(oldPalette, oldState) + " -> " + paletteState(newPalette, newState);
            }
            if (samePos && !java.util.Objects.equals(oldBlock.get("nbt"), newBlock.get("nbt"))) {
                blockEntityDataChanged = true;
                blockEntityExample = worldPosition(newBlock, current.origin());
            }
        } else if (oldBlocks.size() != newBlocks.size()) {
            blockStatesChanged = true;
            var block = firstDifference < newBlocks.size()
                    ? newBlocks.getCompound(firstDifference) : oldBlocks.getCompound(firstDifference);
            blockExample = worldPosition(block, current.origin());
            blockStateExample = firstDifference < newBlocks.size()
                    ? "<absent> -> " + paletteState(newPalette, block.getInt("state"))
                    : paletteState(oldPalette, block.getInt("state")) + " -> <absent>";
        }
        boolean entitiesChanged = !track.lastSent.getList("entities", net.minecraft.nbt.Tag.TAG_COMPOUND)
                .equals(current.tag().getList("entities", net.minecraft.nbt.Tag.TAG_COMPOUND));
        boolean lightChanged = !Arrays.equals(current.light(), track.lastLight);
        boolean biomesChanged = !current.biomePalette().equals(track.lastBiomePalette)
                || !Arrays.equals(current.biomes(), track.lastBiomes);
        boolean weatherChanged = !Arrays.equals(current.rainTops(), track.lastRainTops)
                || !Arrays.equals(current.precip(), track.lastPrecip);
        boolean otherTagChanged = !blockStatesChanged && !blockEntityDataChanged && !entitiesChanged
                && !current.tag().equals(track.lastSent);
        return "blocs=" + blockStatesChanged + positionSuffix(blockExample) + stateSuffix(blockStateExample)
                + ", donnees de blocs speciaux=" + blockEntityDataChanged + positionSuffix(blockEntityExample)
                + ", entites=" + entitiesChanged + ", lumiere=" + lightChanged
                + ", biomes=" + biomesChanged + ", meteo=" + weatherChanged + ", autres donnees=" + otherTagChanged;
    }

    private static String paletteState(net.minecraft.nbt.ListTag palette, int index) {
        return index >= 0 && index < palette.size() ? palette.getCompound(index).toString() : "<index invalide:" + index + ">";
    }

    private static String worldPosition(net.minecraft.nbt.CompoundTag block, BlockPos origin) {
        int[] pos = block.getIntArray("pos");
        return pos.length == 3 ? "[" + (origin.getX() + pos[0]) + "," + (origin.getY() + pos[1]) + ","
                + (origin.getZ() + pos[2]) + "]" : "[position inconnue]";
    }

    private static String positionSuffix(String position) {
        return position == null ? "" : " (exemple " + position + ")";
    }

    private static String stateSuffix(String state) {
        return state == null ? "" : " [" + state + "]";
    }

    static Snapshot capture(MinecraftServer server, Track track, int radius, boolean allowLoad) {
        ServerLevel sourceLevel = server.getLevel(track.source.dimension());
        if (sourceLevel == null) {
            return null;
        }
        BlockPos planarium = track.center;
        BlockPos min = planarium.offset(-radius, -radius, -radius);
        BlockPos max = planarium.offset(radius, radius, radius);
        // Pour les rafraichissements, on ne force pas le chargement des chunks d'origine.
        if (!allowLoad && !sourceLevel.hasChunksAt(min, max)) {
            return null;
        }
        // Le Planarium doit toujours etre la et pointer vers cette dimension.
        if (!(sourceLevel.getBlockEntity(track.source.pos()) instanceof PlanariumTile tile) || !track.pocket.equals(tile.key)) {
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
        CompoundTag tag = template.save(new CompoundTag());
        settleMovingPistons(tag);
        GiantEntities.capture(sourceLevel, min, size, tag);
        return new Snapshot(tag, captureLight(sourceLevel, min, size), min, palette, biomes,
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
     * Pistons en mouvement dans la photo : pendant 2 ticks, le bloc est un "moving_piston" invisible qui contient
     * l'etat d'arrivee. Une capture tombee a ce moment faisait disparaitre le piston (ou sa tete) du decor jusqu'a la
     * capture suivante. On remplace donc ces blocs par leur etat d'arrivee.
     */
    private static void settleMovingPistons(CompoundTag tag) {
        net.minecraft.nbt.ListTag palette = tag.getList("palette", net.minecraft.nbt.Tag.TAG_COMPOUND);
        net.minecraft.nbt.ListTag blocks = tag.getList("blocks", net.minecraft.nbt.Tag.TAG_COMPOUND);
        Set<Integer> movingStates = new HashSet<>();
        Map<net.minecraft.nbt.Tag, Integer> indexOf = new HashMap<>();
        for (int i = 0; i < palette.size(); i++) {
            CompoundTag state = palette.getCompound(i);
            indexOf.putIfAbsent(state, i);
            if ("minecraft:moving_piston".equals(state.getString("Name"))) {
                movingStates.add(i);
            }
        }
        if (movingStates.isEmpty()) {
            return;
        }
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag block = blocks.getCompound(i);
            if (!movingStates.contains(block.getInt("state")) || !block.contains("nbt", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
                continue;
            }
            CompoundTag nbt = block.getCompound("nbt");
            if (!nbt.contains("blockState", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
                continue;
            }
            CompoundTag arrival = nbt.getCompound("blockState");
            Integer index = indexOf.get(arrival);
            if (index == null) {
                index = palette.size();
                palette.add(arrival.copy());
                indexOf.put(arrival, index);
            }
            block.putInt("state", index);
            block.remove("nbt");
        }
    }
}

package com.matthieu.planariumview.server;

import com.matthieu.planariumview.network.FarRingPayload;

import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Capture compacte des blocs visibles (anneau lointain, sections du decor en vol) : seulement les blocs qui touchent
 * une case eclairee qui laisse passer la vue, avec pour chacun ses voisins pleins et d'eau, la lumiere des cases
 * autour et le biome de surface par colonne de 4 x 4 blocs. Les positions sont relatives a {@code origin}.
 */
final class VisibleCapture {
    private static final Direction[] DIRECTIONS = Direction.values();

    /** Forme capturee : {@code contains} dit si une case voisine existe (sinon : pleine, sauf vers le haut). */
    interface Shape {
        boolean contains(int x, int y, int z);

        /** Case a ne pas envoyer (deja dans une autre photo). */
        default boolean skip(int x, int y, int z) {
            return false;
        }

        /**
         * Vrai si une case sans lumiere (caverne noire) peut quand meme se voir du bocal : ce qui l'entoure est alors
         * envoye. Sinon, les cavernes sombres sont ignorees (on ne les voit pas, et ca allege la photo).
         */
        default boolean darkVisible(int x, int y, int z) {
            return false;
        }
    }

    private final int originX, originY, originZ;
    private final int biomeX, biomeZ;
    private final int quarts;
    private final Map<BlockState, Integer> paletteIndex = new HashMap<>();
    private final IntArrayList palette = new IntArrayList();
    final IntArrayList blocks = new IntArrayList();
    private final IntArrayList info = new IntArrayList();
    private final IntArrayList lightPos = new IntArrayList();
    private final ByteArrayList lightValue = new ByteArrayList();
    private final LongOpenHashSet lightSeen = new LongOpenHashSet();
    private final List<ResourceLocation> biomePalette = new ArrayList<>();
    private final Map<ResourceLocation, Integer> biomeIndex = new HashMap<>();
    private final byte[] biomes;

    /** {@code biomeX}/{@code biomeZ} : coin de la grille des biomes ({@code quarts} colonnes de 4 blocs de cote). */
    VisibleCapture(int originX, int originY, int originZ, int biomeX, int biomeZ, int quarts) {
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.biomeX = biomeX;
        this.biomeZ = biomeZ;
        this.quarts = quarts;
        this.biomes = new byte[quarts * quarts];
    }

    private int local(int x, int y, int z) {
        return FarRingPayload.pack(x - originX, y - originY, z - originZ);
    }

    /** Etat d'une case voisine sans charger de chunk (null : chunk absent). */
    private static BlockState stateAt(ServerLevel level, LevelChunk home, BlockPos pos) {
        if ((pos.getX() >> 4) == home.getPos().x && (pos.getZ() >> 4) == home.getPos().z) {
            return home.getBlockState(pos);
        }
        LevelChunk other = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        return other == null ? null : other.getBlockState(pos);
    }

    /** Capture la boite donnee (bornes incluses) d'un chunk, puis les biomes de ses colonnes. */
    void capture(ServerLevel level, LevelChunk chunk, int x0, int x1, int y0, int y1, int z0, int z1, Shape shape) {
        x0 = Math.max(x0, chunk.getPos().getMinBlockX());
        x1 = Math.min(x1, chunk.getPos().getMaxBlockX());
        z0 = Math.max(z0, chunk.getPos().getMinBlockZ());
        z1 = Math.min(z1, chunk.getPos().getMaxBlockZ());
        y0 = Math.max(y0, level.getMinBuildHeight());
        y1 = Math.min(y1, level.getMaxBuildHeight() - 1);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos next = new BlockPos.MutableBlockPos();
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                if (!shape.contains(x, y0, z)) {
                    continue;
                }
                // Au-dessus du plus haut bloc de la colonne, il n'y a que de l'air.
                int top = Math.min(y1, chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15));
                for (int y = y0; y <= top; y++) {
                    if (shape.skip(x, y, z)) {
                        continue;
                    }
                    pos.set(x, y, z);
                    BlockState state = chunk.getBlockState(pos);
                    if (state.isAir()) {
                        continue;
                    }
                    int solidMask = 0;
                    int waterMask = 0;
                    boolean visible = false;
                    for (Direction direction : DIRECTIONS) {
                        next.setWithOffset(pos, direction);
                        if (!shape.contains(next.getX(), next.getY(), next.getZ())) {
                            if (direction == Direction.UP) {
                                visible = true;
                            } else {
                                solidMask |= 1 << direction.get3DDataValue();
                            }
                            continue;
                        }
                        BlockState neighbor = stateAt(level, chunk, next);
                        if (neighbor == null || neighbor.isSolidRender(level, next)) {
                            solidMask |= 1 << direction.get3DDataValue();
                            continue;
                        }
                        // Eau voisine : le client la remet la ou elle n'est pas envoyee (sous la surface, autour des
                        // algues et des blocs waterlogged), sinon leur eau aurait des faces vers de l'air.
                        if (neighbor.getBlock() instanceof LiquidBlock && neighbor.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) {
                            waterMask |= 1 << direction.get3DDataValue();
                        }
                        // L'eau sous la surface de l'eau ne se voit pas.
                        if (state.getBlock() instanceof LiquidBlock && neighbor.getFluidState().getType().isSame(state.getFluidState().getType())) {
                            continue;
                        }
                        // Une case sans aucune lumiere (caverne noire) : en general, ce qui l'entoure ne se voit pas du
                        // bocal. Sauf pres de la hauteur du Planarium (Planarium dans une grotte ou pres d'un surplomb) :
                        // sans ces blocs, on voyait le ciel a travers les parois sombres.
                        // L'eau profonde n'a plus de lumiere du ciel, mais on voit le fond a travers sa surface : sans
                        // ce fond, on voyait le ciel a travers les lacs et les oceans.
                        if (level.getBrightness(LightLayer.SKY, next) == 0 && level.getBrightness(LightLayer.BLOCK, next) == 0
                                && neighbor.getFluidState().isEmpty()
                                && !shape.darkVisible(next.getX(), next.getY(), next.getZ())) {
                            continue;
                        }
                        visible = true;
                    }
                    if (!visible) {
                        continue;
                    }
                    Integer paletteId = paletteIndex.get(state);
                    if (paletteId == null) {
                        if (palette.size() >= 65535) {
                            continue;
                        }
                        paletteId = palette.size();
                        palette.add(net.minecraft.world.level.block.Block.getId(state));
                        paletteIndex.put(state, paletteId);
                    }
                    blocks.add(local(x, y, z));
                    info.add(paletteId | solidMask << 16 | waterMask << 22);
                    // Lumiere de la case du bloc (blocs pas pleins : escaliers, dalles, algues...).
                    if (lightSeen.add(BlockPos.asLong(x, y, z))) {
                        lightPos.add(local(x, y, z));
                        lightValue.add((byte) (level.getBrightness(LightLayer.SKY, pos) << 4
                                | level.getBrightness(LightLayer.BLOCK, pos)));
                    }
                    // Lumiere des cases autour qui laissent passer la vue (faces et ombrage des coins).
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dy = -1; dy <= 1; dy++) {
                            for (int dz = -1; dz <= 1; dz++) {
                                if (dx == 0 && dy == 0 && dz == 0) continue;
                                int nx = x + dx, ny = y + dy, nz = z + dz;
                                if (!shape.contains(nx, ny, nz)) continue;
                                long key = BlockPos.asLong(nx, ny, nz);
                                if (!lightSeen.add(key)) continue;
                                next.set(nx, ny, nz);
                                BlockState around = stateAt(level, chunk, next);
                                if (around == null || around.isSolidRender(level, next)) continue;
                                lightPos.add(local(nx, ny, nz));
                                lightValue.add((byte) (level.getBrightness(LightLayer.SKY, next) << 4
                                        | level.getBrightness(LightLayer.BLOCK, next)));
                            }
                        }
                    }
                }
            }
        }
        captureBiomes(level, chunk, x0, x1, y0, y1, z0, z1);
    }

    /** Also used for a section's biome halo; never captures blocks or loads additional chunks. */
    void captureBiomes(ServerLevel level, LevelChunk chunk, int x0, int x1, int y0, int y1, int z0, int z1) {
        x0 = Math.max(x0, chunk.getPos().getMinBlockX());
        x1 = Math.min(x1, chunk.getPos().getMaxBlockX());
        z0 = Math.max(z0, chunk.getPos().getMinBlockZ());
        z1 = Math.min(z1, chunk.getPos().getMaxBlockZ());
        y0 = Math.max(y0, level.getMinBuildHeight());
        y1 = Math.min(y1, level.getMaxBuildHeight() - 1);
        if (x0 > x1 || z0 > z1 || y0 > y1) return;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        // Biomes de surface, un par colonne de 4 x 4 blocs (couleurs d'herbe, de feuilles et d'eau).
        for (int qx = Math.max(0, (x0 - biomeX) >> 2); qx <= Math.min(quarts - 1, (x1 - biomeX) >> 2); qx++) {
            for (int qz = Math.max(0, (z0 - biomeZ) >> 2); qz <= Math.min(quarts - 1, (z1 - biomeZ) >> 2); qz++) {
                int x = Math.max(x0, Math.min(x1, biomeX + qx * 4 + 2));
                int z = Math.max(z0, Math.min(z1, biomeZ + qz * 4 + 2));
                int y = Math.max(y0, Math.min(y1, chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15)));
                ResourceLocation id = level.getBiome(pos.set(x, y, z)).unwrapKey()
                        .map(ResourceKey::location).orElse(ResourceLocation.withDefaultNamespace("plains"));
                Integer index = biomeIndex.get(id);
                if (index == null) {
                    index = biomePalette.size() < 255 ? biomePalette.size() : 0;
                    if (index == biomePalette.size()) {
                        biomePalette.add(id);
                        biomeIndex.put(id, index);
                    }
                }
                biomes[qx * quarts + qz] = (byte) (int) index;
            }
        }
    }

    FarRingPayload.Data result() {
        return new FarRingPayload.Data(palette.toIntArray(), blocks.toIntArray(), info.toIntArray(),
                lightPos.toIntArray(), lightValue.toByteArray(), List.copyOf(biomePalette), biomes, quarts);
    }
}

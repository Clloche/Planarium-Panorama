package com.matthieu.planariumview.server;

import com.matthieu.planariumview.compat.SafeReflect;
import com.matthieu.planariumview.compat.SubLevelCompat;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Vector3d;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Contraptions de Create (helices d'Aeronautics, roulements, pistons, portiques...) posees sur une structure Sable. Leurs
 * blocs ne sont plus dans le plot une fois assembles : ils appartiennent a une entite. Le serveur en fait des sections
 * comme celles du plot, dans le repere de la contraption, avec une pose qui suit sa rotation ; le client les dessine
 * comme n'importe quelle section (SubLevelView), sans rien savoir de Create.
 *
 * La pose est lue avec {@code toGlobalVector} de Create (repere local de la contraption -> plot) : elle couvre tous les
 * types de contraptions, chacun avec ses propres rotations et deplacements.
 */
final class ContraptionCapture {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Marge autour du plot ou chercher les entites (les blocs d'une helice depassent du plot). */
    private static final double SEARCH_MARGIN = 48;

    /**
     * Une contraption : ses blocs (positions locales), la matrice locale -> plot, ses sections occupees, l'etendue de
     * ses blocs en x (pour lui reserver des origines factices a elle) et une empreinte de ses blocs (etats et donnees),
     * qui change quand un bloc assemble change (porte ouverte, levier, contenu...).
     */
    record Moving(UUID id, Matrix4d toPlot, Map<BlockPos, StructureTemplate.StructureBlockInfo> blocks, Set<Long> sections,
                  int minX, int maxX, long signature) {
    }

    private static boolean checked, failed;
    private static Class<?> entityType;
    private static SafeReflect.Call getContraption, getBlocks, toGlobalVector;

    private ContraptionCapture() {
    }

    private static boolean available() {
        if (!checked) {
            checked = true;
            try {
                entityType = Class.forName("com.simibubi.create.content.contraptions.AbstractContraptionEntity");
                Class<?> contraption = Class.forName("com.simibubi.create.content.contraptions.Contraption");
                getContraption = SafeReflect.find(entityType, "getContraption");
                getBlocks = SafeReflect.find(contraption, "getBlocks");
                toGlobalVector = SafeReflect.find(entityType, "toGlobalVector", Vec3.class, float.class);
            } catch (ClassNotFoundException | LinkageError ignored) {
                failed = true;
            } catch (ReflectiveOperationException e) {
                LOGGER.warn("[Planarium Panorama] Unsupported Create API; contraptions on sub-levels are not shown", e);
                failed = true;
            }
        }
        return !failed;
    }

    /** Contraptions dont l'entite est dans le plot de cette structure (ou juste autour). */
    @SuppressWarnings("unchecked")
    static List<Moving> find(ServerLevel level, SubLevelCompat.Body body) {
        if (!available()) return List.of();
        AABB plot = new AABB(body.min().getX(), body.min().getY(), body.min().getZ(),
                body.max().getX() + 1, body.max().getY() + 1, body.max().getZ() + 1).inflate(SEARCH_MARGIN);
        List<Moving> result = new ArrayList<>();
        for (Entity entity : level.getEntities((Entity) null, plot, entityType::isInstance)) {
            if (!entity.isAlive()) continue;
            try {
                Object contraption = getContraption.invoke(entity);
                if (contraption == null) continue;
                var blocks = (Map<BlockPos, StructureTemplate.StructureBlockInfo>) getBlocks.invoke(contraption);
                if (blocks == null || blocks.isEmpty()) continue;
                Vec3 origin = global(entity, 0, 0, 0);
                Vec3 x = global(entity, 1, 0, 0).subtract(origin);
                Vec3 y = global(entity, 0, 1, 0).subtract(origin);
                Vec3 z = global(entity, 0, 0, 1).subtract(origin);
                // Colonnes : les axes locaux, puis la translation (constructeur JOML en ordre colonne).
                Matrix4d toPlot = new Matrix4d(x.x, x.y, x.z, 0, y.x, y.y, y.z, 0, z.x, z.y, z.z, 0,
                        origin.x, origin.y, origin.z, 1);
                Set<Long> sections = new HashSet<>();
                int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
                long signature = 0;
                for (Map.Entry<BlockPos, StructureTemplate.StructureBlockInfo> block : blocks.entrySet()) {
                    StructureTemplate.StructureBlockInfo info = block.getValue();
                    if (info.state().isAir()) continue;
                    BlockPos pos = block.getKey();
                    sections.add(SectionPos.asLong(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4));
                    minX = Math.min(minX, pos.getX());
                    maxX = Math.max(maxX, pos.getX());
                    // Somme de hachages melanges : independante de l'ordre de la table.
                    long hash = pos.asLong() * 0x9E3779B97F4A7C15L + System.identityHashCode(info.state());
                    if (info.nbt() != null) hash = hash * 31 + info.nbt().hashCode();
                    signature += mix(hash);
                }
                if (sections.isEmpty()) continue;
                result.add(new Moving(entity.getUUID(), toPlot, blocks, sections, minX, maxX, signature));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                LOGGER.debug("[Planarium Panorama] Contraption skipped", e);
            }
        }
        return result;
    }

    private static long mix(long value) {
        value ^= value >>> 33;
        value *= 0xFF51AFD7ED558CCDL;
        value ^= value >>> 33;
        value *= 0xC4CEB9FE1A85EC53L;
        return value ^ value >>> 33;
    }

    private static Vec3 global(Entity entity, double x, double y, double z) throws ReflectiveOperationException {
        return (Vec3) toGlobalVector.invoke(entity, new Vec3(x, y, z), 1.0F);
    }

    /**
     * Section {@code local} (coin, coordonnees de la contraption) au format d'une section du plot (voir
     * SubLevelServer.capture). {@code origin} : origine unique hors du monde, pour que le client ne la confonde avec
     * aucune vraie section (eclairage colore, sections voisines).
     */
    static CompoundTag capture(ServerLevel source, SubLevelCompat.Body body, Moving moving, BlockPos local, BlockPos origin) {
        CompoundTag geometry = template(moving.blocks(), local, 16);
        // Voisins pour les faces cachees au bord de la section (le client les decale de -1).
        geometry.put("neighbors", template(moving.blocks(), local.offset(-1, -1, -1), 18));
        byte[] light = new byte[18 * 18 * 18];
        Vector3d point = new Vector3d();
        Vector3d world = new Vector3d();
        for (int x = -1; x <= 16; x++) for (int y = -1; y <= 16; y++) for (int z = -1; z <= 16; z++) {
            moving.toPlot().transformPosition(point.set(local.getX() + x + 0.5, local.getY() + y + 0.5, local.getZ() + z + 0.5));
            BlockPos at = BlockPos.containing(point.x, point.y, point.z);
            body.transform().transformPosition(world.set(point));
            BlockPos outside = BlockPos.containing(world.x, world.y, world.z);
            int sky = Math.min(source.getBrightness(LightLayer.SKY, at), source.getBrightness(LightLayer.SKY, outside));
            int block = Math.max(source.getBrightness(LightLayer.BLOCK, at), source.getBrightness(LightLayer.BLOCK, outside));
            light[((x + 1) * 18 + y + 1) * 18 + z + 1] = (byte) (sky << 4 | block);
        }
        geometry.putByteArray("light", light);
        moving.toPlot().transformPosition(point.set(local.getX() + 8, local.getY() + 8, local.getZ() + 8));
        body.transform().transformPosition(point);
        source.getBiome(BlockPos.containing(point.x, point.y, point.z)).unwrapKey().ifPresent(
                key -> geometry.putString("biome", key.location().toString()));
        geometry.putIntArray("origin", new int[]{origin.getX(), origin.getY(), origin.getZ()});
        return geometry;
    }

    /** Les blocs de la boite {@code min} .. {@code min + size}, au format de StructureTemplate.save. */
    private static CompoundTag template(Map<BlockPos, StructureTemplate.StructureBlockInfo> blocks, BlockPos min, int size) {
        ListTag palette = new ListTag();
        Map<BlockState, Integer> ids = new HashMap<>();
        ListTag list = new ListTag();
        for (Map.Entry<BlockPos, StructureTemplate.StructureBlockInfo> entry : blocks.entrySet()) {
            BlockState state = entry.getValue().state();
            if (state.isAir()) continue;
            int x = entry.getKey().getX() - min.getX(), y = entry.getKey().getY() - min.getY(), z = entry.getKey().getZ() - min.getZ();
            if (x < 0 || y < 0 || z < 0 || x >= size || y >= size || z >= size) continue;
            Integer id = ids.get(state);
            if (id == null) {
                id = palette.size();
                palette.add(NbtUtils.writeBlockState(state));
                ids.put(state, id);
            }
            CompoundTag block = new CompoundTag();
            block.put("pos", ints(x, y, z));
            block.putInt("state", id);
            CompoundTag nbt = entry.getValue().nbt();
            if (nbt != null) block.put("nbt", nbt.copy());
            list.add(block);
        }
        CompoundTag tag = new CompoundTag();
        tag.put("size", ints(size, size, size));
        tag.put("blocks", list);
        tag.put("entities", new ListTag());
        tag.put("palette", palette);
        return NbtUtils.addCurrentDataVersion(tag);
    }

    private static ListTag ints(int... values) {
        ListTag list = new ListTag();
        for (int value : values) list.add(IntTag.valueOf(value));
        return list;
    }
}

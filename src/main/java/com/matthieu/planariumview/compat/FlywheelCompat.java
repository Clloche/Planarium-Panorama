package com.matthieu.planariumview.compat;

import com.matthieu.planariumview.client.jar.JarLight;
import com.matthieu.planariumview.config.Config;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Flywheel dessine lui-meme les blocs speciaux (coffres, lits...) et ne calcule leur lumiere qu'a leur creation,
 * puis quand la lumiere de Minecraft change. Il ne voit donc pas la lumiere qui entre dans le bocal (JarLight),
 * qui arrive apres. On lui fait recreer les blocs speciaux du bocal quand cette lumiere change. Par reflexion :
 * Flywheel est optionnel.
 */
public final class FlywheelCompat {
    private static Method get;
    private static Method blockEntities;
    private static Method queueRemove;
    private static Method queueAdd;
    private static boolean missing;

    private FlywheelCompat() {
    }

    private static int solidBytes = -1;
    private static Method onLightUpdate;

    /**
     * Ajoute la lumiere du bocal aux donnees de lumiere d'une section que Flywheel vient de copier.
     * Format de Flywheel : 18 x 18 x 18 octets (section + 1 bloc autour), bloc dans les 4 bits bas, ciel dans les 4 hauts,
     * apres un premier bloc de donnees (les blocs pleins) de SOLID_SIZE_BYTES octets.
     */
    public static void boostSection(long ptr, long section) {
        if (!Config.outsideLight()) {
            return;
        }
        int sx = net.minecraft.core.SectionPos.x(section);
        int sy = net.minecraft.core.SectionPos.y(section);
        int sz = net.minecraft.core.SectionPos.z(section);
        if (sx < -1 || sx > 2 || sy < -1 || sy > 2 || sz < -1 || sz > 2) {
            return;
        }
        try {
            if (solidBytes < 0) {
                solidBytes = Class.forName("dev.engine_room.flywheel.backend.engine.LightStorage")
                        .getField("SOLID_SIZE_BYTES").getInt(null);
            }
            long base = ptr + solidBytes;
            for (int y = -1; y < 17; y++) {
                for (int z = -1; z < 17; z++) {
                    for (int x = -1; x < 17; x++) {
                        int light = JarLight.interiorLevel((sx << 4) + x, (sy << 4) + y, (sz << 4) + z);
                        if (light <= 0) {
                            continue;
                        }
                        long address = base + (x + 1) + (z + 1) * 18L + (y + 1) * 18L * 18L;
                        int value = org.lwjgl.system.MemoryUtil.memGetByte(address) & 0xFF;
                        if ((value & 0xF) < light) {
                            org.lwjgl.system.MemoryUtil.memPutByte(address, (byte) ((value & 0xF0) | light));
                        }
                    }
                }
            }
        } catch (Throwable e) {
            missing = true;
        }
    }

    public static void refresh(ClientLevel level) {
        if (missing || level == null) {
            return;
        }
        try {
            if (get == null) {
                Class<?> manager = Class.forName("dev.engine_room.flywheel.api.visualization.VisualizationManager");
                get = manager.getMethod("get", LevelAccessor.class);
                blockEntities = manager.getMethod("blockEntities");
                Class<?> visuals = blockEntities.getReturnType();
                queueRemove = visuals.getMethod("queueRemove", Object.class);
                queueAdd = visuals.getMethod("queueAdd", Object.class);
            }
            Object manager = get.invoke(null, level);
            if (manager == null) {
                return;
            }
            // Flywheel recopie la lumiere des sections du bocal (et la notre avec, voir boostSection).
            try {
                if (onLightUpdate == null) {
                    onLightUpdate = manager.getClass().getMethod("onLightUpdate", net.minecraft.core.SectionPos.class,
                            net.minecraft.world.level.LightLayer.class);
                }
                for (int sx = -1; sx <= 2; sx++) {
                    for (int sy = 0; sy <= 2; sy++) {
                        for (int sz = -1; sz <= 2; sz++) {
                            onLightUpdate.invoke(manager, net.minecraft.core.SectionPos.of(sx, sy, sz),
                                    net.minecraft.world.level.LightLayer.BLOCK);
                        }
                    }
                }
            } catch (Throwable e) {
                // Autre version de Flywheel : on garde au moins le rechargement des blocs speciaux.
            }
            Object visuals = blockEntities.invoke(manager);
            List<BlockEntity> found = new ArrayList<>();
            for (int cx = -1; cx <= 2; cx++) {
                for (int cz = -1; cz <= 2; cz++) {
                    LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                    if (chunk != null) {
                        found.addAll(chunk.getBlockEntities().values());
                    }
                }
            }
            for (BlockEntity blockEntity : found) {
                queueRemove.invoke(visuals, blockEntity);
                queueAdd.invoke(visuals, blockEntity);
            }
        } catch (Throwable e) {
            missing = true;
        }
    }
}

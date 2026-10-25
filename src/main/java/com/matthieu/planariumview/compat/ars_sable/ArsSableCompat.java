package com.matthieu.planariumview.compat.ars_sable;

import com.matthieu.planariumview.compat.SafeReflect;
import com.mojang.logging.LogUtils;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;

import java.util.Optional;
import java.util.UUID;

/** Reads Ars Sable's per-player entry, which survives movement and world reloads. */
public final class ArsSableCompat {
    private static boolean initialized;
    private static SafeReflect.Call from, getForPlayer, trackedPos, sublevelId;

    public record Entry(GlobalPos position, UUID body) {}

    private ArsSableCompat() {}

    public static Entry entry(ServerLevel level, UUID player) {
        if (!initialized) {
            initialized = true;
            try {
                Class<?> data = Class.forName("com.hollingsworth.ars_sable.common.SublevelPosData");
                Class<?> entry = Class.forName("com.hollingsworth.ars_sable.common.SublevelPosData$Entry");
                getForPlayer = SafeReflect.find(data, "getForPlayer", UUID.class);
                trackedPos = SafeReflect.find(entry, "trackedPos");
                sublevelId = SafeReflect.find(entry, "sublevelId");
                from = SafeReflect.find(data, "from", ServerLevel.class);
            } catch (ClassNotFoundException ignored) {
                // Ars Sable is optional, independently of Sable itself.
            } catch (ReflectiveOperationException | LinkageError e) {
                LogUtils.getLogger().warn("[Planarium Panorama] Ars Sable entry support unavailable", e);
            }
        }
        if (from == null) return null;
        try {
            Object value = getForPlayer.invoke(from.invoke(null, level), player);
            if (value == null) return null;
            return new Entry((GlobalPos) trackedPos.invoke(value),
                    (UUID) ((Optional<?>) sublevelId.invoke(value)).orElse(null));
        } catch (ReflectiveOperationException | LinkageError e) {
            from = null;
            LogUtils.getLogger().warn("[Planarium Panorama] Cannot read Ars Sable entry", e);
            return null;
        }
    }
}

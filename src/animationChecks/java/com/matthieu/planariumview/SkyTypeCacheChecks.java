package com.matthieu.planariumview;

import com.matthieu.planariumview.client.sky.SkySync;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import java.lang.reflect.*;
import java.util.*;

/** Counting registry boundary around production source-type lookup. */
public final class SkyTypeCacheChecks {
    private static Field field(Class<?> type, String name) throws Exception {
        Field f = type.getDeclaredField(name); f.setAccessible(true); return f;
    }
    private static <T> T fixture(Class<T> type) throws Exception {
        Method allocate = MobAnimationIntegrationChecks.class.getDeclaredMethod("fixture", Class.class);
        allocate.setAccessible(true); return type.cast(allocate.invoke(null, type));
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    public static void run() throws Exception {
        int[] reads = {0};
        Map<ResourceLocation, DimensionType> types = new HashMap<>();
        Registry<?> registry = (Registry<?>) Proxy.newProxyInstance(SkyTypeCacheChecks.class.getClassLoader(), new Class<?>[]{Registry.class},
                (p, method, args) -> {
                    if (!method.getName().equals("get")) throw new UnsupportedOperationException(method.toString());
                    reads[0]++; return types.get(args[0]);
                });
        RegistryAccess access = (RegistryAccess) Proxy.newProxyInstance(SkyTypeCacheChecks.class.getClassLoader(), new Class<?>[]{RegistryAccess.class},
                (p, method, args) -> {
                    if (!method.getName().equals("registry")) throw new UnsupportedOperationException(method.toString());
                    return Optional.of(registry);
                });
        ClientLevel first = fixture(ClientLevel.class), second = fixture(ClientLevel.class);
        DimensionType own = fixture(DimensionType.class), source = fixture(DimensionType.class), other = fixture(DimensionType.class);
        for (ClientLevel level : List.of(first, second)) {
            field(Level.class, "registryAccess").set(level, access);
            field(Level.class, "dimensionTypeRegistration").set(level, Holder.direct(own));
        }
        ResourceLocation a = ResourceLocation.parse("checks:a"), b = ResourceLocation.parse("checks:b");
        types.put(a, source); types.put(b, other);
        Method lookup = SkySync.class.getDeclaredMethod("sourceType", ClientLevel.class); lookup.setAccessible(true);
        Field id = field(SkySync.class, "sourceTypeId"); Object previousId = id.get(null);
        try {
            SkySync.clear(); id.set(null, a);
            for (int i = 0; i < 1000; i++) require(lookup.invoke(null, first) == source, "same source type reused across repeated frame queries");
            require(reads[0] == 1, "registry queried only once for a world and source ID");
            id.set(null, b);
            require(lookup.invoke(null, first) == other && reads[0] == 2, "source ID change invalidates cache");
            require(lookup.invoke(null, second) == other && reads[0] == 3, "world change invalidates cache");
            id.set(null, ResourceLocation.parse("checks:missing"));
            require(lookup.invoke(null, second) == null && lookup.invoke(null, second) == null && reads[0] == 4,
                    "missing source type is cached without repeated registry queries");
            id.set(null, a); types.put(a, own);
            require(lookup.invoke(null, second) == null, "own dimension type still uses the existing fallback");
            SkySync.clear(); types.put(a, source);
            require(lookup.invoke(null, second) == source, "clear invalidates source lookup even when world and ID are reused");
            SkySync.clear();
            require(((java.lang.ref.WeakReference<?>) field(SkySync.class, "sourceTypeLevel").get(null)).get() == null
                            && field(SkySync.class, "sourceTypeCached").get(null) == null,
                    "clear releases cached world and type");
            id.set(null, null);
            int before = reads[0];
            require(lookup.invoke(null, first) == null && reads[0] == before, "unknown ID does not query the registry");
        } finally { SkySync.clear(); id.set(null, previousId); }
        System.out.println("PASS: source-type cache hits, source/world changes, missing/own types and clear");
    }
}

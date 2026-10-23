package com.matthieu.planariumview.compat;

import com.hollingsworth.arsnouveau.client.renderer.world.CulledStatePos;
import com.matthieu.planariumview.client.scenery.BiomeGrid;
import com.matthieu.planariumview.client.scenery.ClientView;
import com.matthieu.planariumview.client.scenery.GiantSprites;
import com.matthieu.planariumview.client.scenery.LitRenderingWorld;
import com.matthieu.planariumview.client.sky.SkySync;
import com.matthieu.planariumview.client.sound.OutsideAmbience;
import com.matthieu.planariumview.config.Config;
import com.matthieu.planariumview.network.OutsideViewPayload;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Puddles & Floods cree sa pluie cote client : elle ne figure pas dans la photo du serveur.
 * Son propre tick tourne ici dans une vue aux coordonnees du dehors, avec des ecritures isolees de la photo.
 * Les deux tirages autour du joueur sont adaptes par PfPlacementMixin. Aucun chunk reel n'est modifie.
 */
public final class PuddlesCompat {
    private static boolean checked;
    private static Method tick;
    private static Method stateAt;
    private static Method flatSurface;
    private static Field config;
    private static Field range;
    private static Block puddle;
    private static SceneryLevel scenery;

    private PuddlesCompat() {
    }

    private static boolean ready() {
        if (!checked) {
            checked = true;
            if (!ModList.get().isLoaded("puddleflood")) return false;
            try {
                Class<?> placement = Class.forName("pigcart.puddleflood.ClientBlockPlacementUtil");
                tick = placement.getMethod("tick", Level.class);
                flatSurface = placement.getMethod("isFlatSurface", Level.class, BlockPos.class);
                config = Class.forName("pigcart.puddleflood.config.ConfigManager").getField("config");
                range = config.getType().getField("range");
                puddle = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("puddleflood", "puddle"));
                stateAt = puddle.getClass().getMethod("getStateAt", BlockPos.class, BlockGetter.class);
            } catch (ReflectiveOperationException | LinkageError e) {
                disable(e);
            }
        }
        return stateAt != null;
    }

    private static void disable(Throwable error) {
        tick = null;
        stateAt = null;
        clear();
        LogUtils.getLogger().warn("[Planarium Panorama] Integration Puddles & Floods indisponible", error);
    }

    /** Rattache les flaques au nouveau faux monde; une photo de secours ne doit pas les effacer. */
    public static void prepare(LitRenderingWorld world, OutsideViewPayload info) {
        if (info == null || !Config.weather() || !ready()) return;
        if (scenery != null && scenery.info.pocketDim().equals(info.pocketDim())
                && scenery.info.origin().equals(info.origin()) && scenery.info.radius() == info.radius()) {
            scenery.world.setVisualBlocks(Map.of(), null);
            scenery.world = world;
            scenery.updateInfo(info);
            world.setVisualBlocks(scenery.overrides, puddle);
            scenery.checks.addAll(scenery.overrides.keySet());
        } else {
            clear();
            scenery = new SceneryLevel(world, info);
            world.setVisualBlocks(scenery.overrides, puddle);
        }
    }

    public static void tick(LitRenderingWorld world, OutsideViewPayload info) {
        if (world == null || info == null || Minecraft.getInstance().player == null || !Config.weather()) {
            clear();
            return;
        }
        if (scenery == null || scenery.world != world) prepare(world, info);
        if (scenery == null) return;
        try {
            scenery.updateInfo(info);
            scenery.sampleRange = Math.max(0, range.getInt(config.get(null)));
            scenery.checkCapturedChanges();
            tick.invoke(null, scenery);
            // Le bloc ajoute doit aussi raccorder les bords des flaques deja presentes a cote.
            scenery.refreshEdges();
            if (!scenery.dirty.isEmpty()) {
                ClientView.visualBlocksChanged(world, scenery.dirty);
                scenery.dirty.clear();
            }
        } catch (ReflectiveOperationException | LinkageError e) {
            disable(e);
        }
    }

    public static void blocksChanged(LitRenderingWorld world, Collection<BlockPos> positions) {
        if (scenery == null || scenery.world != world || scenery.overrides.isEmpty()) return;
        Set<BlockPos> affected = new HashSet<>();
        for (BlockPos pos : positions) {
            affected.add(pos);
            affected.add(pos.above());
            for (Direction side : Direction.Plane.HORIZONTAL) {
                affected.add(pos.relative(side));
                affected.add(pos.above().relative(side));
            }
        }
        affected.retainAll(scenery.overrides.keySet());
        scenery.checks.addAll(affected);
    }

    public static Collection<BlockPos> extraPositions(LitRenderingWorld world) {
        return scenery != null && scenery.world == world ? scenery.extra.keySet() : List.of();
    }

    /** Une reconstruction recharge les textures du decor : y remettre aussi celles des flaques conservees. */
    public static void collectSprites(LitRenderingWorld world) {
        if (scenery != null && scenery.world == world) GiantSprites.add(scenery.spriteStates);
    }

    public static List<CulledStatePos> sectionStates(LitRenderingWorld world, int x, int y, int z,
                                                    List<CulledStatePos> captured, int radius, int view) {
        if (scenery == null || scenery.world != world) return captured;
        Map<BlockPos, CulledStatePos> extra = scenery.sections.get(new Section(x, y, z));
        if (extra == null || extra.isEmpty()) return captured;
        List<CulledStatePos> result = new ArrayList<>(captured == null ? List.of() : captured);
        for (CulledStatePos state : extra.values()) {
            if (ClientView.inDetail(state.pos.getX() - radius, state.pos.getZ() - radius, view)
                    && world.getCapturedBlockState(state.pos).isAir()) result.add(state);
        }
        return result;
    }

    public static void clear() {
        if (scenery != null) {
            SceneryLevel old = scenery;
            scenery = null;
            old.world.setVisualBlocks(Map.of(), null);
            ClientView.visualBlocksChanged(old.world, old.overrides.keySet());
        }
    }

    private record Section(int x, int y, int z) {
        static Section of(BlockPos pos) {
            return new Section(Math.floorDiv(pos.getX(), 16), Math.floorDiv(pos.getY(), 16), Math.floorDiv(pos.getZ(), 16));
        }
    }

    /** Vue reservee au tick de Puddles & Floods, avec les vraies coordonnees pour sa texture et ses tests d'altitude. */
    public static final class SceneryLevel extends LitRenderingWorld {
        private LitRenderingWorld world;
        private OutsideViewPayload info;
        private BiomeGrid grid;
        private List<Holder<Biome>> palette;
        private int sampleRange;
        private final RandomSource samples = RandomSource.create();
        private final Map<BlockPos, BlockState> overrides = new HashMap<>();
        private final Map<BlockPos, CulledStatePos> extra = new HashMap<>();
        private final Map<Section, Map<BlockPos, CulledStatePos>> sections = new HashMap<>();
        private final Set<BlockPos> dirty = new HashSet<>();
        private final Set<BlockPos> edgeUpdates = new HashSet<>();
        private final Set<BlockPos> checks = new LinkedHashSet<>();
        private final Set<BlockState> spriteStates = new HashSet<>();

        private SceneryLevel(LitRenderingWorld world, OutsideViewPayload info) {
            super(Minecraft.getInstance().level, List.of());
            this.world = world;
            updateInfo(info);
        }

        private void updateInfo(OutsideViewPayload next) {
            if (info == next) return;
            info = next;
            grid = BiomeGrid.of(info.origin(), info.radius() * 2 + 1);
            var registry = registryAccess().registryOrThrow(Registries.BIOME);
            palette = info.biomePalette().stream()
                    .map(id -> registry.getHolder(ResourceKey.create(Registries.BIOME, id))
                            .orElse(registry.getHolderOrThrow(Biomes.PLAINS)))
                    .map(holder -> (Holder<Biome>) holder).toList();
        }

        private BlockPos local(BlockPos pos) {
            return pos.subtract(info.origin());
        }

        private boolean inside(BlockPos pos) {
            int end = info.radius() * 2;
            // Le voisinage doit etre connu : pas de flaques sur les bords coupes de la photo.
            return pos.getX() > 0 && pos.getX() < end && pos.getZ() > 0 && pos.getZ() < end
                    && pos.getY() > 0 && pos.getY() <= end
                    && !(pos.getX() == info.radius() && pos.getZ() == info.radius()
                    && (pos.getY() == info.radius() || pos.getY() == info.radius() + 1));
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return world.getBlockState(local(pos));
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return world.getBlockEntity(local(pos));
        }

        @Override
        public Holder<Biome> getBiome(BlockPos pos) {
            if (palette.isEmpty() || info.biomes().length != grid.volume()) {
                return OutsideAmbience.knownBiomeHolder() != null ? OutsideAmbience.knownBiomeHolder()
                        : registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS);
            }
            int index = info.biomes()[grid.index(net.minecraft.core.QuartPos.fromBlock(pos.getX()),
                    net.minecraft.core.QuartPos.fromBlock(pos.getY()), net.minecraft.core.QuartPos.fromBlock(pos.getZ()))] & 255;
            return palette.get(Math.min(index, palette.size() - 1));
        }

        @Override
        public int getHeight(Heightmap.Types type, int x, int z) {
            int lx = x - info.origin().getX();
            int lz = z - info.origin().getZ();
            int size = info.radius() * 2 + 1;
            if (lx < 0 || lx >= size || lz < 0 || lz >= size || info.rainTops().length != size * size) {
                return info.origin().getY() - 1;
            }
            return info.rainTops()[lx * size + lz];
        }

        @Override
        public int getSeaLevel() {
            return OutsideAmbience.known() ? OutsideAmbience.sourceSeaLevel() : Integer.MIN_VALUE;
        }

        @Override
        public boolean isRaining() {
            return SkySync.rainLevel(Minecraft.getInstance().level) > 0.2F;
        }

        @Override
        public boolean isThundering() {
            return SkySync.thunderLevel(Minecraft.getInstance().level) > 0.9F;
        }

        /** Les seules ecritures du tick sont des flaques ou leur evaporation; les vrais blocs restent dans la photo. */
        @Override
        public boolean setBlock(BlockPos pos, BlockState state, int flags) {
            return setBlock(pos, state, flags, 512);
        }

        @Override
        public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursion) {
            BlockPos at = local(pos);
            if (!inside(at) || (!state.is(puddle) && !state.isAir())) return false;
            BlockState captured = world.getCapturedBlockState(at);
            if ((!captured.isAir() && !captured.is(puddle)) || getBlockState(pos) == state) return false;
            put(at, state);
            edgeUpdates.add(at);
            for (Direction side : Direction.Plane.HORIZONTAL) edgeUpdates.add(at.relative(side));
            return true;
        }

        private void put(BlockPos at, BlockState state) {
            BlockState before = world.getBlockState(at);
            BlockState captured = world.getCapturedBlockState(at);
            if (state == captured || (state.isAir() && captured.isAir())) {
                overrides.remove(at);
            } else {
                overrides.put(at.immutable(), state);
            }
            boolean needsExtra = !state.isAir() && captured.isAir();
            CulledStatePos previous = extra.get(at);
            if (before == state && (needsExtra ? previous != null && previous.state == state : previous == null)) return;
            Section key = Section.of(at);
            Map<BlockPos, CulledStatePos> section = sections.computeIfAbsent(key, ignored -> new HashMap<>());
            extra.remove(at);
            section.remove(at);
            if (needsExtra) {
                CulledStatePos added = new CulledStatePos(state, at.immutable(), null);
                extra.put(at.immutable(), added);
                section.put(at.immutable(), added);
                if (spriteStates.add(state)) GiantSprites.add(Set.of(state));
            }
            if (section.isEmpty()) sections.remove(key);
            dirty.add(at.immutable());
        }

        private void recheck(Collection<BlockPos> positions) throws ReflectiveOperationException {
            for (BlockPos at : positions) {
                BlockState visual = overrides.get(at);
                if (visual == null) continue;
                BlockState captured = world.getCapturedBlockState(at);
                if ((!captured.isAir() && !captured.is(puddle))
                        || (!visual.isAir() && !(boolean) flatSurface.invoke(null, this, at.offset(info.origin())))) {
                    put(at, captured);
                } else {
                    put(at, visual);
                }
                edgeUpdates.add(at);
                for (Direction side : Direction.Plane.HORIZONTAL) edgeUpdates.add(at.relative(side));
            }
        }

        /** Les photos completes peuvent toucher toutes les flaques : leur validation reste etalee sur les ticks. */
        private void checkCapturedChanges() throws ReflectiveOperationException {
            long start = System.nanoTime();
            for (int i = 0; i < 16 && !checks.isEmpty(); i++) {
                var iterator = checks.iterator();
                BlockPos at = iterator.next();
                iterator.remove();
                recheck(List.of(at));
                if (System.nanoTime() - start >= 1_000_000L) break;
            }
        }

        private void refreshEdges() throws ReflectiveOperationException {
            for (BlockPos at : edgeUpdates) {
                if (!world.getBlockState(at).is(puddle)) continue;
                BlockState state = (BlockState) stateAt.invoke(puddle, at.offset(info.origin()), this);
                CulledStatePos existing = extra.get(at);
                if (existing != null) {
                    existing.setNeedsUpdate(true);
                    existing.setSkipRender(false);
                }
                if (state != world.getBlockState(at)) put(at, state);
            }
            edgeUpdates.clear();
        }

        private Vec3 sourceCamera() {
            Vec3 camera = Minecraft.getInstance().player.position();
            return new Vec3(info.origin().getX() + info.radius() + ClientView.ARS_OFFSET + camera.x / ClientView.GIANT,
                    info.origin().getY() + info.radius() + ClientView.ARS_OFFSET + (camera.y + ClientView.Y_SHIFT) / ClientView.GIANT,
                    info.origin().getZ() + info.radius() + ClientView.ARS_OFFSET + camera.z / ClientView.GIANT);
        }

        public BlockState randomSurface(BlockPos.MutableBlockPos destination) {
            Vec3 center = sourceCamera();
            int x = (int) samples.triangle(center.x, sampleRange);
            int z = (int) samples.triangle(center.z, sampleRange);
            destination.set(x, getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), z);
            return getBlockState(destination);
        }

        public BlockState randomPosition(BlockPos.MutableBlockPos destination, int range) {
            Vec3 center = sourceCamera();
            destination.set((int) samples.triangle(center.x, range), (int) samples.triangle(center.y, range),
                    (int) samples.triangle(center.z, range));
            return getBlockState(destination);
        }
    }
}

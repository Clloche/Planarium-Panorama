package com.matthieu.planariumview;

import com.hollingsworth.arsnouveau.client.renderer.PlanariumRenderingWorld;
import com.hollingsworth.arsnouveau.client.renderer.world.CulledStatePos;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;

import java.util.List;

/**
 * Le faux monde d'Ars Nouveau, mais avec la vraie lumiere capturee par le serveur
 * au lieu de 15 partout.
 */
public class LitRenderingWorld extends PlanariumRenderingWorld {
    private byte[] light;
    /** Lumiere du dehors qui entre dans le bocal (voir JarLight.computeTable), ou null. */
    private byte[] outsideLight;

    /** Lumiere du ciel qui arrive au Planarium (0 a 15), ou -1 pour garder celle du bocal. */
    private int outsideSky = -1;

    /** La miniature n'a pas plus de ciel que l'endroit du Planarium (sombre dans une caverne). */
    public void setOutsideSky(int sky) {
        this.outsideSky = sky;
    }

    /** Ciel du bocal vu depuis l'endroit du Planarium : aussi lumineux que ce ciel, attenue comme au Planarium. */
    private int sky(int jarSky) {
        if (outsideSky < 0 || outsideSky >= 15) {
            return jarSky;
        }
        return combineSky(jarSky, outsideSky);
    }

    /** Niveau de lumiere aussi lumineux que les deux niveaux multiplies (courbe de luminosite de Minecraft). */
    static int combineSky(int a, int b) {
        float h = curve(a) * curve(b);
        float ratio = 4.0F * h / (1.0F + 3.0F * h);
        // Arrondi vers le haut (un peu) : la lumiere de la miniature baissait plus vite que dans le bocal.
        return (int) Math.min(15.0F, (float) Math.ceil(ratio * 15.0F - 0.25F));
    }

    private static float curve(int level) {
        float f = level / 15.0F;
        return f / (4.0F - 3.0F * f);
    }

    /** La miniature recoit aussi la lumiere des torches a cote du Planarium, comme l'interieur du bocal. */
    public void setOutsideLight(byte[] table) {
        this.outsideLight = table;
    }

    /** Lumiere du dehors a cette position du gabarit (le gabarit commence un bloc au-dessus du plancher du bocal). */
    private int outside(BlockPos pos) {
        if (outsideLight == null) {
            return 0;
        }
        int index = JarLight.tableIndex(pos.getX(), pos.getY() + 1, pos.getZ());
        return index < 0 ? 0 : outsideLight[index];
    }
    private int sizeX;
    private int sizeY;
    private int sizeZ;

    public LitRenderingWorld(Level level, List<CulledStatePos> states) {
        super(level, states);
    }

    /** Vrai pendant la construction du faux monde du decor geant (les champs ne sont pas encore prets). */
    private static boolean constructingGiant;
    /** Faux monde du decor geant (positions du monde d'origine), pas d'une miniature de bocal. */
    private boolean giant;

    /** Construit le faux monde du decor geant. */
    public static LitRenderingWorld giant(Level level, List<CulledStatePos> states) {
        constructingGiant = true;
        try {
            LitRenderingWorld world = new LitRenderingWorld(level, states);
            world.giant = true;
            return world;
        } finally {
            constructingGiant = false;
        }
    }

    /** Execute {@code action} en signalant que les faux mondes crees pendant ce temps sont ceux du decor geant. */
    public static <T> T asGiant(java.util.function.Supplier<T> action) {
        boolean before = constructingGiant;
        constructingGiant = true;
        try {
            return action.get();
        } finally {
            constructingGiant = before;
        }
    }

    public static boolean isGiant(Object world) {
        return constructingGiant || (world instanceof LitRenderingWorld lit && lit.giant);
    }

    /** Cube de lumiere avec la meme taille dans les trois axes. */
    public void setLight(byte[] light, int size) {
        setLight(light, size, size, size);
    }

    /** Tableau de lumiere [x][y][z], decale d'un bloc (index 0 = position -1 du gabarit). */
    public void setLight(byte[] light, int sizeX, int sizeY, int sizeZ) {
        if (light == null || light.length != sizeX * sizeY * sizeZ) {
            this.light = null;
            return;
        }
        this.light = light;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
    }

    private java.util.List<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> biomePalette;
    private byte[] biomes;
    private BiomeGrid grid;
    private BlockPos origin = BlockPos.ZERO;
    private net.minecraft.world.level.biome.BiomeManager biomeManager;
    private final java.util.Map<net.minecraft.world.level.ColorResolver, java.util.Map<Long, Integer>> tintCache =
            new java.util.IdentityHashMap<>();

    /** Biomes du monde d'origine (grille 3D, voir {@link BiomeGrid}). {@code origin} = coin du gabarit dans le monde. */
    public void setBiomes(java.util.List<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> palette,
                          byte[] biomes, int size, BlockPos origin) {
        BiomeGrid grid = BiomeGrid.of(origin, size);
        if (palette == null || palette.isEmpty() || biomes == null || biomes.length != grid.volume()) {
            return;
        }
        this.biomePalette = palette;
        this.biomes = biomes;
        this.grid = grid;
        this.origin = origin;
        // Les positions de ce faux monde sont celles du gabarit : on les convertit en positions du monde d'origine.
        this.biomeManager = new net.minecraft.world.level.biome.BiomeManager(
                (qx, qy, qz) -> worldBiome(
                        net.minecraft.core.QuartPos.fromBlock(origin.getX() + net.minecraft.core.QuartPos.toBlock(qx)),
                        net.minecraft.core.QuartPos.fromBlock(origin.getY() + net.minecraft.core.QuartPos.toBlock(qy)),
                        net.minecraft.core.QuartPos.fromBlock(origin.getZ() + net.minecraft.core.QuartPos.toBlock(qz))),
                0L);
    }

    public boolean hasBiomes() {
        return biomes != null;
    }

    /** Biome a des coordonnees de quart du monde d'origine. */
    private net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> worldBiome(int qx, int qy, int qz) {
        int index = biomes[grid.index(qx, qy, qz)] & 0xFF;
        return biomePalette.get(Math.min(index, biomePalette.size() - 1));
    }

    private net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> biomeAtBlock(int worldX, int worldY, int worldZ) {
        return worldBiome(net.minecraft.core.QuartPos.fromBlock(worldX), net.minecraft.core.QuartPos.fromBlock(worldY),
                net.minecraft.core.QuartPos.fromBlock(worldZ));
    }

    @Override
    public net.minecraft.world.level.biome.BiomeManager getBiomeManager() {
        return biomeManager != null ? biomeManager : super.getBiomeManager();
    }

    @Override
    public net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> getNoiseBiome(int qx, int qy, int qz) {
        if (biomes == null) {
            return super.getNoiseBiome(qx, qy, qz);
        }
        return worldBiome(
                net.minecraft.core.QuartPos.fromBlock(origin.getX() + net.minecraft.core.QuartPos.toBlock(qx)),
                net.minecraft.core.QuartPos.fromBlock(origin.getY() + net.minecraft.core.QuartPos.toBlock(qy)),
                net.minecraft.core.QuartPos.fromBlock(origin.getZ() + net.minecraft.core.QuartPos.toBlock(qz)));
    }

    /**
     * Couleur de l'herbe, des feuilles et de l'eau d'apres le vrai biome du monde d'origine,
     * melangee sur 5 x 5 blocs comme le fait Minecraft.
     */
    /** Biome unique pour les couleurs (miniature : le biome du Planarium), ou null. */
    private net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> tintBiome;

    public void setTintBiome(net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> biome) {
        this.tintBiome = biome;
    }

    @Override
    public int getBlockTint(BlockPos pos, net.minecraft.world.level.ColorResolver resolver) {
        if (biomes == null) {
            if (tintBiome != null) {
                return resolver.getColor(tintBiome.value(), pos.getX(), pos.getZ());
            }
            return super.getBlockTint(pos, resolver);
        }
        long key = BlockPos.asLong(pos.getX(), pos.getY(), pos.getZ());
        java.util.Map<Long, Integer> cache = tintCache.computeIfAbsent(resolver, r -> new java.util.HashMap<>());
        Integer cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        int worldY = origin.getY() + pos.getY();
        int result = blend(pos, worldY, resolver);
        cache.put(key, result);
        return result;
    }

    private int blend(BlockPos pos, int worldY, net.minecraft.world.level.ColorResolver resolver) {
        int r = 0;
        int g = 0;
        int b = 0;
        int count = 0;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int worldX = origin.getX() + pos.getX() + dx;
                int worldZ = origin.getZ() + pos.getZ() + dz;
                int color = resolver.getColor(biomeAtBlock(worldX, worldY, worldZ).value(), worldX, worldZ);
                r += (color >> 16) & 255;
                g += (color >> 8) & 255;
                b += color & 255;
                count++;
            }
        }
        return ((r / count) & 255) << 16 | ((g / count) & 255) << 8 | ((b / count) & 255);
    }

    /** Decor geant : le ciel est visible la ou la lumiere du ciel est complete (AmbientSounds le demande). */
    @Override
    public boolean canSeeSky(BlockPos pos) {
        if (!giant) {
            return super.canSeeSky(pos);
        }
        return getBrightness(LightLayer.SKY, pos) >= 15;
    }

    /** Decor geant : le dessus de la colonne, lu dans la photo (AmbientSounds le demande). */
    @Override
    public int getHeight(net.minecraft.world.level.levelgen.Heightmap.Types type, int x, int z) {
        if (!giant) {
            return super.getHeight(type, x, z);
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = 80; y >= 0; y--) {
            pos.set(x, y, z);
            if (!getBlockState(pos).isAir()) {
                return y + 1;
            }
        }
        return 0;
    }

    /** Interieur du bocal, en positions du gabarit (0 a 31) : la miniature seulement, pas le decor geant. */
    private static final int JAR_INSIDE = 32;

    /**
     * Miniature : les blocs qui touchent une paroi de verre recoivent un peu plus de ciel (la lumiere du dehors
     * passe par le verre juste a cote), pour qu'ils ne paraissent pas plus sombres que dans le bocal.
     */
    private int nearGlass(BlockPos pos, int sky) {
        if (giant || sky >= 15 || sky <= 0) {
            return sky;
        }
        int x = pos.getX();
        int z = pos.getZ();
        boolean edge = x == 0 || z == 0 || x == JAR_INSIDE - 1 || z == JAR_INSIDE - 1;
        return edge ? Math.min(15, sky + 2) : sky;
    }

    @Override
    public int getBrightness(LightLayer layer, BlockPos pos) {
        if (light == null) {
            int base = super.getBrightness(layer, pos);
            return layer == LightLayer.BLOCK ? Math.max(base, outside(pos)) : sky(base);
        }
        // Les positions du gabarit commencent a 0 ; le tableau de lumiere a une marge d'un bloc.
        int x = pos.getX() + 1;
        int y = pos.getY() + 1;
        int z = pos.getZ() + 1;
        if (x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) {
            return layer == LightLayer.SKY ? sky(15) : 0;
        }
        int value = light[(x * sizeY + y) * sizeZ + z] & 0xFF;
        return layer == LightLayer.SKY ? sky(nearGlass(pos, value >> 4)) : Math.max(value & 15, outside(pos));
    }
}

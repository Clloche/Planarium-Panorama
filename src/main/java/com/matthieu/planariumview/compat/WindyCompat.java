package com.matthieu.planariumview.compat;

import com.matthieu.planariumview.client.scenery.LitRenderingWorld;
import com.matthieu.planariumview.client.scenery.SceneryParticles;
import com.matthieu.planariumview.client.sky.SkySync;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Windy (optionnel) : ses rafales de vent dans le decor geant, avec ses propres regles et son fichier de config, appliquees
 * a l'endroit du Planarium : monde normal seulement, hauteur minimum et densite selon la hauteur, ciel visible, biomes
 * exclus ou plus venteux, et vent fort sous la pluie. Comme Windy le fait autour du joueur, on tente une rafale a chaque
 * bloc tire au hasard pour l'ambiance du decor. Par reflexion : sans Windy, rien ne change.
 */
public final class WindyCompat {
    private static final ResourceLocation OVERWORLD = ResourceLocation.withDefaultNamespace("overworld");
    private static boolean looked;
    private static Method get;
    private static Method minimumHeight;
    private static Method heightMultiplier;
    private static Method biomeMultiplier;
    private static Field spawnWind;
    private static Field mustSeeSky;
    private static ParticleOptions wind;
    private static ParticleOptions strongWind;

    private WindyCompat() {
    }

    private static boolean ready() {
        if (!looked) {
            looked = true;
            try {
                Class<?> config = Class.forName("xyz.bonfiremc.windy.WindyConfig");
                get = config.getMethod("get");
                minimumHeight = config.getMethod("getActiveMinimumWindHeight");
                heightMultiplier = config.getMethod("getHeightSpawnRateMultiplier", int.class);
                biomeMultiplier = config.getMethod("getBiomeSpawnRateMultiplier", Holder.class);
                spawnWind = config.getField("spawnWind");
                mustSeeSky = config.getField("windMustSeeSky");
                wind = options("wind");
                strongWind = options("strong_wind");
            } catch (Throwable e) {
                get = null;
            }
        }
        return get != null && wind != null;
    }

    private static ParticleOptions options(String name) {
        ParticleType<?> type = BuiltInRegistries.PARTICLE_TYPE.get(ResourceLocation.fromNamespaceAndPath("windy", name));
        return type instanceof ParticleOptions options ? options : null;
    }

    /**
     * Un bloc du decor tire au hasard (positions du gabarit, Planarium au rayon {@code radius}, coin du gabarit a
     * {@code origin} dans le monde) : comme Windy, une petite chance d'y faire naitre une rafale.
     */
    public static void tryWind(LitRenderingWorld world, BlockPos at, int radius, BlockPos origin, RandomSource random) {
        if (origin == null || !ready() || !OVERWORLD.equals(SkySync.sourceDimensionType())) {
            return;
        }
        try {
            Object config = get.invoke(null);
            if (!spawnWind.getBoolean(config)) {
                return;
            }
            int worldY = origin.getY() + at.getY();
            if (worldY < (int) minimumHeight.invoke(config)) {
                return;
            }
            if (mustSeeSky.getBoolean(config) && !world.canSeeSky(at)) {
                return;
            }
            double height = (double) heightMultiplier.invoke(config, worldY);
            if (height <= 0.0) {
                return;
            }
            double biome = (double) biomeMultiplier.invoke(config, world.getBiome(at));
            if (biome <= 0.0) {
                return;
            }
            var level = Minecraft.getInstance().level;
            boolean thundering = SkySync.thunderLevel(level) > 0.9F;
            boolean raining = SkySync.rainLevel(level) > 0.2F;
            double chance = (thundering ? 0.020 : 0.015) * height * biome;
            if (random.nextDouble() * 100.0 > chance || !SceneryParticles.allowed()) {
                return;
            }
            boolean strong = raining || thundering;
            ParticleOptions options = strong && strongWind != null ? strongWind : wind;
            // Comme Windy : la rafale nait de 40 a 70 blocs plus loin, et un peu plus haut.
            double angle = random.nextDouble() * Math.PI * 2.0;
            int distance = random.nextInt(30) + 40;
            double x = at.getX() + random.nextDouble() + Math.cos(angle) * distance;
            double y = at.getY() + random.nextDouble() + random.nextInt(15) + random.nextInt(15);
            double z = at.getZ() + random.nextDouble() + Math.sin(angle) * distance;
            int light = LightTexture.pack(world.getBrightness(LightLayer.BLOCK, at), world.getBrightness(LightLayer.SKY, at));
            SceneryParticles.spawnFar(world, radius, options, x, y, z, light);
        } catch (Throwable e) {
            get = null;
        }
    }
}

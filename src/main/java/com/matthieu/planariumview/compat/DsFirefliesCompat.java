package com.matthieu.planariumview.compat;

import com.matthieu.planariumview.client.scenery.LitRenderingWorld;
import com.matthieu.planariumview.client.scenery.SceneryParticles;
import com.matthieu.planariumview.client.sky.SkySync;
import com.matthieu.planariumview.client.sound.OutsideAmbience;
import com.matthieu.planariumview.config.Config;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Lucioles natives de DS, sans scan supplementaire ni modification de ses ressources. */
public final class DsFirefliesCompat {
    // dsconfigs/blocks.json de DS : meme probabilite par fleur tiree au hasard.
    private static final double CHANCE = 0.045;
    private static final int MAX_FIREFLIES = 128;
    private static final TagKey<Biome> HOT = TagKey.create(Registries.BIOME,
            ResourceLocation.fromNamespaceAndPath("dsurround", "is_hot"));
    private static final List<Particle> ACTIVE = new ArrayList<>();
    private static boolean checked;
    private static Method create, blocksIn, matchesBlock, matchesBiome, snowTemperature;
    private static Field obstacles, enabled;
    private static Object options, tags, flowers, seasons;

    private DsFirefliesCompat() {
    }

    private static boolean ready() {
        if (!checked) {
            checked = true;
            try {
                Class<?> particle = Class.forName("org.orecruncher.dsurround.effects.particles.FireflyParticle");
                if (!SceneryFirefly.class.isAssignableFrom(particle)) {
                    throw new IllegalStateException("Adaptation du vol des lucioles absente");
                }
                create = particle.getMethod("create", Level.class, double.class, double.class, double.class);
                obstacles = particle.getDeclaredField("obstacles");
                obstacles.setAccessible(true);
                blocksIn = Class.forName("org.orecruncher.dsurround.effects.particles.WanderingFlight")
                        .getMethod("blocksIn", BlockGetter.class);
                Object config = Class.forName("org.orecruncher.dsurround.Client").getField("Config").get(null);
                options = config.getClass().getField("fireflyOptions").get(config);
                enabled = options.getClass().getField("enableFireflies");
                Class<?> handler = Class.forName("org.orecruncher.dsurround.effects.WaterRippleHandler");
                Field library = handler.getDeclaredField("TAG_LIBRARY");
                library.setAccessible(true);
                tags = library.get(null);
                Class<?> tagLibrary = Class.forName("org.orecruncher.dsurround.config.libraries.ITagLibrary");
                matchesBlock = tagLibrary.getMethod("is", TagKey.class, BlockState.class);
                matchesBiome = tagLibrary.getMethod("is", TagKey.class, Biome.class);
                flowers = Class.forName("org.orecruncher.dsurround.tags.BlockEffectTags").getField("FIREFLIES").get(null);
                Class<?> seasonal = Class.forName("org.orecruncher.dsurround.lib.seasons.ISeasonalInformation");
                seasons = Class.forName("org.orecruncher.dsurround.lib.di.ContainerManager")
                        .getMethod("resolve", Class.class).invoke(null, seasonal);
                snowTemperature = seasonal.getMethod("isSnowTemperature", BlockPos.class);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                disable(error);
            }
        }
        return create != null;
    }

    private static void disable(Throwable error) {
        create = null;
        LogUtils.getLogger().debug("[Planarium Panorama] Lucioles Dynamic Surroundings indisponibles", error);
    }

    /** environment : coin d'origine du gabarit (geant), ou position du Planarium (miniature). */
    public static void animate(LitRenderingWorld world, BlockPos pos, BlockState state, RandomSource random,
                               BlockPos environment) {
        if (!Config.sceneryParticles() || !Config.outsideAmbience() || random.nextDouble() >= CHANCE || !ready()) return;
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        try {
            if (!enabled.getBoolean(options) || !(boolean) matchesBlock.invoke(tags, flowers, state)) return;
            boolean giant = LitRenderingWorld.isGiant(world);
            var type = giant && SkySync.sourceDimensionType() != null ? level.registryAccess()
                    .registryOrThrow(Registries.DIMENSION_TYPE).get(SkySync.sourceDimensionType()) : level.dimensionType();
            if (type == null || type.hasCeiling() || !type.hasSkyLight()) return;
            // DayCycle de DS : coucher puis nuit, jusqu'au debut de l'aube (259 degres).
            float angle = (giant ? SkySync.timeOfDay(level) : level.getTimeOfDay(0.0F)) * 360.0F;
            if (angle <= 79.0F || angle > 259.0F
                    || (giant ? SkySync.rainLevel(level) : level.getRainLevel(1.0F)) > 0.2F) return;
            BlockPos climatePos = giant ? pos.offset(environment) : environment;
            var outside = giant ? OutsideAmbience.knownBiomeHolder() : null;
            var biome = giant && world.hasBiomes() ? world.getBiome(pos)
                    : outside != null ? outside : level.getBiome(climatePos);
            float temperature = giant ? SeasonColors.outsideTemperature(biome, climatePos) : Float.NaN;
            boolean cold = Float.isNaN(temperature) ? (boolean) snowTemperature.invoke(seasons, climatePos) : temperature < 0.15F;
            if (cold || (boolean) matchesBiome.invoke(tags, HOT, biome.value())) return;
            ACTIVE.removeIf(particle -> !particle.isAlive());
            if (ACTIVE.size() >= MAX_FIREFLIES) return;
            world.addParticle(new SceneryParticles.Made(at -> make(world, at)),
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 0.0, 0.0, 0.0);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            disable(error);
        }
    }

    private static Particle make(LitRenderingWorld world, Vec3 at) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || create == null) return null;
        Particle particle = null;
        try {
            particle = (Particle) create.invoke(null, level, at.x, at.y, at.z);
            if (!(particle instanceof SceneryFirefly)) {
                // Le secours END_ROD de DS peut etre deja ajoute au moteur : ne pas le publier une seconde fois.
                if (particle != null) particle.remove();
                return null;
            }
            // L'evitement des obstacles lit le gabarit. Les coordonnees sont converties par DsFireflyParticleMixin.
            obstacles.set(particle, blocksIn.invoke(null, world));
            ACTIVE.add(particle);
            return particle;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            if (particle != null) particle.remove();
            disable(error);
            return null;
        }
    }

    // DS regroupe ses lumieres avec un rayon unique de 1,75 bloc. Garder deux petits lots supplementaires,
    // puis reutiliser son shader et son dessin avec le rayon de chaque echelle (miniature / decor geant).
    private static final LightBatch MINI_LIGHTS = new LightBatch();
    private static final LightBatch GIANT_LIGHTS = new LightBatch();
    private static SceneryParticles.Space rendering;
    private static float lightScale = 1.0F;
    private static boolean drawing, lightsChecked;
    private static MethodHandle addLight, drawLights;

    public static void rendering(SceneryParticles.Space space) {
        rendering = space;
    }

    /** Vrai : la lumiere a ete retenue, ne pas l'ajouter au lot de taille normale de DS. */
    public static boolean captureLight(double x, double y, double z, float r, float g, float b, float strength) {
        if (rendering == null) return false;
        if (Config.sceneryParticles() && Config.outsideAmbience()) {
            LightBatch batch = rendering.scale() < 1.0 ? MINI_LIGHTS : GIANT_LIGHTS;
            batch.add(x, y, z, r, g, b, strength, (float) rendering.scale());
        }
        return true;
    }

    public static float lightRadius(float radius) {
        return radius * lightScale;
    }

    /** Apres le lot normal de DS, avec le meme retard d'une image que ses propres lumieres. */
    public static void drawScaledLights(Tesselator tesselator) {
        if (drawing || (MINI_LIGHTS.count == 0 && GIANT_LIGHTS.count == 0)) return;
        drawing = true;
        try {
            if (!lightsChecked) {
                lightsChecked = true;
                Class<?> lights = Class.forName("org.orecruncher.dsurround.effects.particles.FireflyLights");
                var lookup = MethodHandles.publicLookup();
                addLight = lookup.unreflect(lights.getMethod("add", double.class, double.class, double.class,
                        float.class, float.class, float.class, float.class));
                drawLights = lookup.unreflect(lights.getMethod("draw", Tesselator.class));
            }
            if (addLight != null && drawLights != null && Config.sceneryParticles() && Config.outsideAmbience()) {
                MINI_LIGHTS.draw(tesselator);
                GIANT_LIGHTS.draw(tesselator);
            }
        } catch (Throwable error) {
            addLight = null;
            drawLights = null;
            LogUtils.getLogger().debug("[Planarium Panorama] Lumiere des lucioles du decor indisponible", error);
        } finally {
            lightScale = 1.0F;
            drawing = false;
            MINI_LIGHTS.count = GIANT_LIGHTS.count = 0;
        }
    }

    private static final class LightBatch {
        // Taille bornee : aucun agrandissement de tableau pendant le dessin.
        final double[] values = new double[MAX_FIREFLIES * 7];
        int count;
        float scale;
        long lastAdded;

        void add(double x, double y, double z, float r, float g, float b, float strength, float scale) {
            if (count >= MAX_FIREFLIES || strength <= 0.0F) return;
            this.scale = scale;
            int i = count++ * 7;
            values[i] = x; values[i + 1] = y; values[i + 2] = z;
            values[i + 3] = r; values[i + 4] = g; values[i + 5] = b; values[i + 6] = strength;
            lastAdded = System.nanoTime();
        }

        void draw(Tesselator tesselator) throws Throwable {
            if (count == 0 || System.nanoTime() - lastAdded > 250_000_000L) return;
            lightScale = scale;
            for (int n = 0; n < count; n++) {
                int i = n * 7;
                addLight.invokeExact(values[i], values[i + 1], values[i + 2], (float) values[i + 3],
                        (float) values[i + 4], (float) values[i + 5], (float) values[i + 6]);
            }
            drawLights.invokeExact(tesselator);
        }
    }

    public static void clear() {
        for (Particle particle : ACTIVE) particle.remove();
        ACTIVE.clear();
        rendering = null;
        MINI_LIGHTS.count = GIANT_LIGHTS.count = 0;
    }
}

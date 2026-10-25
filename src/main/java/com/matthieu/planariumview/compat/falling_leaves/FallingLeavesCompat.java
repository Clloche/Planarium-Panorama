package com.matthieu.planariumview.compat.falling_leaves;

import com.matthieu.planariumview.client.scenery.LitRenderingWorld;
import com.matthieu.planariumview.client.scenery.SceneryParticles;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Falling Leaves (optionnel) : ses feuilles (et la neige des feuilles enneigees) tombent aussi des arbres du decor geant
 * et des miniatures, avec ses propres regles et son fichier de config : frequence (feuillus et coniferes), feuilles
 * posees par un joueur, espace libre en dessous, saisons de Serene Seasons, couleur de la feuille et de son biome.
 * <p>
 * Falling Leaves suppose que le monde du bloc est le vrai monde du client : dans le faux monde du decor, il plantait et
 * l'ambiance de tous les feuillages etait coupee. On le remplace donc dans le decor (voir LeavesBlockMixin), et on fait
 * naitre ses feuilles ici, a l'echelle du decor. Le vent de Falling Leaves est mis a l'echelle aussi (voir
 * SceneryParticles). Par reflexion : sans Falling Leaves, rien ne change.
 */
public final class FallingLeavesCompat {
    private static final String PARTICLE_CLASS = "de.cheaterpaul.fallingleaves.util.FallingLeafParticle";
    private static final ResourceLocation DEFAULT = ResourceLocation.fromNamespaceAndPath("fallingleaves", "default");
    private static final ResourceLocation CONIFER = ResourceLocation.fromNamespaceAndPath("fallingleaves", "conifer");
    private static final RandomSource RENDER_RANDOM = RandomSource.create();

    private static boolean looked;
    private static boolean present;
    private static Object config;
    private static Method getLeafSetting;
    private static Method spawnRateFactor;
    private static Method considerAsConifer;
    private static Method leafType;
    private static Method spriteForLeafType;
    private static Method snowProvider;
    private static Method calculateColor;
    private static Method seasonModifier;
    private static Method create;
    private static Object factory;
    private static Field byMipLevel;
    private static Field windX;
    private static Field windZ;
    private static Class<?> particleClass;

    private FallingLeavesCompat() {
    }

    /** Vrai si Falling Leaves est installe (et qu'on sait lui parler). */
    public static boolean present() {
        if (!looked) {
            looked = true;
            try {
                Class<?> mod = Class.forName("de.cheaterpaul.fallingleaves.FallingLeavesMod");
                Class<?> loader = Class.forName("de.cheaterpaul.fallingleaves.data.LeafLoader");
                Class<?> entry = Class.forName("de.cheaterpaul.fallingleaves.config.LeafSettingsEntry");
                Class<?> util = Class.forName("de.cheaterpaul.fallingleaves.util.LeafUtil");
                Class<?> seasons = Class.forName("de.cheaterpaul.fallingleaves.modcompat.SereneSeasons");
                Class<?> wind = Class.forName("de.cheaterpaul.fallingleaves.util.Wind");
                particleClass = Class.forName(PARTICLE_CLASS);
                Class<?> factoryClass = Class.forName(PARTICLE_CLASS + "$LeavesParticleFactory");
                config = mod.getField("CONFIG").get(null);
                getLeafSetting = loader.getMethod("getLeafSetting", ResourceLocation.class);
                spriteForLeafType = loader.getMethod("getSpriteForLeafType", ResourceLocation.class);
                snowProvider = findMethod(loader, "getSnowParticleProvider", 1);
                spawnRateFactor = entry.getMethod("spawnRateFactor");
                considerAsConifer = entry.getMethod("considerAsConifer");
                leafType = entry.getMethod("leafType");
                calculateColor = util.getDeclaredMethod("calculateLeafColor", ResourceLocation.class, NativeImage.class, int.class);
                calculateColor.setAccessible(true);
                seasonModifier = seasons.getMethod("getModifier", Level.class);
                windX = wind.getField("windX");
                windZ = wind.getField("windZ");
                Constructor<?> constructor = factoryClass.getDeclaredConstructor();
                constructor.setAccessible(true);
                factory = constructor.newInstance();
                create = findMethod(factoryClass, "createParticle", 9);
                byMipLevel = net.minecraft.client.renderer.texture.SpriteContents.class.getDeclaredField("byMipLevel");
                byMipLevel.setAccessible(true);
                present = create != null && snowProvider != null;
            } catch (Throwable e) {
                present = false;
            }
        }
        return present;
    }

    private static Method findMethod(Class<?> owner, String name, int parameters) {
        for (Method method : owner.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == parameters) {
                return method;
            }
        }
        return null;
    }

    /**
     * Un bloc du decor ou d'une miniature joue son ambiance (positions du gabarit) : si c'est un feuillage, ses feuilles
     * et sa neige tombent comme Falling Leaves le ferait. Vrai si le bloc est un feuillage de Falling Leaves.
     */
    public static boolean animate(LitRenderingWorld world, BlockPos pos, BlockState state, RandomSource random) {
        if (!present()) {
            return false;
        }
        try {
            Object settings = getLeafSetting.invoke(null, BuiltInRegistries.BLOCK.getKey(state.getBlock()));
            if (settings == null && !(state.getBlock() instanceof LeavesBlock)) {
                return false;
            }
            if (!(Boolean) value("dropFromPlayerPlacedBlocks") && state.hasProperty(LeavesBlock.PERSISTENT)
                    && state.getValue(LeavesBlock.PERSISTENT)) {
                return true;
            }
            Minecraft mc = Minecraft.getInstance();
            ClientLevel level = mc.level;
            if (level == null) {
                return true;
            }
            spawnLeaves(world, pos, state, random, settings, level);
            spawnSnow(world, pos, state, random, settings);
            return true;
        } catch (Throwable e) {
            return state.getBlock() instanceof LeavesBlock;
        }
    }

    private static Object value(String name) throws ReflectiveOperationException {
        return ((ModConfigSpec.ConfigValue<?>) config.getClass().getField(name).get(config)).get();
    }

    private static double number(String name) throws ReflectiveOperationException {
        return ((Number) value(name)).doubleValue();
    }

    private static void spawnLeaves(LitRenderingWorld world, BlockPos pos, BlockState state, RandomSource random,
                                    Object settings, ClientLevel level) throws ReflectiveOperationException {
        double chance = 1.0;
        double modifier = number("leafSpawnRate");
        if (settings != null) {
            chance = (Double) spawnRateFactor.invoke(settings);
            if ((Boolean) considerAsConifer.invoke(settings)) {
                modifier = number("coniferLeafSpawnRate");
            }
        }
        chance *= modifier / 10.0 / 75.0;
        chance *= ((Number) seasonModifier.invoke(null, level)).doubleValue();
        while (chance > 0) {
            if (random.nextDouble() < chance) {
                double x = pos.getX() + random.nextDouble();
                double y = pos.getY() - random.nextDouble() / 3.0;
                double z = pos.getZ() + random.nextDouble();
                if (roomBelow(world, pos, x, y, z)) {
                    double[] color = leafColor(world, pos, state);
                    Object sprites = spriteSet(state, settings);
                    world.addParticle(new SceneryParticles.Made(at -> make(at, color, sprites)), x, y, z, 0.0, 0.0, 0.0);
                }
            }
            chance -= 1.0;
        }
    }

    private static void spawnSnow(LitRenderingWorld world, BlockPos pos, BlockState state, RandomSource random,
                                  Object settings) throws ReflectiveOperationException {
        if ((Boolean) value("disableSnow") || !snowAbove(world, pos)) {
            return;
        }
        double chance = number("snowSpawnRate") / 10.0 / 75.0;
        while (chance > 0) {
            if (random.nextDouble() < chance) {
                double x = pos.getX() + random.nextDouble();
                double y = pos.getY() - random.nextDouble() / 3.0;
                double z = pos.getZ() + random.nextDouble();
                if (roomBelow(world, pos, x, y, z)) {
                    Object sprites = snowProvider.invoke(null, spriteSet(state, settings));
                    double[] white = {1.0, 1.0, 1.0};
                    world.addParticle(new SceneryParticles.Made(at -> make(at, white, sprites)), x, y, z, 0.0, 0.0, 0.0);
                }
            }
            chance -= 1.0;
        }
    }

    /** La feuille elle-meme, deja a sa place dans le vrai monde (voir SceneryParticles.Made). */
    private static Particle make(net.minecraft.world.phys.Vec3 at, double[] color, Object sprites) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return null;
        }
        try {
            return (Particle) create.invoke(factory, null, level, at.x, at.y, at.z, color[0], color[1], color[2], sprites);
        } catch (Throwable e) {
            return null;
        }
    }

    /** Comme Falling Leaves : la sorte de feuille du bloc (son reglage, son nom, ou feuillu / conifere par defaut). */
    private static Object spriteSet(BlockState state, Object settings) throws ReflectiveOperationException {
        if (settings != null) {
            java.util.Optional<?> type = (java.util.Optional<?>) leafType.invoke(settings);
            if (type.isPresent()) {
                Object sprites = spriteForLeafType.invoke(null, type.get());
                if (sprites != null) {
                    return sprites;
                }
            }
        }
        Object sprites = spriteForLeafType.invoke(null, BuiltInRegistries.BLOCK.getKey(state.getBlock()));
        if (sprites != null) {
            return sprites;
        }
        boolean conifer = settings != null && (Boolean) considerAsConifer.invoke(settings);
        return spriteForLeafType.invoke(null, conifer ? CONIFER : DEFAULT);
    }

    /** Comme Falling Leaves : pas de feuillage juste dessous, et assez de place libre sous la feuille. */
    private static boolean roomBelow(LitRenderingWorld world, BlockPos pos, double x, double y, double z)
            throws ReflectiveOperationException {
        BlockState below = world.getBlockState(pos.below());
        if (below.getBlock() instanceof LeavesBlock || below.is(BlockTags.LEAVES)) {
            return false;
        }
        AABB box = new AABB(x - 0.1, y, z - 0.1, x + 0.1, y - number("minimumFreeSpaceBelow") * 0.5, z + 0.1);
        for (BlockPos at : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ),
                BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
            BlockState state = world.getBlockState(at);
            if (state.isAir()) {
                continue;
            }
            for (AABB part : state.getCollisionShape(world, at).toAabbs()) {
                if (part.move(at).intersects(box)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Comme Falling Leaves : de la neige (jusqu'a 5 blocs pleins plus haut) sur ce feuillage. */
    private static boolean snowAbove(LitRenderingWorld world, BlockPos leaves) {
        BlockPos.MutableBlockPos pos = leaves.mutable();
        for (int distance = 0; distance < 5; distance++) {
            pos.move(Direction.UP);
            BlockState state = world.getBlockState(pos);
            if (state.is(BlockTags.SNOW)) {
                return true;
            }
            if (!net.minecraft.world.level.block.Block.isShapeFullBlock(state.getShape(world, pos))) {
                return false;
            }
        }
        return false;
    }

    /**
     * Comme Falling Leaves : la couleur moyenne de la texture du dessous du feuillage, teintee comme dans le decor
     * (couleur du biome de l'endroit du gabarit).
     */
    private static double[] leafColor(LitRenderingWorld world, BlockPos pos, BlockState state) {
        try {
            Minecraft mc = Minecraft.getInstance();
            BakedModel model = mc.getBlockRenderer().getBlockModel(state);
            ModelData data = model.getModelData(world, pos, state, ModelData.EMPTY);
            RENDER_RANDOM.setSeed(state.getSeed(pos));
            List<BakedQuad> quads = model.getQuads(state, Direction.DOWN, RENDER_RANDOM, data, RenderType.cutout());
            TextureAtlasSprite sprite;
            boolean tinted;
            if (!quads.isEmpty()) {
                sprite = quads.get(0).getSprite();
                tinted = quads.get(0).isTinted();
            } else {
                sprite = model.getParticleIcon(data);
                tinted = true;
            }
            NativeImage texture = ((NativeImage[]) byMipLevel.get(sprite.contents()))[0];
            int tint = tinted ? mc.getBlockColors().getColor(state, world, pos, 0) : -1;
            return (double[]) calculateColor.invoke(null, sprite.contents().name(), texture, tint);
        } catch (Throwable e) {
            return new double[]{1.0, 1.0, 1.0};
        }
    }

    /** Vrai pour une feuille de Falling Leaves (elle se met a l'echelle comme une particule de Minecraft). */
    public static boolean isLeaf(Particle particle) {
        return particleClass != null && particleClass.isInstance(particle);
    }

    /** Vent de Falling Leaves avant la mise a l'echelle (voir scaleWind), ou null. */
    public static float[] scaleWind(Particle particle, double scale) {
        if (!isLeaf(particle) || windX == null) {
            return null;
        }
        try {
            float x = windX.getFloat(null);
            float z = windZ.getFloat(null);
            windX.setFloat(null, (float) (x * scale));
            windZ.setFloat(null, (float) (z * scale));
            return new float[]{x, z};
        } catch (Throwable e) {
            return null;
        }
    }

    public static void restoreWind(float[] wind) {
        if (wind == null) {
            return;
        }
        try {
            windX.setFloat(null, wind[0]);
            windZ.setFloat(null, wind[1]);
        } catch (Throwable ignored) {
            // le vent reviendra au prochain tick de Falling Leaves
        }
    }

    /**
     * Falling Leaves dessine ses feuilles un peu au-dessus de leur position (0,01 a 0,03 bloc) : dans une miniature,
     * elles flotteraient au-dessus du sol. Hauteur a compenser quand la feuille se pose (en blocs du vrai monde).
     */
    public static double renderLift(Particle particle) {
        if (!isLeaf(particle)) {
            return 0.0;
        }
        return switch (particle.hashCode() % 3) {
            case 0 -> 0.01;
            case 1 -> 0.02;
            default -> 0.03;
        };
    }
}

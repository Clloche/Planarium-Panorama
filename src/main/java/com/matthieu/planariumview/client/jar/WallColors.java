package com.matthieu.planariumview.client.jar;

import com.matthieu.planariumview.client.sky.SkySync;
import com.matthieu.planariumview.compat.ColorCompat;
import com.matthieu.planariumview.config.Config;
import com.matthieu.planariumview.network.WallPayload;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cote client : couleur de la lumiere des parois du bocal avec Contraption Lights.
 *
 * Les parois emettent la lumiere des blocs autour du Planarium (voir JarWalls). Pour Contraption Lights,
 * chaque paroi prend la couleur du bloc lumineux qui eclaire ce cote du Planarium (par exemple bleu pour
 * un feu de camp des ames). La couleur est tiree des pixels les plus lumineux de ses textures, comme le fait
 * Contraption Lights pour les blocs sans couleur definie. Tout passe par l'API publique de Contraption Lights,
 * par reflexion (le mod est optionnel).
 */
public final class WallColors {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int PASS = -1;
    public static final int WARM = 0xFFB060;

    private static volatile ResourceLocation dim;
    private static volatile int[] light = new int[JarWalls.FACES];
    private static volatile int[] colors = new int[JarWalls.FACES];
    private static final Map<Integer, Integer> COLOR_CACHE = new ConcurrentHashMap<>();
    private static Method colorChanged;
    private static boolean registered;

    private WallColors() {
    }

    /** A appeler pendant FMLClientSetupEvent. */
    public static void register() {
        if (registered || !ColorCompat.present()) {
            return;
        }
        try {
            Class<?> api = Class.forName("xyz.atmerek.contraptionlights.api.ContraptionLightsApi");
            Class<?> providerType = Class.forName("xyz.atmerek.contraptionlights.api.LightColorProvider");
            Block wall = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("ars_nouveau", "dim_boundary"));
            Object provider = Proxy.newProxyInstance(providerType.getClassLoader(), new Class<?>[]{providerType}, (proxy, method, args) -> {
                String name = method.getName();
                if (name.equals("lightColor") && args != null && args.length == 3 && args[1] instanceof BlockPos pos) {
                    return colorAt(pos);
                }
                if (name.equals("lightColor")) {
                    return PASS;
                }
                if (name.equals("hashCode")) {
                    return System.identityHashCode(proxy);
                }
                if (name.equals("equals")) {
                    return proxy == args[0];
                }
                if (name.equals("toString")) {
                    return "PlanariumPanoramaWallColors";
                }
                return null;
            });
            api.getMethod("registerLightColor", Block.class, providerType).invoke(null, wall, provider);
            colorChanged = api.getMethod("lightColorChanged", Level.class, BlockPos.class);
            registered = true;
        } catch (Throwable e) {
            LOGGER.info("[Planarium Panorama] API de couleur de Contraption Lights indisponible, parois sans couleur", e);
        }
    }

    /** Appele par les fils de construction des chunks de Contraption Lights : doit rester rapide. */
    private static int colorAt(BlockPos pos) {
        ClientLevel level = Minecraft.getInstance().level;
        ResourceLocation current = dim;
        if (current == null || level == null || !Config.jarColoredLight() || !level.dimension().location().equals(current)) {
            return PASS;
        }
        int face = JarWalls.faceAt(pos.getX(), pos.getY(), pos.getZ(), light);
        if (face < 0 || light[face] <= 0) {
            return PASS;
        }
        return colors[face];
    }

    public static void receive(WallPayload payload) {
        int[] next = new int[JarWalls.FACES];
        for (int i = 0; i < JarWalls.FACES; i++) {
            next[i] = payload.sources()[i] < 0 ? WARM : colorOf(payload.sources()[i]);
        }
        boolean changed = !payload.pocketDim().equals(dim) || !java.util.Arrays.equals(next, colors)
                || !java.util.Arrays.equals(payload.light(), light);
        dim = payload.pocketDim();
        light = payload.light();
        colors = next;
        if (changed) {
            notifyChanged();
        }
        JarLight.rebuild(light, SkySync.outsideBlock(), dim);
    }

    /** Lumiere qui entre par chaque face du bocal (voir JarWalls), ou null si inconnue. */
    public static int[] faceLight() {
        return dim == null ? null : light;
    }

    /** Couleur (0xRRGGBB) de la lumiere qui entre par chaque face du bocal. */
    public static int[] faceColors() {
        return colors;
    }

    public static ResourceLocation faceDim() {
        return dim;
    }

    /** Demande a Contraption Lights de recolorer les sections qui contiennent les parois. */
    static void notifyChanged() {
        JarColor.invalidate();
        ClientLevel level = Minecraft.getInstance().level;
        // Seulement dans ce bocal : le message peut arriver juste avant que le client y entre.
        if (colorChanged == null || level == null || dim == null || !level.dimension().location().equals(dim)) {
            return;
        }
        // Toutes les sections du bocal : les parois, et l'interieur colore par JarColor.
        for (int sx = JarWalls.MIN >> 4; sx <= JarWalls.MAX >> 4; sx++) {
            for (int sy = JarWalls.MIN_Y >> 4; sy <= JarWalls.MAX_Y >> 4; sy++) {
                for (int sz = JarWalls.MIN >> 4; sz <= JarWalls.MAX >> 4; sz++) {
                    int x = Math.max(JarWalls.MIN, Math.min(JarWalls.MAX, (sx << 4) + 8));
                    int y = Math.max(JarWalls.MIN_Y, Math.min(JarWalls.MAX_Y, (sy << 4) + 8));
                    int z = Math.max(JarWalls.MIN, Math.min(JarWalls.MAX, (sz << 4) + 8));
                    try {
                        colorChanged.invoke(null, level, new BlockPos(x, y, z));
                    } catch (Throwable e) {
                        return;
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ couleur d'un bloc

    /** Couleur d'un bloc lumineux : moyenne des pixels lumineux et colores de ses textures (0xRRGGBB). */
    public static int colorOf(int stateId) {
        return COLOR_CACHE.computeIfAbsent(stateId, id -> {
            int defined = definedColor(Block.stateById(id));
            if (defined >= 0) {
                return defined;
            }
            try {
                BlockState state = Block.stateById(id);
                BakedModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
                Set<TextureAtlasSprite> sprites = new HashSet<>();
                RandomSource random = RandomSource.create(42L);
                for (Direction direction : Direction.values()) {
                    for (BakedQuad quad : model.getQuads(state, direction, random)) {
                        sprites.add(quad.getSprite());
                    }
                }
                for (BakedQuad quad : model.getQuads(state, null, random)) {
                    sprites.add(quad.getSprite());
                }
                sprites.add(model.getParticleIcon());
                double r = 0;
                double g = 0;
                double b = 0;
                double total = 0;
                for (TextureAtlasSprite sprite : sprites) {
                    NativeImage image = originalImage(sprite.contents());
                    if (image == null) {
                        continue;
                    }
                    for (int y = 0; y < image.getHeight(); y++) {
                        for (int x = 0; x < image.getWidth(); x++) {
                            int abgr = image.getPixelRGBA(x, y);
                            int a = (abgr >>> 24) & 255;
                            int pr = abgr & 255;
                            int pg = (abgr >> 8) & 255;
                            int pb = (abgr >> 16) & 255;
                            int max = Math.max(pr, Math.max(pg, pb));
                            int min = Math.min(pr, Math.min(pg, pb));
                            if (a < 128 || max < 170) {
                                continue;
                            }
                            double saturation = (max - min) / (double) max;
                            double weight = saturation * saturation * max;
                            r += pr * weight;
                            g += pg * weight;
                            b += pb * weight;
                            total += weight;
                        }
                    }
                }
                if (total <= 0.0) {
                    return WARM;
                }
                int cr = (int) Math.min(255, r / total);
                int cg = (int) Math.min(255, g / total);
                int cb = (int) Math.min(255, b / total);
                return cr << 16 | cg << 8 | cb;
            } catch (Throwable e) {
                return WARM;
            }
        });
    }

    private static Method resolveDefinition;
    private static boolean definitionsMissing;

    /**
     * Couleur que Contraption Lights donne a ce bloc dans le vrai monde (ses fichiers light_colors, comme #FF914C
     * pour la torche, sinon sa propre analyse des textures), ou -1 si elle n'est pas disponible.
     */
    private static int definedColor(BlockState state) {
        if (definitionsMissing) {
            return -1;
        }
        try {
            if (resolveDefinition == null) {
                // La couleur complete de Contraption Lights : ses fichiers de couleurs, sinon sa propre analyse des textures.
                resolveDefinition = Class.forName("xyz.atmerek.contraptionlights.light.color.LightSourceColor")
                        .getMethod("of", BlockState.class);
            }
            float[] color = (float[]) resolveDefinition.invoke(null, state);
            if (color == null || color.length < 3) {
                return -1;
            }
            // Valeurs de 0 a 1 (ou de 0 a 255 selon la version)
            float scale = color[0] > 1.0F || color[1] > 1.0F || color[2] > 1.0F ? 1.0F : 255.0F;
            int r = Math.min(255, Math.round(color[0] * scale));
            int g = Math.min(255, Math.round(color[1] * scale));
            int b = Math.min(255, Math.round(color[2] * scale));
            return r << 16 | g << 8 | b;
        } catch (Throwable e) {
            definitionsMissing = true;
            LOGGER.info("[Planarium Panorama] Couleurs definies de Contraption Lights indisponibles, couleur tiree des textures");
            return -1;
        }
    }

    private static Field imageField;

    private static NativeImage originalImage(SpriteContents contents) {
        try {
            if (imageField == null) {
                for (Field field : SpriteContents.class.getDeclaredFields()) {
                    if (field.getType() == NativeImage.class) {
                        field.setAccessible(true);
                        imageField = field;
                        break;
                    }
                }
            }
            return imageField == null ? null : (NativeImage) imageField.get(contents);
        } catch (Throwable e) {
            return null;
        }
    }

    public static void clear() {
        dim = null;
        COLOR_CACHE.clear();
    }
}

package com.matthieu.planariumview;

import com.hollingsworth.arsnouveau.client.renderer.tile.PlanariumRenderer.StructureRenderData;
import com.hollingsworth.arsnouveau.client.renderer.world.CulledStatePos;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Sodium n'anime que les textures qu'il voit dans ses propres chunks. Le decor geant n'en fait pas partie :
 * ses flammes, son eau et sa lave se figeaient quand aucun vrai bloc semblable n'etait a l'ecran.
 * A chaque image ou le decor geant est dessine (donc seulement dans le bocal), on signale ses textures animees a Sodium.
 * Pareil pour les miniatures dessinees dans les Planariums.
 */
public final class GiantSprites {
    private static List<TextureAtlasSprite> animated = List.of();
    private static MethodHandle markActive;
    private static MethodHandle hasAnimation;
    private static Object spriteUtil;
    private static boolean missing;

    private GiantSprites() {
    }

    private static boolean sodium() {
        if (spriteUtil != null) {
            return true;
        }
        if (missing) {
            return false;
        }
        try {
            Class<?> type = Class.forName("net.caffeinemc.mods.sodium.api.texture.SpriteUtil");
            spriteUtil = type.getField("INSTANCE").get(null);
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            markActive = lookup.findVirtual(type, "markSpriteActive", MethodType.methodType(void.class, TextureAtlasSprite.class));
            hasAnimation = lookup.findVirtual(type, "hasAnimation", MethodType.methodType(boolean.class, TextureAtlasSprite.class));
            return true;
        } catch (Throwable e) {
            missing = true;
            spriteUtil = null;
            return false;
        }
    }

    /** Apres la construction du decor : garde ses textures animees. */
    public static void collect(StructureRenderData data) {
        animated = spritesOf(data);
    }

    /** Textures animees de chaque miniature (dessinee par Ars dans le Planarium). */
    private static final java.util.Map<StructureRenderData, List<TextureAtlasSprite>> MINI =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    public static void collectMini(StructureRenderData data) {
        if (data != null) {
            List<TextureAtlasSprite> sprites = spritesOf(data);
            if (!sprites.isEmpty()) {
                MINI.put(data, sprites);
            }
        }
    }

    /** A chaque image ou une miniature est dessinee. */
    public static void markMini(StructureRenderData data) {
        if (data == null || spriteUtil == null) {
            return;
        }
        List<TextureAtlasSprite> sprites = MINI.get(data);
        if (sprites != null) {
            mark(sprites);
        }
    }

    private static List<TextureAtlasSprite> spritesOf(StructureRenderData data) {
        if (data == null || data.statePosCache == null || !sodium()) {
            return List.of();
        }
        Set<TextureAtlasSprite> sprites = new HashSet<>();
        Set<BlockState> seen = new HashSet<>();
        RandomSource random = RandomSource.create(42L);
        Function<ResourceLocation, TextureAtlasSprite> atlas = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS);
        for (CulledStatePos pos : data.statePosCache) {
            BlockState state = pos.state;
            if (!seen.add(state)) {
                continue;
            }
            try {
                BakedModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
                for (Direction direction : Direction.values()) {
                    for (BakedQuad quad : model.getQuads(state, direction, random)) {
                        sprites.add(quad.getSprite());
                    }
                }
                for (BakedQuad quad : model.getQuads(state, null, random)) {
                    sprites.add(quad.getSprite());
                }
                FluidState fluid = state.getFluidState();
                if (!fluid.isEmpty()) {
                    IClientFluidTypeExtensions extensions = IClientFluidTypeExtensions.of(fluid);
                    sprites.add(atlas.apply(extensions.getStillTexture()));
                    sprites.add(atlas.apply(extensions.getFlowingTexture()));
                }
            } catch (Throwable ignored) {
                // un modele particulier : on l'ignore
            }
        }
        List<TextureAtlasSprite> result = new ArrayList<>();
        for (TextureAtlasSprite sprite : sprites) {
            try {
                if (sprite != null && (boolean) hasAnimation.invoke(spriteUtil, sprite)) {
                    result.add(sprite);
                }
            } catch (Throwable ignored) {
                // texture ignoree
            }
        }
        return result;
    }

    /** A chaque image du decor geant. */
    public static void markActive() {
        if (animated.isEmpty() || spriteUtil == null) {
            return;
        }
        if (!mark(animated)) {
            animated = List.of();
        }
    }

    private static boolean mark(List<TextureAtlasSprite> sprites) {
        for (TextureAtlasSprite sprite : sprites) {
            try {
                markActive.invoke(spriteUtil, sprite);
            } catch (Throwable e) {
                return false;
            }
        }
        return true;
    }

    public static void clear() {
        animated = List.of();
    }
}

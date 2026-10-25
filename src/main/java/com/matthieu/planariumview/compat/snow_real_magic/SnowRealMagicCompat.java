package com.matthieu.planariumview.compat.snow_real_magic;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Snow! Real Magic : un bloc couvert de neige (barriere, herbe...) garde le vrai bloc dans son bloc special, et le mod
 * dessine les deux (le bloc, puis la neige) pendant la construction des chunks. Le decor geant et la miniature ne passent
 * pas par la : sans ceci, on ne voyait que la neige. On appelle donc le dessin du mod (ClientHooks.renderHook) avec notre
 * propre "RenderAPI", qui dessine chaque morceau avec le rendu d'Ars. Tout passe par reflexion : sans le mod, rien ne change.
 */
public final class SnowRealMagicCompat {

    /** Dessine un modele pour un bloc (meme pose, decale en hauteur de yOffset). */
    public interface Painter {
        void paint(BlockState state, BakedModel model, PoseStack poseStack);
    }

    private static boolean checked;
    private static Class<?> snowVariant;
    private static Class<?> renderApi;
    private static Method renderHook;
    private static Method getRenderData;
    private static Method camo;
    private static Method options;

    private SnowRealMagicCompat() {
    }

    private static boolean ready() {
        if (!checked) {
            checked = true;
            try {
                snowVariant = Class.forName("snownee.snow.block.SnowVariant");
                renderApi = Class.forName("snownee.snow.client.RenderAPI");
                Class<?> hooks = Class.forName("snownee.snow.client.ClientHooks");
                Class<?> renderData = Class.forName("snownee.snow.block.entity.RenderData");
                Class<?> optionsClass = Class.forName("snownee.snow.block.entity.SnowBlockEntity$Options");
                renderHook = hooks.getMethod("renderHook", BlockState.class, BlockState.class, optionsClass,
                        RenderType.class, renderApi);
                camo = renderData.getMethod("camo");
                options = renderData.getMethod("options");
            } catch (Throwable e) {
                snowVariant = null;
            }
        }
        return snowVariant != null;
    }

    /**
     * Si c'est un bloc couvert de neige de Snow! Real Magic, le dessine (pour cette couche) et renvoie vrai.
     * Faux : bloc normal, a dessiner comme d'habitude.
     */
    public static boolean render(BlockAndTintGetter level, BlockState state, BlockPos pos, RenderType renderType,
                                 PoseStack poseStack, Painter painter) {
        if (!ready()) {
            return false;
        }
        if (!snowVariant.isInstance(state.getBlock())) {
            return upperPlant(level, state, pos, renderType, poseStack, painter);
        }
        try {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity == null) {
                return false;
            }
            if (getRenderData == null) {
                getRenderData = blockEntity.getClass().getMethod("getRenderData");
            }
            Object data = getRenderData.invoke(blockEntity);
            if (data == null) {
                return false;
            }
            BlockState camoState = (BlockState) camo.invoke(data);
            Object opts = options.invoke(data);
            RandomSource random = RandomSource.create();
            Object api = Proxy.newProxyInstance(renderApi.getClassLoader(), new Class<?>[]{renderApi},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "render" -> {
                            BlockState partState = (BlockState) args[0];
                            BakedModel model = partState == state ? unwrap((BakedModel) args[1]) : variant((BakedModel) args[1]);
                            double yOffset = ((Number) args[2]).doubleValue();
                            random.setSeed(42L);
                            if (renderType != null && !model.getRenderTypes(partState, random, ModelData.EMPTY).contains(renderType)) {
                                yield false;
                            }
                            poseStack.pushPose();
                            if (yOffset != 0.0) {
                                poseStack.translate(0.0, yOffset, 0.0);
                            }
                            try {
                                painter.paint(partState, model, poseStack);
                            } finally {
                                poseStack.popPose();
                            }
                            yield true;
                        }
                        case "level" -> level;
                        case "pos" -> pos;
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        case "toString" -> "PlanariumPanoramaRenderAPI";
                        default -> null;
                    });
            renderHook.invoke(null, state, camoState, opts, renderType, api);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * Haut d'une grande plante (grande fougere, hautes herbes) dont le bas est couvert de neige : le haut reste un bloc
     * normal, mais Snow! Real Magic le dessine avec sa version enneigee. On fait pareil.
     */
    private static boolean upperPlant(BlockAndTintGetter level, BlockState state, BlockPos pos, RenderType renderType,
                                      PoseStack poseStack, Painter painter) {
        try {
            if (!state.hasProperty(net.minecraft.world.level.block.DoublePlantBlock.HALF)
                    || state.getValue(net.minecraft.world.level.block.DoublePlantBlock.HALF)
                    != net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER
                    || !snowVariant.isInstance(level.getBlockState(pos.below()).getBlock())) {
                return false;
            }
            BakedModel model = net.minecraft.client.Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
            BakedModel snowy = variant(model);
            if (snowy == model) {
                return false;
            }
            if (renderType == null || snowy.getRenderTypes(state, RandomSource.create(42L), ModelData.EMPTY).contains(renderType)) {
                painter.paint(state, snowy, poseStack);
            }
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * Herbe, fougeres... : Snow! Real Magic a une version enneigee du modele (SnowVariantModel), choisie quand le bloc
     * est couvert de neige. On prend cette version.
     */
    private static BakedModel variant(BakedModel model) {
        if (!model.getClass().getName().endsWith("SnowVariantModel")) {
            return model;
        }
        try {
            Field field = model.getClass().getDeclaredField("variantModel");
            field.setAccessible(true);
            Object variant = field.get(model);
            return variant instanceof BakedModel baked ? baked : model;
        } catch (Throwable e) {
            return model;
        }
    }

    /**
     * Le modele du bloc couvert de neige lui-meme (qui appellerait ce dessin en boucle) : on prend le modele qu'il enveloppe,
     * comme le fait Snow! Real Magic.
     */
    private static BakedModel unwrap(BakedModel model) {
        if (!model.getClass().getName().startsWith("snownee.snow")) {
            return model;
        }
        for (Class<?> type = model.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field wrapped = type.getDeclaredField("wrapped");
                wrapped.setAccessible(true);
                Object inner = wrapped.get(model);
                if (inner instanceof BakedModel baked) {
                    return baked;
                }
            } catch (NoSuchFieldException ignored) {
                // classe parente suivante
            } catch (Throwable e) {
                return model;
            }
        }
        return model;
    }
}

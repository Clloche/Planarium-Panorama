package com.matthieu.planariumview.mixin;

import com.matthieu.planariumview.compat.ColorCompat;

import com.hollingsworth.arsnouveau.client.renderer.tile.PlanariumRenderer;
import com.hollingsworth.arsnouveau.client.renderer.world.CulledStatePos;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Ars passe la position du bloc dans le monde (et pas dans le gabarit) au calcul de l'eclairage.
 * Le faux monde ne connait que les positions du gabarit : on lui passe la bonne position
 * pour que la lumiere et l'ombrage des coins soient lus au bon endroit.
 */
@Mixin(value = PlanariumRenderer.class, remap = false)
public abstract class PlanariumRendererMixin {

    @Redirect(method = "generateRender",
            at = @At(value = "INVOKE",
                    target = "Lcom/hollingsworth/arsnouveau/client/renderer/tile/PlanariumRenderer;tesselateBlock(Lcom/hollingsworth/arsnouveau/client/renderer/world/CulledStatePos;Lnet/minecraft/client/renderer/block/ModelBlockRenderer;Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/client/resources/model/BakedModel;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/util/RandomSource;JILnet/neoforged/neoforge/client/model/data/ModelData;Lnet/minecraft/client/renderer/RenderType;)V"),
            require = 0)
    private static void planariumview$templatePos(CulledStatePos tile, ModelBlockRenderer renderer, BlockAndTintGetter level,
                                                  BakedModel model, BlockState state, BlockPos worldPos, PoseStack poseStack,
                                                  VertexConsumer consumer, RandomSource random, long seed, int overlay,
                                                  ModelData modelData, RenderType renderType) {
        // Bloc couvert de neige de Snow! Real Magic : le vrai bloc, puis la neige (voir SnowRealMagicCompat).
        if (com.matthieu.planariumview.config.Config.enabled()
                && com.matthieu.planariumview.compat.SnowRealMagicCompat.render(level, state, tile.pos, renderType, poseStack,
                (partState, partModel, pose) -> PlanariumRenderer.tesselateBlock(tile, renderer, level, partModel, partState,
                        tile.pos, pose, consumer, random, seed, overlay, ModelData.EMPTY, renderType))) {
            return;
        }
        PlanariumRenderer.tesselateBlock(tile, renderer, level, model, state, tile.pos, poseStack, consumer, random,
                seed, overlay, modelData, renderType);
    }

    /** Ajoute la couleur de Contraption Lights a la copie d'Ars de getLightColor (voir ClTag). */
    @Inject(method = "getLightColor(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)I",
            at = @At("RETURN"), cancellable = true, require = 0)
    private static void planariumview$colorTag(BlockAndTintGetter level, BlockState state, BlockPos pos,
                                               org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Integer> cir) {
        int packed = cir.getReturnValueI();
        int tagged = com.matthieu.planariumview.compat.ClTag.tagBaked(packed, level, pos);
        if (tagged != packed) {
            cir.setReturnValue(tagged);
        }
    }

    /** Pendant la construction de la miniature, Contraption Lights lit la couleur dans le faux monde de la miniature. */
    @Inject(method = "generateRender", at = @At("HEAD"), require = 0)
    private static void planariumview$colorBegin(PlanariumRenderer.StructureRenderData data, Level level, BlockPos renderPos,
                                                 Vec3 cameraPosition, CallbackInfo ci) {
        if (data != null) {
            com.matthieu.planariumview.client.mini.MiniOutsideLight.apply(data, level, renderPos);
            ColorCompat.begin(data.fakeRenderingWorld);
            com.matthieu.planariumview.client.mini.MiniParticles.readHues(data);
        }
    }

    /** Reconstruit la miniature quand la lumiere autour du Planarium change (une torche posee a cote). */
    @Inject(method = "render(Lcom/hollingsworth/arsnouveau/common/block/tile/PlanariumTile;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
            at = @At("HEAD"), require = 0)
    private void planariumview$outsideLight(com.hollingsworth.arsnouveau.common.block.tile.PlanariumTile tile, float partialTick,
                                            PoseStack poseStack, net.minecraft.client.renderer.MultiBufferSource buffers,
                                            int packedLight, int packedOverlay, CallbackInfo ci) {
        // Faux monde de cette miniature : les blocs speciaux d'Ars sont rattaches au vrai monde, pas a lui.
        PlanariumRenderer.StructureRenderData miniData = tile.key != null ? PlanariumRenderer.structureRenderData.get(tile.key) : null;
        planariumview$miniWorld = miniData != null ? miniData.fakeRenderingWorld : null;
        com.matthieu.planariumview.client.mini.MiniOutsideLight.check(tile);
        com.matthieu.planariumview.client.mini.MiniParticles.seen(tile, packedLight);
        com.matthieu.planariumview.client.mini.PlanariumFloor.render(tile, poseStack, buffers, packedLight);
        if (tile.key != null && com.matthieu.planariumview.config.Config.enabled()) {
            com.matthieu.planariumview.client.scenery.GiantSprites.markMini(PlanariumRenderer.structureRenderData.get(tile.key));
        }
    }

    @org.spongepowered.asm.mixin.Unique
    private static boolean planariumview$beLogged;

    /** Faux monde de la miniature en train d'etre dessinee (voir planariumview$outsideLight), ou null. */
    @org.spongepowered.asm.mixin.Unique
    private static net.minecraft.world.level.Level planariumview$miniWorld;

    @Inject(method = "render(Lcom/hollingsworth/arsnouveau/common/block/tile/PlanariumTile;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
            at = @At("RETURN"), require = 0)
    private void planariumview$miniWorldEnd(com.hollingsworth.arsnouveau.common.block.tile.PlanariumTile tile, float partialTick,
                                            PoseStack poseStack, net.minecraft.client.renderer.MultiBufferSource buffers,
                                            int packedLight, int packedOverlay, CallbackInfo ci) {
        planariumview$miniWorld = null;
    }

    /**
     * Ars dessine les blocs speciaux de la miniature (coffres, bannieres...) en pleine lumiere : avec la vraie lumiere,
     * ils sont eclaires comme les blocs autour d'eux (meme lumiere que le reste de la miniature).
     */
    @Redirect(method = "render(Lcom/hollingsworth/arsnouveau/common/block/tile/PlanariumTile;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderer;render(Lnet/minecraft/world/level/block/entity/BlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V"),
            require = 0)
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void planariumview$blockEntityLight(net.minecraft.client.renderer.blockentity.BlockEntityRenderer renderer,
                                                net.minecraft.world.level.block.entity.BlockEntity blockEntity, float partialTick,
                                                PoseStack poseStack, net.minecraft.client.renderer.MultiBufferSource buffers,
                                                int packedLight, int packedOverlay) {
        if (com.matthieu.planariumview.config.Config.enabled()
                && com.matthieu.planariumview.compat.AmbersolCompat.render(blockEntity, partialTick, poseStack, buffers)) {
            return;
        }
        int light = packedLight;
        if (com.matthieu.planariumview.config.Config.realLighting()
                && planariumview$miniWorld instanceof com.matthieu.planariumview.client.scenery.LitRenderingWorld lit) {
            try {
                light = PlanariumRenderer.getLightColor(lit, blockEntity.getBlockState(), blockEntity.getBlockPos());
            } catch (Exception e) {
                light = packedLight;
            }
            if (!planariumview$beLogged) {
                planariumview$beLogged = true;
                org.slf4j.LoggerFactory.getLogger("planariumview").info(
                        "[Planarium Panorama] Miniature : lumiere des blocs speciaux ({} : ciel {}, blocs {})",
                        net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType()),
                        net.minecraft.client.renderer.LightTexture.sky(light), net.minecraft.client.renderer.LightTexture.block(light));
            }
        }
        renderer.render(blockEntity, partialTick, poseStack, buffers, light, packedOverlay);
    }

    @Inject(method = "generateRender", at = @At("RETURN"), require = 0)
    private static void planariumview$colorEnd(PlanariumRenderer.StructureRenderData data, Level level, BlockPos renderPos,
                                               Vec3 cameraPosition, CallbackInfo ci) {
        ColorCompat.end();
        com.matthieu.planariumview.client.jar.JarColor.clearMini();
    }
}

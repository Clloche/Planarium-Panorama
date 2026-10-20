package com.matthieu.planariumview.mixin;

import com.matthieu.planariumview.compat.ColorCompat;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.hollingsworth.arsnouveau.client.renderer.tile.PlanariumRenderer;
import com.hollingsworth.arsnouveau.client.renderer.world.CulledStatePos;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexSorting;
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
import org.spongepowered.asm.mixin.injection.Slice;
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

    /** Les couches d'un meme bloc se suivent dans generateRender et reutilisent le meme BlockPos. */
    @org.spongepowered.asm.mixin.Unique
    private static BlockPos planariumview$lastFluidPos;

    /** Les sommets de la miniature sont locaux au bloc du Planarium, et doivent se trier du plus loin au plus pres. */
    @Redirect(method = "generateRender", at = @At(value = "INVOKE", target =
            "Lcom/mojang/blaze3d/vertex/VertexSorting;byDistance(Lcom/mojang/blaze3d/vertex/VertexSorting$DistanceFunction;)Lcom/mojang/blaze3d/vertex/VertexSorting;"),
            require = 0)
    private static VertexSorting planariumview$initialTransparencySort(VertexSorting.DistanceFunction original,
            PlanariumRenderer.StructureRenderData data, Level level, BlockPos renderPos, Vec3 cameraPosition) {
        return planariumview$miniSort(original, renderPos, cameraPosition);
    }

    /** Ars oublie de soustraire la position du Planarium lors des tris suivants (sortAll -> sort). */
    @Redirect(method = "sort", at = @At(value = "INVOKE", target =
            "Lcom/mojang/blaze3d/vertex/VertexSorting;byDistance(Lcom/mojang/blaze3d/vertex/VertexSorting$DistanceFunction;)Lcom/mojang/blaze3d/vertex/VertexSorting;"),
            require = 0)
    private static VertexSorting planariumview$updatedTransparencySort(VertexSorting.DistanceFunction original,
            PlanariumRenderer.StructureRenderData data, BlockPos renderPos, RenderType renderType) {
        return planariumview$miniSort(original, renderPos,
                net.minecraft.client.Minecraft.getInstance().gameRenderer.getMainCamera().getPosition());
    }

    @org.spongepowered.asm.mixin.Unique
    private static VertexSorting planariumview$miniSort(VertexSorting.DistanceFunction original,
                                                        BlockPos renderPos, Vec3 camera) {
        if (!com.matthieu.planariumview.config.Config.enabled()) {
            return VertexSorting.byDistance(original);
        }
        // byDistance trie deja par distance decroissante : la distance negative d'Ars inversait cet ordre.
        return VertexSorting.byDistance((float) (camera.x - renderPos.getX()),
                (float) (camera.y - renderPos.getY()), (float) (camera.z - renderPos.getZ()));
    }

    /** Ars saute le modele des blocs qui contiennent de l'eau (herbes marines, cornichons, blocs waterlogged). */
    @Redirect(method = "generateRender",
            slice = @Slice(from = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/state/BlockState;getRenderShape()Lnet/minecraft/world/level/block/RenderShape;")),
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/material/FluidState;isEmpty()Z"),
            require = 0)
    private static boolean planariumview$includeWaterloggedModel(net.minecraft.world.level.material.FluidState fluid) {
        // Cette condition decide seulement si le modele sera dessine ; l'eau a deja ete dessinee juste avant.
        return com.matthieu.planariumview.config.Config.enabled() || fluid.isEmpty();
    }

    /** Meme couche pour toute l'eau, libre ou dans un bloc ; les modeles gardent leur propre couche. */
    @Redirect(method = "generateRender", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/client/renderer/block/BlockRenderDispatcher;renderLiquid(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/BlockAndTintGetter;Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;)V"),
            require = 0)
    private static void planariumview$fluidLayer(net.minecraft.client.renderer.block.BlockRenderDispatcher dispatcher,
            BlockPos pos, BlockAndTintGetter world, VertexConsumer consumer, BlockState state,
            net.minecraft.world.level.material.FluidState fluid, PlanariumRenderer.StructureRenderData data,
            Level level, BlockPos renderPos, Vec3 cameraPosition) {
        boolean enabled = com.matthieu.planariumview.config.Config.enabled();
        if (enabled && planariumview$lastFluidPos == pos) {
            return;
        }
        if (enabled
                && consumer instanceof com.hollingsworth.arsnouveau.client.renderer.LiquidBlockVertexConsumer liquid) {
            // Ars utilise la couche du modele du bloc, meme pour son fluide : l'eau libre peut finir en solid,
            // et celle des plantes en cutout. Choisir la couche du fluide pour les deux (eau translucide, lave solid).
            RenderType fluidLayer = net.minecraft.client.renderer.ItemBlockRenderTypes.getRenderLayer(fluid);
            var builder = data.bufferBuilders.computeIfAbsent(fluidLayer,
                    type -> new com.mojang.blaze3d.vertex.BufferBuilder(data.getByteBuffer(type), type.mode(), type.format()));
            consumer = new com.hollingsworth.arsnouveau.client.renderer.LiquidBlockVertexConsumer(builder, liquid.pose(), liquid.pos());
        }
        dispatcher.renderLiquid(pos, world, consumer, state, fluid);
        if (enabled) {
            planariumview$lastFluidPos = pos;
        }
    }

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

    /** Le contexte de couleur doit etre ferme meme si un modele modde echoue pendant la construction. */
    @WrapMethod(method = "generateRender", require = 0)
    private static void planariumview$colorScope(PlanariumRenderer.StructureRenderData data, Level level, BlockPos renderPos,
                                                 Vec3 cameraPosition, Operation<Void> original) {
        planariumview$lastFluidPos = null;
        if (data != null) {
            com.matthieu.planariumview.client.mini.MiniOutsideLight.apply(data, level, renderPos);
        }
        ColorCompat.begin(data != null ? data.fakeRenderingWorld : null);
        try {
            if (data != null) {
                com.matthieu.planariumview.client.mini.MiniParticles.readHues(data);
            }
            original.call(data, level, renderPos, cameraPosition);
        } finally {
            ColorCompat.end();
            com.matthieu.planariumview.client.jar.JarColor.clearMini();
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
        com.matthieu.planariumview.client.mini.MiniEntities.render(tile, partialTick, poseStack, buffers, packedLight);
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
        if (com.matthieu.planariumview.compat.SwingingLanternsCompat.skip(renderer)) {
            // Lanterne de Swinging Lanterns : immobile dans la miniature (voir SwingingLanternsCompat).
            return;
        }
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

}

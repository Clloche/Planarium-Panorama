package com.matthieu.planariumview.mixin;

import com.hollingsworth.arsnouveau.client.renderer.tile.PlanariumRenderer;
import com.matthieu.planariumview.MiniLight;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Quand Ars prepare le rendu de la miniature, on remplace son faux monde (lumiere 15 partout)
 * par un faux monde avec la vraie lumiere du bocal.
 */
@Mixin(value = PlanariumRenderer.StructureRenderData.class, remap = false)
public abstract class StructureRenderDataMixin {

    @Inject(method = "<init>", at = @At("TAIL"), require = 0)
    private void planariumview$realLight(StructureTemplate template, long lastUpdatedAt, CallbackInfo ci) {
        MiniLight.apply((PlanariumRenderer.StructureRenderData) (Object) this, template);
    }
}

package com.matthieu.planariumview.mixin;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.dimension.DimensionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Le bocal a de la lumiere du ciel (voir le datapack real_light). Le serveur le sait, mais le client peut garder
 * la version d'origine d'Ars Nouveau (sans ciel) : il n'a alors aucune lumiere du ciel dans le bocal et les blocs
 * restent noirs de jour. On reconnait le type de dimension du bocal a ses effets (ars_nouveau:jar).
 */
@Mixin(DimensionType.class)
public abstract class DimensionTypeSkyMixin {
    private static final ResourceLocation PLANARIUMVIEW_JAR = ResourceLocation.fromNamespaceAndPath("ars_nouveau", "jar");

    @Shadow
    public abstract ResourceLocation effectsLocation();

    @Inject(method = "hasSkyLight", at = @At("HEAD"), cancellable = true)
    private void planariumview$jarSky(CallbackInfoReturnable<Boolean> cir) {
        if (PLANARIUMVIEW_JAR.equals(effectsLocation())) {
            cir.setReturnValue(true);
        }
    }
}

package com.matthieu.planariumview.mixin.compat.dynamic_surroundings;

import com.matthieu.planariumview.client.sound.OutsideAmbience;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Dans un bocal, Dynamic Surroundings "voit" le biome qui entoure le Planarium au lieu du biome du bocal.
 */
@Mixin(targets = "org.orecruncher.dsurround.processing.scanner.BiomeScanner", remap = false)
public abstract class DsBiomeScannerMixin {

    /** Dernier etat connu "bocal sous l'eau", pour refaire l'analyse quand il change. */
    private boolean planariumview$outsideWater;

    private static boolean planariumview$submerged() {
        net.minecraft.client.player.LocalPlayer player = net.minecraft.client.Minecraft.getInstance().player;
        return player != null && OutsideAmbience.active()
                && com.matthieu.planariumview.client.scenery.OutsideFluidView.fogAt(player.getEyePosition())
                == net.minecraft.world.level.material.FogType.WATER;
    }

    /**
     * Bocal sous l'eau : Dynamic Surroundings joue l'ambiance sous-marine (riviere, ocean, ocean profond selon le biome
     * du dehors) au lieu de celle du biome. Il ne refait son analyse que si le joueur change de bloc ou de biome :
     * on la force quand le bocal entre dans l'eau ou en sort.
     */
    @Inject(method = "tick", at = @At("HEAD"), require = 0)
    private void planariumview$rescanOnImmersion(long tickCount, CallbackInfo ci) {
        boolean water = planariumview$submerged();
        if (water == planariumview$outsideWater) {
            return;
        }
        planariumview$outsideWater = water;
        try {
            java.lang.reflect.Field field = this.getClass().getDeclaredField("surveyedBiome");
            field.setAccessible(true);
            field.set(this, null);
        } catch (Throwable e) {
            // Autre version de Dynamic Surroundings : l'analyse suivra au prochain changement de bloc.
        }
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;isEyeInFluid(Lnet/minecraft/tags/TagKey;)Z"), require = 0)
    private boolean planariumview$eyeInWater(net.minecraft.world.entity.player.Player player,
                                             net.minecraft.tags.TagKey<net.minecraft.world.level.material.Fluid> tag) {
        return player.isEyeInFluid(tag)
                || (tag == net.minecraft.tags.FluidTags.WATER && planariumview$submerged());
    }

    @Redirect(method = {"tick", "resolveBiome"},
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/biome/BiomeManager;getBiome(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/Holder;"),
            require = 0)
    private Holder<Biome> planariumview$outsideBiome(BiomeManager manager, BlockPos pos) {
        Holder<Biome> outside = OutsideAmbience.biomeHolder();
        return outside != null ? outside : manager.getBiome(pos);
    }

    /**
     * Planarium tres haut (dans l'Aether par exemple) : comme a cote du Planarium, Dynamic Surroundings joue les sons
     * d'altitude (vent des nuages, silence de l'espace) au lieu de ceux du biome. Pas sous terre, ni a l'abri, ni dans une grotte.
     */
    @Inject(method = "resolveBiome", at = @At("RETURN"), cancellable = true, require = 0)
    private void planariumview$altitude(BiomeManager access, BlockPos pos, CallbackInfoReturnable<Object> cir) {
        if (!OutsideAmbience.active() || OutsideAmbience.underground() || OutsideAmbience.sheltered()) {
            return;
        }
        String altitude = com.matthieu.planariumview.compat.dynamic_surroundings.DsAltitude.altitudeBiome();
        if (altitude == null) {
            return;
        }
        try {
            Object info = cir.getReturnValue();
            if (info != null && (Boolean) info.getClass().getMethod("isCave").invoke(info)) {
                return;
            }
            java.lang.reflect.Field field = this.getClass().getDeclaredField("biomeLibrary");
            field.setAccessible(true);
            Object library = field.get(this);
            Class<?> synthetic = Class.forName("org.orecruncher.dsurround.config.SyntheticBiome");
            Object biome = synthetic.getField(altitude).get(null);
            Object result = library.getClass().getMethod("getBiomeInfo", synthetic).invoke(library, biome);
            if (result != null) {
                cir.setReturnValue(result);
            }
        } catch (Throwable e) {
            // Autre version de Dynamic Surroundings : on garde le biome normal.
        }
    }
}

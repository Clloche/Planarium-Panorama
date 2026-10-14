package com.matthieu.planariumview.mixin.compat;

import com.matthieu.planariumview.OutsideAmbience;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Dans un bocal, Dynamic Surroundings "voit" le biome qui entoure le Planarium au lieu du biome du bocal.
 */
@Mixin(targets = "org.orecruncher.dsurround.processing.scanner.BiomeScanner", remap = false)
public abstract class DsBiomeScannerMixin {

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
        String altitude = com.matthieu.planariumview.DsAltitude.altitudeBiome();
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

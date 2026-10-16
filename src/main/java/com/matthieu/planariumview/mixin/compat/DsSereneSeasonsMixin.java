package com.matthieu.planariumview.mixin.compat;

import com.matthieu.planariumview.client.sound.OutsideAmbience;
import com.matthieu.planariumview.compat.SeasonColors;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Dans un bocal, Dynamic Surroundings lit la saison de Serene Seasons dans la dimension du bocal, qui n'en a pas.
 * Avec l'ambiance exterieure, il utilise la saison et la temperature du monde d'origine
 * (par exemple : pas de grillons ni d'oiseaux au debut du printemps dans un biome froid).
 */
@Mixin(targets = "org.orecruncher.dsurround.lib.seasons.compat.SereneSeasons", remap = false)
public abstract class DsSereneSeasonsMixin {

    private static boolean planariumview$active() {
        return OutsideAmbience.biomeHolder() != null && SeasonColors.outsideSeason() != null;
    }

    private static void planariumview$season(String season, CallbackInfoReturnable<Boolean> cir) {
        if (planariumview$active()) {
            cir.setReturnValue(season.equals(SeasonColors.outsideSeason()));
        }
    }

    private static void planariumview$part(String prefix, CallbackInfoReturnable<Boolean> cir) {
        if (planariumview$active()) {
            String sub = SeasonColors.outsideSubSeason();
            cir.setReturnValue(sub != null && sub.startsWith(prefix));
        }
    }

    @Inject(method = "isSpring", at = @At("HEAD"), cancellable = true, require = 0)
    private void planariumview$spring(CallbackInfoReturnable<Boolean> cir) {
        planariumview$season("SPRING", cir);
    }

    @Inject(method = "isSummer", at = @At("HEAD"), cancellable = true, require = 0)
    private void planariumview$summer(CallbackInfoReturnable<Boolean> cir) {
        planariumview$season("SUMMER", cir);
    }

    @Inject(method = "isAutumn", at = @At("HEAD"), cancellable = true, require = 0)
    private void planariumview$autumn(CallbackInfoReturnable<Boolean> cir) {
        planariumview$season("AUTUMN", cir);
    }

    @Inject(method = "isWinter", at = @At("HEAD"), cancellable = true, require = 0)
    private void planariumview$winter(CallbackInfoReturnable<Boolean> cir) {
        planariumview$season("WINTER", cir);
    }

    @Inject(method = "isEarly", at = @At("HEAD"), cancellable = true, require = 0)
    private void planariumview$early(CallbackInfoReturnable<Boolean> cir) {
        planariumview$part("EARLY_", cir);
    }

    @Inject(method = "isMiddle", at = @At("HEAD"), cancellable = true, require = 0)
    private void planariumview$middle(CallbackInfoReturnable<Boolean> cir) {
        planariumview$part("MID_", cir);
    }

    @Inject(method = "isLate", at = @At("HEAD"), cancellable = true, require = 0)
    private void planariumview$late(CallbackInfoReturnable<Boolean> cir) {
        planariumview$part("LATE_", cir);
    }

    @Inject(method = "getTemperature", at = @At("HEAD"), cancellable = true, require = 0)
    private void planariumview$temperature(BlockPos pos, CallbackInfoReturnable<Float> cir) {
        float temperature = planariumview$outsideTemperature(pos);
        if (!Float.isNaN(temperature)) {
            cir.setReturnValue(temperature);
        }
    }

    /**
     * Temperature a cote du Planarium : son biome, sa saison, et surtout sa hauteur (plus froid en altitude,
     * comme dans l'Aether). Avec la hauteur du joueur dans le bocal (tout en bas), il faisait trop chaud :
     * Dynamic Surroundings jouait les sons de foret au lieu du silence (ou du vent) qu'on entend a cote du Planarium.
     */
    private static float planariumview$outsideTemperature(BlockPos pos) {
        Holder<Biome> biome = OutsideAmbience.biomeHolder();
        if (biome == null) {
            return Float.NaN;
        }
        BlockPos at = OutsideAmbience.atPlanariumHeight(pos);
        float temperature = SeasonColors.outsideSeason() != null ? SeasonColors.outsideTemperature(biome, at) : Float.NaN;
        return Float.isNaN(temperature) ? OutsideAmbience.biomeTemperature(biome, at) : temperature;
    }

    @Inject(method = "getPrecipitationAt", at = @At("HEAD"), cancellable = true, require = 0)
    private void planariumview$precipitation(BlockPos pos, CallbackInfoReturnable<Biome.Precipitation> cir) {
        Holder<Biome> biome = OutsideAmbience.biomeHolder();
        float temperature = planariumview$outsideTemperature(pos);
        if (biome == null || Float.isNaN(temperature)) {
            return;
        }
        if (!biome.value().getModifiedClimateSettings().hasPrecipitation()) {
            cir.setReturnValue(Biome.Precipitation.NONE);
        } else {
            cir.setReturnValue(temperature < 0.15F ? Biome.Precipitation.SNOW : Biome.Precipitation.RAIN);
        }
    }
}

package com.matthieu.planariumview.mixin;

import com.hollingsworth.arsnouveau.ArsNouveau;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Cote serveur : les bocaux partagent la meteo du monde principal, donc des eclairs naturels (taille normale)
 * pouvaient tomber a l'interieur du bocal pendant un orage. Pas d'orage pour les eclairs naturels dans un bocal :
 * les eclairs du dehors y sont montres en geant (voir LightningView). Les eclairs invoques (commande, trident)
 * ne changent pas.
 */
@Mixin(ServerLevel.class)
public abstract class PocketLightningMixin {

    private static boolean planariumview$noThunder(Level level) {
        return level.dimensionTypeRegistration().is(ArsNouveau.DIMENSION_TYPE_KEY);
    }

    @Redirect(method = "tickChunk", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;isThundering()Z"),
            require = 0)
    private boolean planariumview$thunderServerLevel(ServerLevel level) {
        return !planariumview$noThunder(level) && level.isThundering();
    }

    @Redirect(method = "tickChunk", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;isThundering()Z"),
            require = 0)
    private boolean planariumview$thunderLevel(Level level) {
        return !planariumview$noThunder(level) && level.isThundering();
    }
}

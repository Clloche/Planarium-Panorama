package com.matthieu.planariumview.mixin;

import com.matthieu.planariumview.SceneryParticles;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Une particule du decor qui en cree une autre (goutte qui pend, puis tombe, puis s'ecrase) : la nouvelle va dans le
 * decor geant, a la meme echelle, au lieu d'apparaitre en petit dans le monde du bocal.
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelParticleMixin {

    @Inject(method = "addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V", at = @At("HEAD"),
            cancellable = true, require = 0)
    private void planariumview$sceneryChild(ParticleOptions options, double x, double y, double z, double vx, double vy,
                                            double vz, CallbackInfo ci) {
        if (SceneryParticles.spawnChild(options, x, y, z, vx, vy, vz)) {
            ci.cancel();
        }
    }
}

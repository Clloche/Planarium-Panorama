package com.matthieu.planariumview.mixin.compat.dynamic_surroundings;

import com.matthieu.planariumview.client.scenery.SceneryParticles;
import com.matthieu.planariumview.compat.dynamic_surroundings.DsFirefliesCompat;
import com.matthieu.planariumview.compat.dynamic_surroundings.SceneryFirefly;
import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Le vol natif travaille en blocs du gabarit, le dessin en blocs du monde client. */
@Pseudo
@Mixin(targets = "org.orecruncher.dsurround.effects.particles.FireflyParticle", remap = false)
public abstract class DsFireflyParticleMixin implements SceneryFirefly {
    @Unique private SceneryParticles.Space planariumview$space;

    @Override
    public SceneryParticles.Space planariumview$space() {
        return planariumview$space;
    }

    @Override
    public void planariumview$space(SceneryParticles.Space space) {
        planariumview$space = space;
    }

    @ModifyArgs(method = "tick", at = @At(value = "INVOKE", target =
            "Lorg/orecruncher/dsurround/effects/particles/WanderingFlight;tick(DDDIFLorg/orecruncher/dsurround/effects/particles/WanderingFlight$Obstacles;)V"), require = 0)
    private void planariumview$flightPosition(Args args) {
        var space = planariumview$space;
        if (space == null) return;
        args.set(0, ((double) args.get(0) - space.ox()) / space.scale());
        args.set(1, ((double) args.get(1) - space.oy()) / space.scale());
        args.set(2, ((double) args.get(2) - space.oz()) / space.scale());
    }

    @ModifyArgs(method = "move", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/phys/AABB;move(DDD)Lnet/minecraft/world/phys/AABB;"), require = 0)
    private void planariumview$flightMovement(Args args) {
        if (planariumview$space == null) return;
        double scale = planariumview$space.scale();
        args.set(0, (double) args.get(0) * scale);
        args.set(1, (double) args.get(1) * scale);
        args.set(2, (double) args.get(2) * scale);
    }

    @Inject(method = "move", at = @At("RETURN"), require = 0)
    private void planariumview$bounds(double dx, double dy, double dz, CallbackInfo ci) {
        if (planariumview$space != null) {
            SceneryParticles.collide((Particle) (Object) this, dy * planariumview$space.scale());
        }
    }

    @Inject(method = "render", at = @At("HEAD"), require = 0)
    private void planariumview$beginLight(CallbackInfo ci) {
        DsFirefliesCompat.rendering(planariumview$space);
    }

    @Inject(method = "render", at = @At("RETURN"), require = 0)
    private void planariumview$endLight(CallbackInfo ci) {
        DsFirefliesCompat.rendering(null);
    }
}

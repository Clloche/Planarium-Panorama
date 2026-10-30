package com.matthieu.planariumview.mixin;

import com.matthieu.planariumview.client.scenery.SceneryParticles;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.particle.Particle;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Particules du decor geant : elles flottent loin du bocal, la ou le monde du bocal est vide et noir. Elles prennent
 * donc la lumiere du bloc du decor d'ou elles viennent (les particules qui brillent, comme les flammes, gardent la leur).
 */
@Mixin(Particle.class)
public abstract class ParticleLightMixin {

    /** Conserve Particle.move (arret, vitesses, sol), mais calcule ses collisions dans la miniature. */
    @WrapOperation(method = "move", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/world/entity/Entity;collideBoundingBox(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Lnet/minecraft/world/level/Level;Ljava/util/List;)Lnet/minecraft/world/phys/Vec3;"),
            require = 0)
    private Vec3 planariumview$miniCollisions(Entity entity, Vec3 motion, AABB box, Level level,
                                             List<VoxelShape> shapes, Operation<Vec3> original) {
        Vec3 result = SceneryParticles.miniCollision((Particle) (Object) this, motion, box);
        return result != null ? result : original.call(entity, motion, box, level, shapes);
    }

    /** Tick de SceneryParticles ou la lumiere ci-dessous a ete lue (voir planariumview$sceneryLight). */
    @Unique private long planariumview$lightTick = Long.MIN_VALUE;
    @Unique private boolean planariumview$hasLight;
    @Unique private int planariumview$light;

    /**
     * Une seule lecture par tick et par particule : une particule ne bouge qu'a chaque tick, et sa lumiere suit encore
     * celle des blocs autour d'elle (relue au tick suivant, meme immobile). Avant, chaque image relisait le faux monde.
     */
    @Inject(method = "getLightColor", at = @At("HEAD"), cancellable = true, require = 0)
    private void planariumview$sceneryLight(float partialTick, CallbackInfoReturnable<Integer> cir) {
        long tick = SceneryParticles.tickCount();
        if (tick != planariumview$lightTick) {
            planariumview$lightTick = tick;
            Integer light = SceneryParticles.lightOf((Particle) (Object) this);
            planariumview$hasLight = light != null;
            if (light != null) planariumview$light = light;
        }
        if (planariumview$hasLight) {
            cir.setReturnValue(planariumview$light);
        }
    }

    /** Les particules du decor s'arretent sur les blocs du decor (voir SceneryParticles.collide). */
    @Inject(method = "move", at = @At("TAIL"), require = 0)
    private void planariumview$sceneryGround(double dx, double dy, double dz, CallbackInfo ci) {
        SceneryParticles.collide((Particle) (Object) this, dy);
    }
}

package com.matthieu.planariumview.mixin;

import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Particules du decor geant (SceneryParticles) : vitesse, gravite et collisions ajustees apres leur creation. */
@Mixin(Particle.class)
public interface ParticleAccessor {
    @Accessor("xd")
    double planariumview$getXd();

    @Accessor("xd")
    void planariumview$setXd(double value);

    @Accessor("yd")
    double planariumview$getYd();

    @Accessor("yd")
    void planariumview$setYd(double value);

    @Accessor("zd")
    double planariumview$getZd();

    @Accessor("zd")
    void planariumview$setZd(double value);

    @Accessor("gravity")
    float planariumview$getGravity();

    @Accessor("gravity")
    void planariumview$setGravity(float value);

    @Accessor("hasPhysics")
    void planariumview$setHasPhysics(boolean value);

    @Accessor("onGround")
    void planariumview$setOnGround(boolean value);

    @Accessor("x")
    double planariumview$getX();

    @Accessor("y")
    double planariumview$getY();

    @Accessor("z")
    double planariumview$getZ();

    @Accessor("yo")
    double planariumview$getYo();

    @Accessor("xo")
    void planariumview$setXo(double value);

    @Accessor("yo")
    void planariumview$setYo(double value);

    @Accessor("zo")
    void planariumview$setZo(double value);
}

package com.matthieu.planariumview.client.scenery.entities;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Parrot;

/** Client wing counters only: no entity ticks, AI, falling physics, sounds or egg laying. */
public final class OutsideWings {
    private float flapping = 1;

    public void tick(Entity entity) {
        if (entity instanceof Chicken chicken) {
            chicken.oFlap = chicken.flap;
            chicken.oFlapSpeed = chicken.flapSpeed;
            chicken.flapSpeed = amplitude(chicken.flapSpeed, !chicken.onGround());
            chicken.flapping = speed(chicken.onGround());
            chicken.flap += chicken.flapping * 2;
        } else if (entity instanceof Parrot parrot) {
            parrot.oFlap = parrot.flap;
            parrot.oFlapSpeed = parrot.flapSpeed;
            parrot.flapSpeed = amplitude(parrot.flapSpeed, !parrot.onGround() && !parrot.isPassenger());
            parrot.flap += speed(parrot.onGround()) * 2;
        } else if (entity instanceof Bat bat) {
            if (bat.isResting()) {
                bat.flyAnimationState.stop();
                bat.restAnimationState.startIfStopped(bat.tickCount);
            } else {
                bat.restAnimationState.stop();
                bat.flyAnimationState.startIfStopped(bat.tickCount);
            }
        }
        // Bees, allays, vexes and phantoms use the render age; OutsideMobs already advances tickCount.
    }

    private static float amplitude(float previous, boolean flying) {
        return Mth.clamp(previous + (flying ? 4 : -1) * .3F, 0, 1);
    }

    private float speed(boolean onGround) {
        if (!onGround && flapping < 1) flapping = 1;
        flapping *= .9F;
        return flapping;
    }
}

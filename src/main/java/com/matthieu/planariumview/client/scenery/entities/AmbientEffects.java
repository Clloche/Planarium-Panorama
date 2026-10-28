package com.matthieu.planariumview.client.scenery.entities;

import com.matthieu.planariumview.client.scenery.ClientView;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Endermite;

/**
 * Particules que des mobs creent eux-memes a chaque tick cote client, dans leur aiStep (les copies ne le font pas tourner).
 * Memes formules que vanilla, a la position retardee de la copie.
 */
public final class AmbientEffects {
    private AmbientEffects() {}

    static void tick(LivingEntity entity) {
        sample(entity, entity.getRandom(), ClientView::onMobParticle);
    }

    public static void sample(LivingEntity entity, RandomSource random, AquaticEffects.Sink sink) {
        // EnderMan.aiStep et Endermite.aiStep : deux particules de portail par tick, qui retombent vers le mob.
        if (entity instanceof EnderMan || entity instanceof Endermite) {
            double yOffset = entity instanceof EnderMan ? -0.25 : 0.0;
            for (int i = 0; i < 2; i++) {
                sink.emit(ParticleTypes.PORTAL, entity.getRandomX(0.5), entity.getRandomY() + yOffset, entity.getRandomZ(0.5),
                        (random.nextDouble() - 0.5) * 2.0, -random.nextDouble(), (random.nextDouble() - 0.5) * 2.0);
            }
        }
    }
}

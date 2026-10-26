package com.matthieu.planariumview.mixin.entities;

import com.matthieu.planariumview.client.scenery.entities.MobEffects;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;

/** Potion metadata is already in OutsideMobsPayload; the copy's NBT effect durations are not ticked. */
@Mixin(LivingEntity.class)
public abstract class LivingEffectParticlesMixin implements MobEffects.PotionParticles {
    @Shadow @Final private static EntityDataAccessor<List<ParticleOptions>> DATA_EFFECT_PARTICLES;
    @Shadow @Final private static EntityDataAccessor<Boolean> DATA_EFFECT_AMBIENCE_ID;

    @Override public List<ParticleOptions> planariumview$effectParticles() {
        return ((LivingEntity) (Object) this).getEntityData().get(DATA_EFFECT_PARTICLES);
    }

    @Override public boolean planariumview$ambientEffects() {
        return ((LivingEntity) (Object) this).getEntityData().get(DATA_EFFECT_AMBIENCE_ID);
    }
}

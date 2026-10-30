package com.matthieu.planariumview.client.scenery.entities;

import com.matthieu.planariumview.mixin.entities.HoglinAnimationAccessor;
import com.matthieu.planariumview.mixin.entities.IronGolemAnimationAccessor;
import com.matthieu.planariumview.mixin.entities.RavagerAnimationAccessor;
import com.matthieu.planariumview.mixin.entities.SheepAnimationAccessor;
import com.matthieu.planariumview.mixin.entities.ZoglinAnimationAccessor;
import com.matthieu.planariumview.network.entities.MobAnimations;

import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.LivingEntity;
import org.slf4j.Logger;

import java.util.List;

/**
 * Evenements d'entite visuels (coup du golem de fer, du ravageur, des hoglins, du warden ; fleur offerte ; mouton qui
 * broute ; voir MobAnimations.visualEvent) rejoues sur les copies du decor et des miniatures, et les compteurs que
 * vanilla decremente dans aiStep, que les copies ne font jamais tourner.
 */
public final class MobEventAnimations {
    private static final Logger LOGGER = LogUtils.getLogger();

    private MobEventAnimations() {
    }

    /** Chaque tick d'une copie qui suit le serveur, apres MobAnimationTimeline.apply. */
    public static void tick(LivingEntity entity, List<Integer> events) {
        for (int id : events) play(entity, id);
        countDown(entity);
    }

    /**
     * Le traitement vanilla de l'evenement, en silence : la copie est dans le monde du client a ses coordonnees de
     * decor, un son y serait joue au mauvais endroit (le golem et les hoglins font un bruit d'attaque).
     */
    private static void play(LivingEntity entity, int id) {
        if (!MobAnimations.visualEvent(entity, id)) return;
        boolean silent = entity.isSilent();
        try {
            entity.setSilent(true);
            entity.handleEntityEvent((byte) id);
        } catch (RuntimeException e) {
            LOGGER.debug("[Planarium Panorama] Evenement {} d'une copie ignore", id, e);
        } finally {
            entity.setSilent(silent);
        }
    }

    /** Comme leur aiStep cote client : un tick de moins (les AnimationState du warden suivent deja tickCount). */
    private static void countDown(LivingEntity entity) {
        if (entity instanceof IronGolemAnimationAccessor golem) {
            golem.planariumview$setAttackAnimationTick(Math.max(0, golem.planariumview$getAttackAnimationTick() - 1));
            golem.planariumview$setOfferFlowerTick(Math.max(0, golem.planariumview$getOfferFlowerTick() - 1));
        } else if (entity instanceof RavagerAnimationAccessor ravager) {
            ravager.planariumview$setAttackTick(Math.max(0, ravager.planariumview$getAttackTick() - 1));
            ravager.planariumview$setStunnedTick(Math.max(0, ravager.planariumview$getStunnedTick() - 1));
        } else if (entity instanceof HoglinAnimationAccessor hoglin) {
            hoglin.planariumview$setAttackAnimationRemainingTicks(
                    Math.max(0, hoglin.planariumview$getAttackAnimationRemainingTicks() - 1));
        } else if (entity instanceof ZoglinAnimationAccessor zoglin) {
            zoglin.planariumview$setAttackAnimationRemainingTicks(
                    Math.max(0, zoglin.planariumview$getAttackAnimationRemainingTicks() - 1));
        } else if (entity instanceof SheepAnimationAccessor sheep) {
            sheep.planariumview$setEatAnimationTick(Math.max(0, sheep.planariumview$getEatAnimationTick() - 1));
        }
    }

    /** Le direct d'une miniature s'arrete : pas de bras leve ni de fleur tendue figes sur la copie. */
    public static void calm(LivingEntity entity) {
        if (entity instanceof net.minecraft.world.entity.monster.warden.Warden warden) {
            warden.attackAnimationState.stop();
            warden.sonicBoomAnimationState.stop();
        }
        if (entity instanceof IronGolemAnimationAccessor golem) {
            golem.planariumview$setAttackAnimationTick(0);
            golem.planariumview$setOfferFlowerTick(0);
        } else if (entity instanceof RavagerAnimationAccessor ravager) {
            ravager.planariumview$setAttackTick(0);
            ravager.planariumview$setStunnedTick(0);
        } else if (entity instanceof HoglinAnimationAccessor hoglin) {
            hoglin.planariumview$setAttackAnimationRemainingTicks(0);
        } else if (entity instanceof ZoglinAnimationAccessor zoglin) {
            zoglin.planariumview$setAttackAnimationRemainingTicks(0);
        } else if (entity instanceof SheepAnimationAccessor sheep) {
            sheep.planariumview$setEatAnimationTick(0);
        }
    }
}

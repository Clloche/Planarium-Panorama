package com.matthieu.planariumview.mixin.common;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.matthieu.planariumview.server.PlayerSoundCause;

import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;

/** Marks sound events synchronously emitted while a player-triggered sound call is running. */
@Mixin(ServerLevel.class)
public abstract class SoundCauseMixin {
    @WrapMethod(method = "playSeededSound(Lnet/minecraft/world/entity/player/Player;DDDLnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V")
    private void planariumview$positionSound(Player player, double x, double y, double z, Holder<SoundEvent> sound,
                                             SoundSource source, float volume, float pitch, long seed, Operation<Void> original) {
        PlayerSoundCause.play(player != null, () -> original.call(player, x, y, z, sound, source, volume, pitch, seed));
    }

    @WrapMethod(method = "playSeededSound(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V")
    private void planariumview$entitySound(Player player, Entity entity, Holder<SoundEvent> sound, SoundSource source,
                                           float volume, float pitch, long seed, Operation<Void> original) {
        PlayerSoundCause.play(player != null, () -> original.call(player, entity, sound, source, volume, pitch, seed));
    }
}

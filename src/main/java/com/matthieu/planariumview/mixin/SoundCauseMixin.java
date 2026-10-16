package com.matthieu.planariumview.mixin;

import com.matthieu.planariumview.PlayerSoundCause;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Marks sound events synchronously emitted while a player-triggered sound call is running. */
@Mixin(ServerLevel.class)
public abstract class SoundCauseMixin {
    @Inject(method = "playSeededSound(Lnet/minecraft/world/entity/player/Player;DDDLnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V",
            at = @At("HEAD"))
    private void planariumview$beginPositionSound(Player player, double x, double y, double z,
                                                  Holder<SoundEvent> sound, SoundSource source,
                                                  float volume, float pitch, long seed, CallbackInfo ci) {
        if (player != null) {
            PlayerSoundCause.beginPlayerSound();
        }
    }

    @Inject(method = "playSeededSound(Lnet/minecraft/world/entity/player/Player;DDDLnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V",
            at = @At("RETURN"))
    private void planariumview$endPositionSound(Player player, double x, double y, double z,
                                                Holder<SoundEvent> sound, SoundSource source,
                                                float volume, float pitch, long seed, CallbackInfo ci) {
        if (player != null) {
            PlayerSoundCause.endPlayerSound();
        }
    }

    @Inject(method = "playSeededSound(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V",
            at = @At("HEAD"))
    private void planariumview$beginEntitySound(Player player, Entity entity, Holder<SoundEvent> sound,
                                                SoundSource source, float volume, float pitch, long seed,
                                                CallbackInfo ci) {
        if (player != null) {
            PlayerSoundCause.beginPlayerSound();
        }
    }

    @Inject(method = "playSeededSound(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V",
            at = @At("RETURN"))
    private void planariumview$endEntitySound(Player player, Entity entity, Holder<SoundEvent> sound,
                                              SoundSource source, float volume, float pitch, long seed,
                                              CallbackInfo ci) {
        if (player != null) {
            PlayerSoundCause.endPlayerSound();
        }
    }
}

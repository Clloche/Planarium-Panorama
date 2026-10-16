package com.matthieu.planariumview.mixin;

import com.matthieu.planariumview.server.ServerView;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Certains sons passent par les "evenements de niveau" (bloc casse, distributeur, enclume...) au lieu de
 * PlayLevelSoundEvent : on les transmet aussi aux joueurs dans un bocal (voir ServerView.onLevelEvent).
 */
@Mixin(ServerLevel.class)
public abstract class LevelEventSoundMixin {

    @Inject(method = "levelEvent(Lnet/minecraft/world/entity/player/Player;ILnet/minecraft/core/BlockPos;I)V",
            at = @At("HEAD"), require = 0)
    private void planariumview$levelEventSound(Player player, int type, BlockPos pos, int data, CallbackInfo ci) {
        try {
            ServerView.onLevelEvent((ServerLevel) (Object) this, player, type, pos, data);
        } catch (Exception ignored) {
            // Un son en moins ne doit jamais casser le jeu.
        }
    }
}

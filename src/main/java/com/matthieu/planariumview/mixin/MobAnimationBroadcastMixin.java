package com.matthieu.planariumview.mixin;

import com.matthieu.planariumview.server.OutsideMobsServer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Both vanilla entry points delegate directly to ChunkMap; neither calls the other. */
@Mixin(ServerChunkCache.class)
public abstract class MobAnimationBroadcastMixin {
    @Inject(method = {"broadcast", "broadcastAndSend"}, at = @At("HEAD"))
    private void planariumview$mobAnimation(Entity entity, Packet<?> packet, CallbackInfo ci) {
        OutsideMobsServer.capture(entity, packet);
    }
}

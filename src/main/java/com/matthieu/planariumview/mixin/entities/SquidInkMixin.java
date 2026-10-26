package com.matthieu.planariumview.mixin.entities;

import com.matthieu.planariumview.server.entities.OutsideMobsServer;
import net.minecraft.world.entity.animal.Squid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Capture actual ink releases, including when no outside player receives the vanilla particle packets. */
@Mixin(Squid.class)
public abstract class SquidInkMixin {
    @Inject(method = "spawnInk", at = @At("HEAD"))
    private void planariumview$ink(CallbackInfo ci) {
        OutsideMobsServer.captureInk((Squid) (Object) this);
    }
}

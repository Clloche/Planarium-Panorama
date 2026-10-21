package com.matthieu.planariumview.mixin;

import com.hollingsworth.arsnouveau.common.block.tile.PlanariumTile;
import com.matthieu.planariumview.server.SubLevelServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observe nearby miniature viewers without depending on Sable or client subscription packets. */
@Mixin(value = PlanariumTile.class, remap = false)
public abstract class PlanariumTileSubLevelMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void planariumview$subLevelViewers(CallbackInfo ci) {
        SubLevelServer.seen((PlanariumTile) (Object) this);
    }
}

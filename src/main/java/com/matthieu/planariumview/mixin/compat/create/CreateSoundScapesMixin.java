package com.matthieu.planariumview.mixin.compat.create;

import com.matthieu.planariumview.client.sound.MachineSounds;
import com.matthieu.planariumview.compat.create.CreateCopies;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keep preview sources out of Create's real-world ambience groups. */
@Pseudo
@Mixin(targets = "com.simibubi.create.foundation.sound.SoundScapes", remap = false)
public abstract class CreateSoundScapesMixin {
    @Inject(method = "play", at = @At("HEAD"), cancellable = true, require = 0)
    private static void planariumview$preview(@Coerce Object group, BlockPos pos, float pitch, CallbackInfo ci) {
        if (CreateCopies.ticking()) {
            MachineSounds.group(group, pos, pitch);
            ci.cancel();
        }
    }
}

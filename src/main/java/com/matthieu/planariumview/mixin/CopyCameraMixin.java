package com.matthieu.planariumview.mixin;

import com.matthieu.planariumview.compat.CreateCopies;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Camera-facing Create parts use the coordinate system of the preview being drawn. */
@Mixin(Camera.class)
public abstract class CopyCameraMixin {
    @Inject(method = "getPosition", at = @At("HEAD"), cancellable = true)
    private void planariumview$previewCamera(CallbackInfoReturnable<Vec3> cir) {
        Vec3 camera = CreateCopies.camera();
        if (camera != null) cir.setReturnValue(camera);
    }
}

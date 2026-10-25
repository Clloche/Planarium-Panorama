package com.matthieu.planariumview.mixin;

import com.matthieu.planariumview.compat.create.CreateCopies;
import com.matthieu.planariumview.client.scenery.OutsideFluidView;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Camera-facing Create parts use the coordinate system of the preview being drawn. */
@Mixin(Camera.class)
public abstract class CopyCameraMixin {
    @Inject(method = "getFluidInCamera", at = @At("RETURN"), cancellable = true)
    private void planariumview$outsideFluid(CallbackInfoReturnable<FogType> cir) {
        Camera self = (Camera) (Object) this;
        if (self == Minecraft.getInstance().gameRenderer.getMainCamera() && cir.getReturnValue() == FogType.NONE) {
            FogType outside = OutsideFluidView.visualFog(self);
            if (outside != FogType.NONE) cir.setReturnValue(outside);
        }
    }

    @Inject(method = "getPosition", at = @At("HEAD"), cancellable = true)
    private void planariumview$previewCamera(CallbackInfoReturnable<Vec3> cir) {
        Vec3 camera = CreateCopies.camera();
        if (camera != null) cir.setReturnValue(camera);
    }
}

package com.matthieu.planariumview.mixin.compat;

import com.matthieu.planariumview.AmbientSoundsCompat;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** AmbientSounds : dans un bocal, les sons de la dimension du Planarium (l'Overworld, le Nether...). */
@Pseudo
@Mixin(targets = "team.creative.ambientsounds.engine.AmbientEngine", remap = false)
public abstract class AsEngineMixin {

    @Inject(method = "getDimension", at = @At("HEAD"), cancellable = true, require = 0)
    private void planariumview$outsideDimension(Level level, CallbackInfoReturnable<Object> cir) {
        Object dimension = AmbientSoundsCompat.dimension(this);
        if (dimension != null) {
            cir.setReturnValue(dimension);
        }
    }
}

package com.matthieu.planariumview.mixin.compat.dynamic_surroundings;

import com.matthieu.planariumview.client.scenery.SceneryReverb;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/** Indique a SceneryReverb quel son Dynamic Surroundings est en train de calculer (sur son fil). */
@Pseudo
@Mixin(targets = "org.orecruncher.dsurround.runtime.audio.SourceContext", remap = false)
public abstract class DsSourceContextMixin {

    @WrapMethod(method = "updateImpl", require = 0)
    private void planariumview$sceneryScope(Operation<Void> original) {
        try {
            try {
                SceneryReverb.begin(this);
            } catch (RuntimeException | LinkageError e) {
                SceneryReverb.end();
            }
            original.call();
        } finally {
            SceneryReverb.end();
        }
    }
}

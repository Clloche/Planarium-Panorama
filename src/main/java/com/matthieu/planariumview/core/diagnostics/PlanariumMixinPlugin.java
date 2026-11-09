package com.matthieu.planariumview.core.diagnostics;

import com.matthieu.planariumview.integration.bootstrap.OptionalMixinSupport;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.io.IOException;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Selection des compatibilites optionnelles, inventaire du bilan et installation du controle apres injection.
 */
public final class PlanariumMixinPlugin implements IMixinConfigPlugin {
    private final Map<String, OptionalMixinSupport.Result> supported = new HashMap<>();

    @Override
    public void onLoad(String mixinPackage) {
        MixinHealthExtension.install();
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    /** Les controles precedents sont limites aux integrations optionnelles ; les mixins essentiels restent obligatoires. */
    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        OptionalMixinSupport.Result result = OptionalMixinSupport.isOptional(mixinClassName)
                ? supported.computeIfAbsent(mixinClassName + "|" + targetClassName,
                    key -> OptionalMixinSupport.inspect(targetClassName, mixinClassName))
                : new OptionalMixinSupport.Result(OptionalMixinSupport.Status.SUPPORTED, null);
        MixinHealth.State initial = switch (result.status()) {
            case SUPPORTED -> MixinHealth.State.PENDING;
            case ABSENT -> MixinHealth.State.NOT_APPLICABLE;
            case INCOMPATIBLE -> MixinHealth.State.DISABLED;
        };
        try {
            ClassNode mixin = OptionalMixinSupport.read(mixinClassName);
            if (mixin == null) throw new IOException("Mixin illisible : " + mixinClassName);
            MixinHealth.register(mixinClassName, targetClassName, mixin, initial);
        } catch (Throwable t) {
            MixinHealth.unavailable("inventaire : " + t);
        }
        if (initial == MixinHealth.State.DISABLED) {
            MixinHealth.disabled(mixinClassName, targetClassName, result.reason());
        }
        return initial == MixinHealth.State.PENDING;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}

package com.matthieu.planariumview.diagnostics;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * Plugin des deux configurations de mixins : il ne change rien a ce qui est applique. Il fait seulement l'inventaire du
 * bilan des mixins (voir MixinHealth), avant le chargement des cibles, et installe l'extension qui verifie les classes
 * finales (MixinHealthExtension).
 */
public final class PlanariumMixinPlugin implements IMixinConfigPlugin {

    @Override
    public void onLoad(String mixinPackage) {
        MixinHealthExtension.install();
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    /**
     * Toujours vrai : Mixin ecarte deja lui-meme les cibles absentes. Une compatibilite dont le mod n'est pas installe
     * (classe cible introuvable) est indiquee comme « non applicable ».
     */
    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        try {
            boolean compat = mixinClassName.startsWith(MixinHealth.COMPAT_PACKAGE);
            boolean applicable = !compat || read(targetClassName) != null;
            ClassNode mixin = read(mixinClassName);
            if (mixin == null) throw new IOException("Mixin illisible : " + mixinClassName);
            MixinHealth.register(mixinClassName, targetClassName, mixin, applicable);
        } catch (Throwable t) {
            MixinHealth.unavailable("inventaire : " + t);
        }
        return true;
    }

    /** Bytecode sans definir la classe. ModLauncher exige runTransformers=true, comme Mixin lui-meme. */
    private static ClassNode read(String name) throws IOException {
        try {
            return MixinService.getService().getBytecodeProvider().getClassNode(name.replace('/', '.'), true);
        } catch (ClassNotFoundException e) {
            return null;
        }
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

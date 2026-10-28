package com.matthieu.planariumview.diagnostics;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.mixin.transformer.ext.Extensions;
import org.spongepowered.asm.mixin.transformer.ext.IExtension;
import org.spongepowered.asm.mixin.transformer.ext.ITargetClassContext;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Extension de Mixin qui recoit chaque classe transformee dans son etat final ({@code export}), apres les extensions de
 * MixinExtras (finalisation de {@code @WrapMethod}). Mixin appelle les extensions d'export meme sans
 * {@code mixin.debug.export} : cette option ne decide que de l'ecriture des fichiers par son propre exporteur.
 */
public final class MixinHealthExtension implements IExtension {
    private static boolean attempted;

    private MixinHealthExtension() {
    }

    /**
     * Une seule fois pour les deux configurations. Les extensions actives sont choisies a la selection de Mixin : comme
     * MixinExtras, on s'ajoute aussi a la liste deja choisie (sinon l'extension ne serait active qu'au prochain choix).
     */
    @SuppressWarnings("unchecked")
    static synchronized void install() {
        if (attempted) return;
        attempted = true;
        try {
            Object transformer = MixinEnvironment.getCurrentEnvironment().getActiveTransformer();
            if (!(transformer instanceof IMixinTransformer mixinTransformer)
                    || !(mixinTransformer.getExtensions() instanceof Extensions extensions)) {
                MixinHealth.unavailable("transformeur Mixin introuvable");
                return;
            }
            MixinHealthExtension extension = new MixinHealthExtension();
            extensions.add(extension);
            Field activeField = Extensions.class.getDeclaredField("activeExtensions");
            activeField.setAccessible(true);
            List<IExtension> active = (List<IExtension>) activeField.get(extensions);
            if (active == null || !active.contains(extension)) {
                List<IExtension> next = active == null ? new ArrayList<>() : new ArrayList<>(active);
                next.add(extension);
                activeField.set(extensions, Collections.unmodifiableList(next));
            }
            MixinHealth.installed();
        } catch (Throwable t) {
            MixinHealth.unavailable("extension Mixin : " + t);
        }
    }

    @Override
    public boolean checkActive(MixinEnvironment environment) {
        return true;
    }

    @Override
    public void preApply(ITargetClassContext context) {
    }

    @Override
    public void postApply(ITargetClassContext context) {
    }

    @Override
    public void export(MixinEnvironment env, String name, boolean force, ClassNode classNode) {
        try {
            MixinHealth.check(name, classNode);
        } catch (MixinHealth.StrictFailure strict) {
            throw strict;
        } catch (Throwable t) {
            MixinHealth.unavailable("verification : " + t);
        }
    }
}

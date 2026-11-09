package com.matthieu.planariumview.integration.bootstrap;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.service.MixinService;

import java.io.IOException;

/** Early selection of optional mixins, independent of Minecraft, ModList and external mod initialization. */
public final class OptionalMixinSupport {
    public enum Status { SUPPORTED, ABSENT, INCOMPATIBLE }
    public record Result(Status status, String reason) {}

    private static final String PACKAGE = "com.matthieu.planariumview.mixin.integration.";

    private OptionalMixinSupport() {}

    public static boolean isOptional(String mixin) {
        // Ars Nouveau is a required dependency: its hooks must never be silently switched off.
        return mixin.startsWith(PACKAGE) && !mixin.startsWith(PACKAGE + "ars_nouveau.");
    }

    public static Result inspect(String target, String mixin) {
        try {
            ClassNode node = read(target);
            if (node == null) return new Result(Status.ABSENT, "classe cible absente");
            return inspect(mixin, node);
        } catch (IOException | RuntimeException | LinkageError failure) {
            return new Result(Status.INCOMPATIBLE, "bytecode cible illisible : " + failure);
        }
    }

    public static Result inspect(String mixin, ClassNode target) {
        String reason = OptionalMixinLayouts.incompatibility(mixin, target);
        return new Result(reason == null ? Status.SUPPORTED : Status.INCOMPATIBLE, reason);
    }

    public static ClassNode read(String name) throws IOException {
        try {
            // NeoForge/ModLauncher requires transformed bytecode, as does Mixin itself.
            return MixinService.getService().getBytecodeProvider().getClassNode(name.replace('/', '.'), true);
        } catch (ClassNotFoundException absent) {
            return null;
        }
    }
}

package com.matthieu.planariumview.diagnostics;

import org.objectweb.asm.Handle;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bilan des mixins : verifie que chaque crochet ({@code @Inject}, {@code @Redirect}, {@code @WrapOperation}...) a
 * vraiment ete injecte dans chacune de ses cibles. Avec {@code require = 0}, une compatibilite brisee par la mise a jour
 * d'un mod ne fait sinon aucun bruit.
 *
 * <p>L'inventaire des attentes est fait avant le chargement des cibles (PlanariumMixinPlugin, en lisant nos propres
 * mixins) ; la verification se fait sur la classe finale, apres toutes les extensions de Mixin et de MixinExtras
 * (MixinHealthExtension.export). Une injection compte si la classe finale appelle au moins une fois la methode du
 * crochet fusionnee depuis ce mixin (annotation MixinMerged).
 *
 * <p>Pas de classe de Minecraft ni de NeoForge ici : cette classe est chargee pendant la preparation des mixins.
 * Le rapport (versions des mods, evenements) est dans MixinHealthReport.
 */
public final class MixinHealth {
    private static final Logger LOGGER = LoggerFactory.getLogger("planariumview");
    static final String COMPAT_PACKAGE = "com.matthieu.planariumview.mixin.compat.";
    static final String CORE = "core";
    private static final String MIXIN_MERGED = "Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;";
    private static final String EXPECT_IN = "Lcom/matthieu/planariumview/diagnostics/ExpectIn;";
    private static final String ALTERNATIVE = "Lcom/matthieu/planariumview/diagnostics/Alternative;";
    private static final Set<String> INJECTORS = Set.of(
            "Lorg/spongepowered/asm/mixin/injection/Inject;",
            "Lorg/spongepowered/asm/mixin/injection/Redirect;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyArg;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyArgs;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyConstant;",
            "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;",
            "Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;",
            "Lcom/llamalad7/mixinextras/injector/ModifyReceiver;",
            "Lcom/llamalad7/mixinextras/injector/WrapWithCondition;",
            "Lcom/llamalad7/mixinextras/injector/v2/WrapWithCondition;",
            "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;",
            "Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;");
    /** -Dplanariumview.strictMixins=true (developpement) : un echec confirme arrete le jeu. */
    static final boolean STRICT = Boolean.getBoolean("planariumview.strictMixins");

    public enum State { PENDING, OK, FAILED, NOT_APPLICABLE }

    /** Une injection attendue : un crochet d'un mixin dans une de ses cibles. */
    private static final class Unit {
        final String mixin;
        final String handler;
        final String descriptor;
        final String target;
        final String group;
        final String alternative;
        State state = State.PENDING;

        Unit(String mixin, String handler, String descriptor, String target, String group, String alternative, boolean applicable) {
            this.mixin = mixin;
            this.handler = handler;
            this.descriptor = descriptor;
            this.target = target;
            this.group = group;
            this.alternative = alternative;
            if (!applicable) state = State.NOT_APPLICABLE;
        }

        String requirementKey() {
            return alternative != null ? "alt:" + alternative : "unit:" + mixin + "#" + handler + descriptor + "@" + target;
        }

        String label() {
            String name = simpleName(mixin) + (handler == null ? "" : "." + handler.replace("planariumview$", ""));
            return alternative != null ? name + " (variantes)" : name + " dans " + simpleName(target);
        }
    }

    /** Une attente du bilan : une injection seule, ou un groupe de variantes dont une seule doit reussir. */
    public record Requirement(String group, String label, State state) {
    }

    private static final List<Unit> UNITS = new ArrayList<>();
    private static final Map<String, List<Unit>> BY_TARGET = new HashMap<>();
    private static final Set<String> REGISTERED = new HashSet<>();
    private static final Set<String> WARNED = new HashSet<>();
    /** Cible commune aux deux cotes, chargee pendant le demarrage et portant nos injections principales. */
    private static volatile boolean witnessSeen;
    private static volatile boolean installed;
    private static volatile String unavailable;

    private MixinHealth() {
    }

    /** L'extension qui verifie les classes finales est active (voir MixinHealthExtension.install). */
    static void installed() {
        installed = true;
    }

    /** Le bilan ne peut pas se faire ; une seule ligne, meme si le probleme apparait apres le premier rapport. */
    static synchronized void unavailable(String reason) {
        if (unavailable == null) {
            unavailable = reason;
            LOGGER.warn("[Planarium Panorama] Bilan des mixins indisponible ({})", reason);
        }
    }

    /** Raison pour laquelle le bilan est indisponible, ou null s'il fonctionne. */
    public static String unavailableReason() {
        if (unavailable != null) return unavailable;
        if (!installed) return "extension Mixin non installee";
        if (!witnessSeen) return "classe temoin ServerLevel non observee par l'extension";
        return null;
    }

    /** Une cible de ce mixin : ses crochets y sont attendus, ou non applicables si la cible est absente. */
    static synchronized void register(String mixinClass, String targetClass, ClassNode mixinNode, boolean applicable) {
        String target = dotted(targetClass);
        if (!REGISTERED.add(mixinClass + "|" + target)) return;
        String group = groupOf(mixinClass);
        if (mixinNode == null || mixinNode.methods == null) {
            unavailable("inventaire illisible : " + mixinClass);
            return;
        }
        for (MethodNode method : mixinNode.methods) {
            if (!injector(method)) continue;
            List<String> expectIn = strings(annotation(method, EXPECT_IN));
            if (expectIn != null && !matches(target, expectIn)) continue;
            String alternative = string(annotation(method, ALTERNATIVE));
            add(new Unit(mixinClass, method.name, method.desc, target, group,
                    alternative == null ? null : group + "/" + alternative, applicable));
        }
    }

    private static void add(Unit unit) {
        UNITS.add(unit);
        BY_TARGET.computeIfAbsent(unit.target, key -> new ArrayList<>()).add(unit);
    }

    /** Classe finale, apres toutes les transformations de Mixin (voir MixinHealthExtension.export). */
    static void check(String name, ClassNode node) {
        if (node == null || node.methods == null) return;
        List<Unit> units;
        synchronized (MixinHealth.class) {
            List<Unit> known = BY_TARGET.get(dotted(name));
            if (known == null) return;
            units = new ArrayList<>(known);
        }
        if (dotted(name).equals("net.minecraft.server.level.ServerLevel")) witnessSeen = true;
        Map<String, Integer> calls = calls(node);
        List<String> confirmed = new ArrayList<>();
        synchronized (MixinHealth.class) {
            for (Unit unit : units) {
                if (unit.state == State.NOT_APPLICABLE) continue;
                int sites = 0;
                for (MethodNode method : node.methods) {
                    if (!unit.mixin.equals(mergedFrom(method))) continue;
                    if (!method.name.equals(unit.handler) && !method.name.endsWith("$" + unit.handler)) continue;
                    if (!method.desc.equals(unit.descriptor)) continue;
                    sites += calls.getOrDefault(method.name + method.desc, 0);
                }
                unit.state = sites > 0 ? State.OK : State.FAILED;
            }
            for (Unit unit : units) {
                if (unit.state != State.FAILED) continue;
                String key = unit.requirementKey();
                if (state(key) == State.FAILED && WARNED.add(key)) {
                    LOGGER.warn("[Planarium Panorama] Mixin inactif ({}) : {}", groupOf(unit.mixin), unit.label());
                    confirmed.add(unit.label());
                }
            }
        }
        if (STRICT && !confirmed.isEmpty()) {
            String message = "[Planarium Panorama] Mixins inactifs (planariumview.strictMixins) : " + confirmed;
            // Le rapport de crash d'un autre mod peut masquer l'exception si sa propre initialisation echoue.
            LOGGER.error(message);
            throw new StrictFailure(message);
        }
    }

    /** Echec confirme en mode strict : il doit arreter le chargement de la classe. */
    static final class StrictFailure extends IllegalStateException {
        StrictFailure(String message) {
            super(message);
        }
    }

    /** Toutes les attentes, dans l'ordre de l'inventaire. */
    public static synchronized List<Requirement> requirements() {
        Map<String, Requirement> result = new LinkedHashMap<>();
        for (Unit unit : UNITS) {
            String key = unit.requirementKey();
            if (!result.containsKey(key)) result.put(key, new Requirement(unit.group, unit.label(), state(key)));
        }
        return new ArrayList<>(result.values());
    }

    /** Une variante reussie suffit ; tant qu'une variante n'est pas chargee, le groupe attend. */
    private static State state(String key) {
        boolean pending = false;
        boolean any = false;
        for (Unit unit : UNITS) {
            if (!unit.requirementKey().equals(key)) continue;
            if (unit.state == State.NOT_APPLICABLE) continue;
            any = true;
            if (unit.state == State.OK) return State.OK;
            if (unit.state == State.PENDING) pending = true;
        }
        return !any ? State.NOT_APPLICABLE : pending ? State.PENDING : State.FAILED;
    }

    /** Appels (et references de lambdas) vers chaque methode de la classe, par nom + descripteur. */
    private static Map<String, Integer> calls(ClassNode node) {
        Map<String, Integer> calls = new HashMap<>();
        for (MethodNode method : node.methods) {
            if (method.instructions == null) continue;
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof MethodInsnNode call && call.owner.equals(node.name)) {
                    calls.merge(call.name + call.desc, 1, Integer::sum);
                } else if (insn instanceof InvokeDynamicInsnNode indy && indy.bsmArgs != null) {
                    for (Object argument : indy.bsmArgs) {
                        if (argument instanceof Handle handle && handle.getOwner().equals(node.name)) {
                            calls.merge(handle.getName() + handle.getDesc(), 1, Integer::sum);
                        }
                    }
                }
            }
        }
        return calls;
    }

    /** Mixin d'ou vient cette methode fusionnee, ou null. */
    private static String mergedFrom(MethodNode method) {
        AnnotationNode merged = annotation(method, MIXIN_MERGED);
        if (merged == null || merged.values == null) return null;
        for (int i = 0; i + 1 < merged.values.size(); i += 2) {
            if ("mixin".equals(merged.values.get(i)) && merged.values.get(i + 1) instanceof String mixin) {
                return dotted(mixin);
            }
        }
        return null;
    }

    private static boolean injector(MethodNode method) {
        for (List<AnnotationNode> list : annotationLists(method)) {
            for (AnnotationNode annotation : list) {
                if (INJECTORS.contains(annotation.desc)) return true;
            }
        }
        return false;
    }

    private static AnnotationNode annotation(MethodNode method, String desc) {
        for (List<AnnotationNode> list : annotationLists(method)) {
            for (AnnotationNode annotation : list) {
                if (desc.equals(annotation.desc)) return annotation;
            }
        }
        return null;
    }

    private static List<List<AnnotationNode>> annotationLists(MethodNode method) {
        List<List<AnnotationNode>> lists = new ArrayList<>(2);
        if (method.visibleAnnotations != null) lists.add(method.visibleAnnotations);
        if (method.invisibleAnnotations != null) lists.add(method.invisibleAnnotations);
        return lists;
    }

    /** Valeur {@code value} (un texte) d'une annotation, ou null. */
    private static String string(AnnotationNode annotation) {
        Object value = value(annotation);
        return value instanceof String text ? text : null;
    }

    /** Valeur {@code value} (un tableau de textes) d'une annotation, ou null. */
    private static List<String> strings(AnnotationNode annotation) {
        Object value = value(annotation);
        if (value instanceof String text) return List.of(text);
        if (!(value instanceof List<?> list)) return null;
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof String text) result.add(text);
        }
        return result;
    }

    private static Object value(AnnotationNode annotation) {
        if (annotation == null || annotation.values == null) return null;
        for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
            if ("value".equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        }
        return null;
    }

    private static boolean matches(String target, List<String> names) {
        for (String name : names) {
            String wanted = dotted(name);
            if (target.equals(wanted) || target.endsWith("." + wanted) || target.endsWith("$" + wanted)) return true;
        }
        return false;
    }

    /** Dossier de compatibilite du mixin ({@code dynamic_surroundings}...), ou "core". */
    static String groupOf(String mixinClass) {
        if (!mixinClass.startsWith(COMPAT_PACKAGE)) return CORE;
        String rest = mixinClass.substring(COMPAT_PACKAGE.length());
        int dot = rest.indexOf('.');
        return dot < 0 ? CORE : rest.substring(0, dot);
    }

    private static String dotted(String name) {
        return name.replace('/', '.');
    }

    private static String simpleName(String name) {
        String dottedName = dotted(name);
        return dottedName.substring(dottedName.lastIndexOf('.') + 1);
    }
}

package com.matthieu.planariumview.core.diagnostics;

import com.google.gson.JsonParser;
import com.matthieu.planariumview.integration.bootstrap.OptionalMixinSupport;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.service.*;

import java.io.InputStream;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/** Bytecode-only checks: no game, external mod initialization or rendering context. */
public final class MixinCompatibilityChecks {
    private static final String PREFIX = "com.matthieu.planariumview.mixin.integration.";
    private static final Map<String, ClassNode> FIXTURES = new HashMap<>();

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static ClassNode resource(String name) throws Exception {
        try (InputStream in = MixinCompatibilityChecks.class.getClassLoader()
                .getResourceAsStream(name.replace('.', '/') + ".class")) {
            if (in == null) throw new ClassNotFoundException(name);
            ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, 0);
            return node;
        }
    }

    /** Supply bytecode lookup only; the production plugin still makes all selection and inventory decisions. */
    private static void installBytecodeService() throws Exception {
        Method allocate = Class.forName("com.matthieu.planariumview.MobAnimationIntegrationChecks")
                .getDeclaredMethod("fixture", Class.class);
        allocate.setAccessible(true);
        Object singleton = allocate.invoke(null, MixinService.class);
        Object bytecode = Proxy.newProxyInstance(MixinCompatibilityChecks.class.getClassLoader(),
                new Class<?>[]{IClassBytecodeProvider.class}, (p, method, args) -> {
                    String name = ((String) args[0]).replace('/', '.');
                    require(args.length >= 2 && Boolean.TRUE.equals(args[1]), "ModLauncher bytecode must run transformers");
                    return FIXTURES.containsKey(name) ? FIXTURES.get(name) : resource(name);
                });
        Object service = Proxy.newProxyInstance(MixinCompatibilityChecks.class.getClassLoader(),
                new Class<?>[]{IMixinService.class}, (p, method, args) -> {
                    if (method.getName().equals("getBytecodeProvider")) return bytecode;
                    throw new UnsupportedOperationException(method.toString());
                });
        Field serviceField = MixinService.class.getDeclaredField("service");
        serviceField.setAccessible(true);
        serviceField.set(singleton, service);
        Field instance = MixinService.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, singleton);
    }

    private static MixinHealth.State state(String label) {
        return MixinHealth.requirements().stream().filter(r -> r.label().equals(label))
                .findFirst().orElseThrow().state();
    }

    private static void selectionAndFinalCheck() throws Exception {
        installBytecodeService();
        require(!OptionalMixinSupport.isOptional(PREFIX + "ars_nouveau.PlanariumRendererMixin"), "Ars is required");
        require(!OptionalMixinSupport.isOptional("com.matthieu.planariumview.mixin.client.ParticleLightMixin"), "Core is required");
        String mixin = PREFIX + "dynamic_surroundings.DsPointLightsMixin";
        ClassNode good = new ClassNode();
        good.name = "fixtures/GoodLights";
        good.methods.add(new MethodNode(Opcodes.ACC_STATIC, "add", "(DDDFFFFF)V", null, null));
        ClassNode changed = new ClassNode();
        changed.name = "fixtures/ChangedLights";
        changed.methods.add(new MethodNode(0, "add", "(DDDFFFFF)V", null, null));
        FIXTURES.put("fixtures.GoodLights", good);
        FIXTURES.put("fixtures.ChangedLights", changed);
        PlanariumMixinPlugin plugin = new PlanariumMixinPlugin();
        require(plugin.shouldApplyMixin("fixtures.GoodLights", mixin), "Supported static hook rejected");
        require(!plugin.shouldApplyMixin("fixtures.ChangedLights", mixin), "Static mismatch accepted");
        require(!plugin.shouldApplyMixin("fixtures.AbsentLights", mixin), "Absent target accepted");
        require(plugin.shouldApplyMixin("fixtures.GoodLights", mixin), "Cached decision changed");
        require(MixinHealth.requirements().size() == 6, "Repeated selection duplicated inventory");
        require(state("DsPointLightsMixin.skip dans GoodLights") == MixinHealth.State.PENDING, "Unloaded is not failed");
        require(state("DsPointLightsMixin.skip dans ChangedLights") == MixinHealth.State.DISABLED, "Refusal lost");
        require(state("DsPointLightsMixin.skip dans AbsentLights") == MixinHealth.State.NOT_APPLICABLE, "Absence lost");
        MixinHealth.check(changed.name, changed);
        require(state("DsPointLightsMixin.skip dans ChangedLights") == MixinHealth.State.DISABLED, "Export overwrote refusal");

        // A merged handler alone is insufficient: the final class must reference it.
        ClassNode finalNode = new ClassNode();
        finalNode.name = good.name;
        ClassNode source = resource(mixin);
        MethodNode handler = source.methods.stream().filter(m -> m.name.equals("planariumview$skip")).findFirst().orElseThrow();
        MethodNode merged = new MethodNode(Opcodes.ACC_STATIC, "handler$test$" + handler.name, handler.desc, null, null);
        merged.visibleAnnotations = new ArrayList<>();
        AnnotationNode annotation = new AnnotationNode("Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;");
        annotation.values = new ArrayList<>(List.of("mixin", mixin));
        merged.visibleAnnotations.add(annotation);
        finalNode.methods.add(merged);
        MethodNode caller = new MethodNode(Opcodes.ACC_STATIC, "add", "(DDDFFFFF)V", null, null);
        caller.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, finalNode.name, merged.name, merged.desc, false));
        finalNode.methods.add(caller);
        MixinHealth.check(finalNode.name, finalNode);
        require(state("DsPointLightsMixin.skip dans GoodLights") == MixinHealth.State.OK, "Final handler call not confirmed");
        require(state("DsPointLightsMixin.radius dans GoodLights") == MixinHealth.State.FAILED, "Missing injection not detected");

        // Same mixin, different target: never reuse a positive decision for another target.
        require(!plugin.shouldApplyMixin("fixtures.ChangedLights", mixin), "Target-specific cache broken");
        changed.methods.getFirst().access = Opcodes.ACC_STATIC;
        changed.methods.getFirst().desc = "(DDDFFFF)V";
        require(OptionalMixinSupport.inspect(mixin, changed).status() == OptionalMixinSupport.Status.INCOMPATIBLE,
                "Changed descriptor accepted");
        ClassNode required = resource(PREFIX + "ars_nouveau.PlanariumTileSubLevelMixin");
        require(required != null && plugin.shouldApplyMixin("fixtures.MissingRequired", PREFIX + "ars_nouveau.PlanariumTileSubLevelMixin"),
                "Required integration silently skipped");
    }

    private static void alternatives() {
        String group = PREFIX + "fixture.";
        ClassNode mixin = new ClassNode();
        MethodNode handler = new MethodNode(0, "planariumview$variant", "()V", null, null);
        AnnotationNode inject = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Inject;");
        AnnotationNode alternative = new AnnotationNode("Lcom/matthieu/planariumview/core/diagnostics/Alternative;");
        alternative.values = new ArrayList<>(List.of("value", "variant"));
        handler.visibleAnnotations = new ArrayList<>(List.of(inject, alternative));
        mixin.methods.add(handler);
        MixinHealth.register(group + "Absent", "fixtures.Absent", mixin, MixinHealth.State.NOT_APPLICABLE);
        MixinHealth.register(group + "Disabled", "fixtures.Disabled", mixin, MixinHealth.State.DISABLED);
        require(state("Absent.variant (variantes)") == MixinHealth.State.DISABLED, "Disabled variant reported as absent/failed");
        MixinHealth.register(group + "Pending", "fixtures.Pending", mixin, MixinHealth.State.PENDING);
        require(state("Absent.variant (variantes)") == MixinHealth.State.PENDING, "Variant not yet loaded must stay pending");
        ClassNode node = new ClassNode();
        node.name = "fixtures/Pending";
        MixinHealth.check(node.name, node);
        require(state("Absent.variant (variantes)") == MixinHealth.State.FAILED, "Failed active variant was hidden by disabled variant");
    }

    private static void strictSelection() throws Exception {
        installBytecodeService();
        ClassNode target = new ClassNode();
        target.name = "fixtures/StrictLights";
        FIXTURES.put("fixtures.StrictLights", target);
        try {
            new PlanariumMixinPlugin().shouldApplyMixin("fixtures.StrictLights", PREFIX + "dynamic_surroundings.DsPointLightsMixin");
            throw new AssertionError("Strict selection did not stop on an incompatible present target");
        } catch (MixinHealth.StrictFailure expected) {
            require(expected.getMessage().contains("add (DDDFFFFF)V static"), "Strict failure lacks the violated contract");
        }
        require(new PlanariumMixinPlugin().shouldApplyMixin("fixtures.Required", PREFIX + "ars_nouveau.PlanariumTileSubLevelMixin"),
                "Strict mode switched off a required mixin");
        System.out.println("Strict mixin compatibility checks passed.");
    }

    private static void realYaclContracts() throws Exception {
        Map<String, String> types = Map.of(
                "YaclOptionListMixin", "dev.isxander.yacl3.gui.OptionListWidget",
                "YaclGroupHeaderMixin", "dev.isxander.yacl3.gui.OptionListWidget$GroupSeparatorEntry",
                "YaclPendingMixin", "dev.isxander.yacl3.gui.YACLScreen",
                "YaclUndoButtonMixin", "dev.isxander.yacl3.gui.YACLScreen$CategoryTab");
        for (var entry : types.entrySet()) {
            String mixin = PREFIX + "yacl." + entry.getKey();
            ClassNode target = resource(entry.getValue());
            require(OptionalMixinSupport.inspect(mixin, target).status() == OptionalMixinSupport.Status.SUPPORTED,
                    mixin + ": " + OptionalMixinSupport.inspect(mixin, target).reason());
            ClassNode source = resource(mixin);
            for (FieldNode shadow : source.fields) {
                if (shadow.visibleAnnotations == null || shadow.visibleAnnotations.stream()
                        .noneMatch(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/Shadow;"))) continue;
                FieldNode field = target.fields.stream().filter(f -> f.name.equals(shadow.name)).findFirst().orElseThrow();
                String original = field.desc;
                field.desc = "Ljava/lang/Object;";
                require(OptionalMixinSupport.inspect(mixin, target).status() == OptionalMixinSupport.Status.INCOMPATIBLE,
                        "Unprotected shadow: " + mixin + "." + shadow.name);
                field.desc = original;
            }
        }
    }

    private static void configurationPaths(Path root) throws Exception {
        Set<String> seen = new HashSet<>();
        for (String file : List.of("planariumview.mixins.json", "planariumview.compat.mixins.json")) {
            var config = JsonParser.parseString(Files.readString(root.resolve("src/main/resources/" + file))).getAsJsonObject();
            require(resource(config.get("plugin").getAsString()) != null, "Missing plugin");
            String pkg = config.get("package").getAsString();
            for (String side : List.of("mixins", "client")) {
                for (var entry : config.getAsJsonArray(side)) {
                    String name = pkg + "." + entry.getAsString();
                    require(seen.add(name), "Duplicate mixin " + name);
                    ClassNode node = resource(name);
                    require(node.name.equals(name.replace('.', '/')), "Package/path mismatch " + name);
                    if (side.equals("mixins")) require(!name.contains(".mixin.client."), "Client mixin registered on server");
                }
            }
        }
    }

    /** Optional developer check against installed reference jars, without adding them as build dependencies. */
    private static void installedContracts(Path root) throws Exception {
        Path mods = root.resolve("run/mods");
        if (!Files.isDirectory(mods)) return;
        Map<String, String> targets = Map.of(
                "ClColorSectionMixin", "xyz/atmerek/contraptionlights/light/color/ColorLightEngine",
                "ClColorFeatureMixin", "xyz/atmerek/contraptionlights/light/color/ColorLightFeature",
                "FwLightDataCollectorMixin", "dev/engine_room/flywheel/backend/engine/LightDataCollector",
                "DsDimensionInfoMixin", "org/orecruncher/dsurround/config/DimensionInfo",
                "DsChunkViewMixin", "org/orecruncher/dsurround/lib/block/ChunkCachingView",
                "DsPointLightsMixin", "org/orecruncher/dsurround/effects/particles/PointLights",
                "PfPlacementMixin", "pigcart/puddleflood/ClientBlockPlacementUtil");
        try (var files = Files.list(mods)) {
            for (Path jar : files.filter(p -> p.toString().endsWith(".jar")).toList()) {
                try (ZipFile zip = new ZipFile(jar.toFile())) {
                    for (var target : targets.entrySet()) {
                        var entry = zip.getEntry(target.getValue() + ".class");
                        if (entry == null) continue;
                        ClassNode node = new ClassNode();
                        try (InputStream in = zip.getInputStream(entry)) { new ClassReader(in).accept(node, 0); }
                        String reason = com.matthieu.planariumview.integration.bootstrap.OptionalMixinLayouts
                                .incompatibility(target.getKey(), node);
                        require(reason == null, jar.getFileName() + " / " + target.getKey() + ": " + reason);
                    }
                    for (var entry : Collections.list(zip.entries())) {
                        if (!entry.getName().contains("flywheel") || !entry.getName().endsWith(".jar")) continue;
                        try (ZipInputStream nested = new ZipInputStream(zip.getInputStream(entry))) {
                            for (var part = nested.getNextEntry(); part != null; part = nested.getNextEntry()) {
                                if (!part.getName().equals(targets.get("FwLightDataCollectorMixin") + ".class")) continue;
                                ClassNode node = new ClassNode();
                                new ClassReader(nested.readAllBytes()).accept(node, 0);
                                String reason = com.matthieu.planariumview.integration.bootstrap.OptionalMixinLayouts
                                        .incompatibility("FwLightDataCollectorMixin", node);
                                require(reason == null, entry.getName() + ": " + reason);
                            }
                        }
                    }
                }
            }
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 1 && args[1].equals("--strict")) { strictSelection(); return; }
        Path root = Path.of(args[0]);
        configurationPaths(root);
        realYaclContracts();
        installedContracts(root);
        selectionAndFinalCheck();
        alternatives();
        System.out.println("Mixin compatibility checks passed (layout contracts, selection, cache, inventory, final bytecode).");
    }
}

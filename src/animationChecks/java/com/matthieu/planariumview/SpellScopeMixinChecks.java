package com.matthieu.planariumview;

import com.matthieu.planariumview.client.scenery.SceneryParticles;
import net.minecraft.client.particle.*;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.AABB;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.service.*;
import java.io.*;
import java.lang.reflect.*;
import java.net.URL;
import java.util.*;

/** Real Mixin injection and vanilla SpellParticle constructor; the nearby scoping player is a fixed boundary. */
public final class SpellScopeMixinChecks {
    private static final class FactoryFailure extends Error {}
    private static final ClassLoader LOADER = SpellScopeMixinChecks.class.getClassLoader();
    private record Key(String name) implements IPropertyKey {}
    private static Object proxy(Class<?> type, InvocationHandler handler) {
        return Proxy.newProxyInstance(LOADER, new Class<?>[]{type}, handler);
    }
    private static byte[] bytes(String name) throws IOException {
        try (InputStream in = LOADER.getResourceAsStream(name.replace('.', '/') + ".class")) {
            if (in == null) throw new FileNotFoundException(name);
            return in.readAllBytes();
        }
    }
    private static Field field(Class<?> type, String name) throws Exception {
        Field f = type.getDeclaredField(name); f.setAccessible(true); return f;
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    /** Supply only class/resource lookup to the real Mixin engine, without starting ModLauncher or a window. */
    private static IMixinTransformer transformer() throws Exception {
        return transformer("planariumview.spell-scope-checks.mixins.json", node -> {});
    }

    static IMixinTransformer transformer(String config, java.util.function.Consumer<ClassNode> prepare) throws Exception {
        Method allocate = MobAnimationIntegrationChecks.class.getDeclaredMethod("fixture", Class.class);
        allocate.setAccessible(true);
        Object singleton = allocate.invoke(null, MixinService.class);
        Map<Object, Object> properties = new HashMap<>();
        Object propertyService = proxy(IGlobalPropertyService.class, (p, method, a) -> switch (method.getName()) {
            case "resolveKey" -> new Key((String) a[0]);
            case "setProperty" -> { properties.put(a[0], a[1]); yield null; }
            case "getProperty", "getPropertyString" -> properties.getOrDefault(a[0], a.length == 2 ? a[1] : null);
            default -> throw new UnsupportedOperationException(method.toString());
        });
        Object classProvider = proxy(IClassProvider.class, (p, method, a) -> switch (method.getName()) {
            case "getClassPath" -> new URL[0];
            case "findClass", "findAgentClass" -> Class.forName((String) a[0], a.length == 2 && (boolean) a[1], LOADER);
            default -> throw new UnsupportedOperationException(method.toString());
        });
        Object bytecode = proxy(IClassBytecodeProvider.class, (p, method, a) -> {
            ClassNode node = new ClassNode();
            new ClassReader(bytes((String) a[0])).accept(node, a.length == 3 ? (int) a[2] : 0);
            prepare.accept(node);
            return node;
        });
        Object tracker = proxy(IClassTracker.class, (p, method, a) -> switch (method.getName()) {
            case "isClassLoaded" -> false;
            case "getClassRestrictions" -> "";
            default -> null;
        });
        Object service = proxy(IMixinService.class, (p, method, a) -> switch (method.getName()) {
            case "getName" -> "SpellScopeChecks";
            case "isValid" -> true;
            case "getInitialPhase" -> MixinEnvironment.Phase.DEFAULT;
            case "getMinCompatibilityLevel", "getMaxCompatibilityLevel" -> MixinEnvironment.CompatibilityLevel.JAVA_21;
            case "getSideName" -> "CLIENT";
            case "getClassProvider" -> classProvider;
            case "getBytecodeProvider" -> bytecode;
            case "getClassTracker" -> tracker;
            case "getReEntranceLock" -> LOCK;
            case "getLogger" -> new org.spongepowered.asm.logging.LoggerAdapterDefault((String) a[0]);
            case "getPlatformAgents", "getMixinContainers" -> List.of();
            case "getPrimaryContainer" -> new org.spongepowered.asm.launch.platform.container.ContainerHandleVirtual("scope-checks");
            case "getResourceAsStream" -> LOADER.getResourceAsStream((String) a[0]);
            case "getTransformerProvider", "getAuditTrail", "prepare", "offer", "init", "beginPhase", "checkEnv" -> null;
            default -> throw new UnsupportedOperationException(method.toString());
        });
        field(MixinService.class, "service").set(singleton, service);
        field(MixinService.class, "propertyService").set(singleton, propertyService);
        field(MixinService.class, "instance").set(null, singleton);
        org.spongepowered.asm.launch.MixinBootstrap.init();
        MixinEnvironment.getDefaultEnvironment().setSide(MixinEnvironment.Side.CLIENT);
        Mixins.addConfiguration(config);
        Constructor<?> ctor = Class.forName("org.spongepowered.asm.mixin.transformer.MixinTransformer").getDeclaredConstructor();
        ctor.setAccessible(true); return (IMixinTransformer) ctor.newInstance();
    }
    private static final org.spongepowered.asm.util.ReEntranceLock LOCK = new org.spongepowered.asm.util.ReEntranceLock(1);

    private static Constructor<?> spellConstructor() throws Exception {
        String name = "net.minecraft.client.particle.SpellParticle";
        ClassNode node = new ClassNode(); new ClassReader(bytes(name)).accept(node, 0);
        for (MethodNode method : node.methods) {
            if (!method.name.equals("isCloseToScopingPlayer")) continue;
            // Only replace the external player/camera query. Constructor, alpha handling and mixin stay real.
            method.instructions.clear(); method.tryCatchBlocks.clear();
            if (method.localVariables != null) method.localVariables.clear();
            method.instructions.add(new InsnNode(Opcodes.ICONST_1));
            method.instructions.add(new InsnNode(Opcodes.IRETURN));
            method.maxStack = 1;
        }
        ClassWriter input = new ClassWriter(0); node.accept(input);
        byte[] transformed = transformer().transformClassBytes(name, name, input.toByteArray());
        ClassNode result = new ClassNode(); new ClassReader(transformed).accept(result, 0);
        require(result.methods.stream().anyMatch(m -> m.name.contains("planariumview$miniature")), "real production scope mixin was applied");
        Class<?> type = new ClassLoader(LOADER) { Class<?> define(byte[] data) { return defineClass(name, data, 0, data.length); } }.define(transformed);
        Constructor<?> ctor = type.getDeclaredConstructor(ClientLevel.class, double.class, double.class, double.class,
                double.class, double.class, double.class, SpriteSet.class);
        ctor.setAccessible(true); return ctor;
    }

    private static Particle construct(Constructor<?> ctor) {
        try {
            SpriteSet sprites = (SpriteSet) proxy(SpriteSet.class, (p, m, a) -> null);
            return (Particle) ctor.newInstance(null, 0, 0, 0, 0, 0, 0, sprites);
        } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
    }
    private static Particle createIn(SceneryParticles.Space space, Constructor<?> ctor, boolean fail) {
        Particle[] result = new Particle[1];
        SceneryParticles.spawn(space, new SceneryParticles.Made(pos -> {
            result[0] = construct(ctor);
            if (fail) throw new IllegalStateException("expected factory failure");
            return null; // Constructor is under test; no renderer or particle engine is required.
        }), 0, 0, 0, 0, 0, 0, 0);
        return result[0];
    }

    public static void main(String[] args) throws Exception {
        Constructor<?> ctor = spellConstructor();
        Field alpha = field(Particle.class, "alpha"), original = field(ctor.getDeclaringClass(), "originalAlpha");
        var mini = new SceneryParticles.Space(null, 1.0 / 32, 0, 0, 0, new AABB(0, 0, 0, 32, 32, 32), false, null);
        var normal = new SceneryParticles.Space(null, 1, 0, 0, 0, null, false, null);
        Particle regular = createIn(normal, ctor, false);
        require(alpha.getFloat(regular) == 0 && original.getFloat(regular) == 0, "ordinary spell particle still fades while scoping");
        Particle small = createIn(mini, ctor, false);
        require(alpha.getFloat(small) == 1 && original.getFloat(small) == 1, "miniature constructor keeps initial and original alpha");
        Particle failed = createIn(mini, ctor, true);
        require(original.getFloat(failed) == 1 && !SceneryParticles.miniature(regular), "factory failure restores creation context");
        require(alpha.getFloat(createIn(normal, ctor, false)) == 0, "miniature context does not leak to later ordinary particles");
        try {
            SceneryParticles.spawn(mini, new SceneryParticles.Made(pos -> {
                try {
                    require(alpha.getFloat(createIn(normal, ctor, false)) == 0, "nested ordinary factory keeps vanilla fading");
                    require(original.getFloat(construct(ctor)) == 1, "outer miniature context restored after nested factory");
                } catch (IllegalAccessException e) { throw new AssertionError(e); }
                throw new FactoryFailure();
            }), 0, 0, 0, 0, 0, 0, 0);
            throw new AssertionError("fatal factory failure should propagate");
        } catch (FactoryFailure expected) {
            require(!SceneryParticles.miniature(regular), "fatal factory failure also restores creation context");
        }
        System.out.println("PASS: real SpellParticle constructor with production mixin, scoping boundary, miniature factory and exception cleanup");
    }
}

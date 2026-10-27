package com.matthieu.planariumview;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Set;
import org.objectweb.asm.*;

/** Skip game bootstrap in fixtures; production methods, including vanilla animation methods, stay intact. */
public final class AnimationFixtureAgent {
    public static void premain(String arguments, Instrumentation instrumentation) {
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader loader, String name, Class<?> type, ProtectionDomain domain, byte[] bytes) {
                if (!Set.of("net/minecraft/world/entity/Entity", "net/minecraft/world/entity/LivingEntity",
                        "net/minecraft/world/entity/Mob",
                        "net/minecraft/world/entity/AgeableMob", "net/minecraft/world/entity/TamableAnimal",
                        "net/minecraft/world/entity/animal/Dolphin", "net/minecraft/world/entity/GlowSquid",
                        "net/minecraft/world/entity/animal/Chicken", "net/minecraft/world/entity/animal/Parrot",
                        "net/minecraft/world/entity/ambient/Bat", "net/minecraft/world/level/biome/AmbientParticleSettings",
                        "net/minecraft/core/particles/ParticleTypes",
                        "net/minecraft/world/level/material/FluidState", "net/minecraft/world/level/block/state/BlockState",
                        "net/minecraft/world/level/material/Fluids", "net/minecraft/world/level/block/Block",
                        "net/minecraft/sounds/SoundEvents", "com/matthieu/planariumview/client/scenery/ClientView",
                        "net/minecraft/client/multiplayer/ClientLevel", "net/minecraft/server/level/ServerLevel",
                        "net/minecraft/server/MinecraftServer", "net/minecraft/server/dedicated/DedicatedServer").contains(name)) return null;
                ClassReader reader = new ClassReader(bytes);
                ClassWriter writer = new ClassWriter(0);
                reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override public MethodVisitor visitMethod(int access, String method, String descriptor, String signature, String[] exceptions) {
                        MethodVisitor visitor = super.visitMethod(access, method, descriptor, signature, exceptions);
                        if (!method.equals("<clinit>")) return visitor;
                        visitor.visitCode(); visitor.visitInsn(Opcodes.RETURN); visitor.visitMaxs(0, 0); visitor.visitEnd();
                        return null;
                    }
                }, 0);
                return writer.toByteArray();
            }
        });
    }
}

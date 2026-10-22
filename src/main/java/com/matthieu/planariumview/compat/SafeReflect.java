package com.matthieu.planariumview.compat;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * Trouve une methode d'un autre mod sans lister les autres methodes de sa classe.
 *
 * {@code Class.getMethod} lit la signature de toutes les methodes de la classe (et de ses interfaces). Sur un serveur
 * dedie, il suffit qu'une seule d'entre elles mentionne une classe du client (Sable :
 * {@code SubLevelContainer.getContainer(ClientLevel)}) pour que NeoForge refuse de la charger et fasse planter le
 * demarrage. Ici, on lit seulement la signature de la methode voulue dans le fichier .class, puis on y accede par un
 * MethodHandle : seules les classes de cette signature sont chargees.
 */
public final class SafeReflect {
    private SafeReflect() {
    }

    /** Une methode trouvee, appelee comme {@code Method.invoke} (cible ignoree pour une methode statique). */
    public static final class Call {
        private final MethodHandle handle;
        private final boolean isStatic;

        private Call(MethodHandle handle, boolean isStatic) {
            this.handle = handle.asFixedArity();
            this.isStatic = isStatic;
        }

        /** Comme {@code Method.invoke} : toute erreur de la methode appelee arrive en InvocationTargetException. */
        public Object invoke(Object target, Object... args) throws InvocationTargetException {
            try {
                if (isStatic) {
                    return handle.invokeWithArguments(args);
                }
                Object[] all = new Object[args.length + 1];
                all[0] = target;
                System.arraycopy(args, 0, all, 1, args.length);
                return handle.invokeWithArguments(all);
            } catch (Throwable e) {
                throw new InvocationTargetException(e);
            }
        }
    }

    /** Methode publique {@code name(params)} de {@code owner}, declaree par elle ou une classe ou interface parente. */
    public static Call find(Class<?> owner, String name, Class<?>... params) throws ReflectiveOperationException {
        StringBuilder wanted = new StringBuilder("(");
        for (Class<?> param : params) {
            wanted.append(Type.getDescriptor(param));
        }
        String prefix = wanted.append(')').toString();
        Deque<Class<?>> queue = new ArrayDeque<>();
        Set<Class<?>> seen = new HashSet<>();
        queue.add(owner);
        while (!queue.isEmpty()) {
            Class<?> type = queue.poll();
            if (!seen.add(type)) {
                continue;
            }
            // Seulement une classe publique : la methode d'une classe cachee s'appelle par son interface publique.
            Found found = java.lang.reflect.Modifier.isPublic(type.getModifiers()) ? read(type, name, prefix) : null;
            if (found != null) {
                ClassLoader loader = owner.getClassLoader() != null ? owner.getClassLoader() : ClassLoader.getSystemClassLoader();
                MethodType methodType = MethodType.fromMethodDescriptorString(found.descriptor, loader);
                MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                // Une methode statique d'interface n'est pas heritee : on la prend la ou elle est declaree.
                return found.isStatic
                        ? new Call(lookup.findStatic(type, name, methodType), true)
                        : new Call(lookup.findVirtual(type, name, methodType), false);
            }
            if (type.getSuperclass() != null) {
                queue.add(type.getSuperclass());
            }
            for (Class<?> parent : type.getInterfaces()) {
                queue.add(parent);
            }
        }
        throw new NoSuchMethodException(owner.getName() + "." + name + prefix);
    }

    private record Found(String descriptor, boolean isStatic) {
    }

    /** Lit le fichier .class de {@code type} et cherche une methode publique au nom et aux parametres voulus. */
    private static Found read(Class<?> type, String name, String prefix) throws ReflectiveOperationException {
        String path = type.getName().replace('.', '/') + ".class";
        ClassLoader loader = type.getClassLoader();
        try (InputStream in = loader != null ? loader.getResourceAsStream(path) : ClassLoader.getSystemResourceAsStream(path)) {
            if (in == null) {
                return null;
            }
            Found[] result = new Found[1];
            new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String methodName, String descriptor, String signature,
                                                 String[] exceptions) {
                    if (result[0] == null && methodName.equals(name) && descriptor.startsWith(prefix)
                            && (access & Opcodes.ACC_PUBLIC) != 0 && (access & Opcodes.ACC_SYNTHETIC) == 0) {
                        result[0] = new Found(descriptor, (access & Opcodes.ACC_STATIC) != 0);
                    }
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return result[0];
        } catch (java.io.IOException e) {
            throw new ReflectiveOperationException("Lecture de " + path + " impossible", e);
        }
    }
}

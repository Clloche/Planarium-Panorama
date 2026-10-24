package com.matthieu.planariumview.client.scenery;

import com.matthieu.planariumview.client.sound.OutsideSoundClient;
import com.matthieu.planariumview.config.Config;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Reverberation de Dynamic Surroundings pour les sons du dehors (voir DsSoundFxMixin).
 *
 * Ces sons sont joues pres de la camera, dans le bocal, deja attenues : DS les ignorait (attenuation NONE), et sinon il
 * aurait calcule l'echo du bocal. Ici, DS fait son calcul dans le decor, comme si on etait au Planarium : le son part de
 * sa vraie position, ses rayons rebondissent sur les vrais blocs autour (grotte, vallee, foret), et l'occlusion se
 * calcule entre le son et le Planarium. Le calcul se fait sur le fil de DS : DsSourceContextMixin indique quel son
 * est en cours.
 */
public final class SceneryReverb {
    /** Decor, position du son et de l'ecoute dans le decor, pour le son que DS traite sur ce fil. */
    public record Context(Level world, Vec3 source, Vec3 listener) {
    }

    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();

    private SceneryReverb() {
    }

    /** Son du dehors que DS doit traiter (au lieu de l'ignorer comme un son sans attenuation). */
    public static boolean handles(SoundInstance sound) {
        return Config.enabled() && OutsideSoundClient.outsidePosition(sound) != null
                && sound.getSource() != SoundSource.WEATHER && sound.getSource() != SoundSource.MUSIC
                && sound.getSource() != SoundSource.MASTER && ClientView.sceneryWorld() != null;
    }

    /**
     * Le son d'un SourceContext de DS : {@code getSound()} jusqu'a 0.4.6, {@code emitter.sound()} en 0.4.7 (un son
     * de voix n'en a pas). Lu par reflexion : la classe change d'une version a l'autre.
     */
    private static final ClassValue<java.util.function.Function<Object, SoundInstance>> SOUND_OF = new ClassValue<>() {
        @Override
        protected java.util.function.Function<Object, SoundInstance> computeValue(Class<?> type) {
            try {
                java.lang.reflect.Method getter = type.getMethod("getSound");
                if (SoundInstance.class.isAssignableFrom(getter.getReturnType())) {
                    getter.setAccessible(true);
                    return context -> {
                        try {
                            return (SoundInstance) getter.invoke(context);
                        } catch (ReflectiveOperationException | RuntimeException e) {
                            return null;
                        }
                    };
                }
            } catch (NoSuchMethodException | RuntimeException ignored) {
            }
            try {
                java.lang.reflect.Field field = type.getDeclaredField("emitter");
                field.setAccessible(true);
                return context -> {
                    try {
                        Object emitter = field.get(context);
                        if (emitter == null) return null;
                        java.lang.reflect.Method sound = emitter.getClass().getDeclaredMethod("sound");
                        sound.setAccessible(true);
                        Object value = sound.invoke(emitter);
                        return value instanceof SoundInstance instance ? instance : null;
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        return null;
                    }
                };
            } catch (NoSuchFieldException | RuntimeException ignored) {
            }
            return context -> null;
        }
    };

    /** Avant le calcul de DS pour ce SourceContext (fil de DS). */
    public static void begin(Object sourceContext) {
        CURRENT.remove();
        if (sourceContext == null) return;
        SoundInstance sound = SOUND_OF.get(sourceContext.getClass()).apply(sourceContext);
        Vec3 relative = OutsideSoundClient.outsidePosition(sound);
        LitRenderingWorld world = ClientView.sceneryWorld();
        if (relative == null || world == null || !Config.enabled()) return;
        int radius = ClientView.nearRadius();
        // Decor : le Planarium est a (rayon, rayon, rayon). On ecoute juste au-dessus (le Planarium est un bloc).
        Vec3 planarium = new Vec3(radius + 0.5, radius + 0.5, radius + 0.5);
        // Les sons sont relatifs au coin du bloc du Planarium, pas a son centre (voir OutsideSoundClient).
        CURRENT.set(new Context(world, relative.add(radius, radius, radius), planarium.add(0.0, 1.0, 0.0)));
    }

    public static void end() {
        CURRENT.remove();
    }

    /** Son du dehors en cours de calcul sur ce fil, ou null (son normal : DS fait comme d'habitude). */
    public static Context current() {
        return CURRENT.get();
    }
}

package com.matthieu.planariumview.compat.create;

import net.minecraft.world.level.block.entity.BlockEntity;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Angle de la tete d'un roulement de Create (ou d'une helice d'Aeronautics) copie dans le decor. La copie tourne seule a
 * sa propre vitesse (sans voiles, une helice n'accelere pas comme la vraie) et se decalait de la contraption, qui suit
 * l'angle envoye par le serveur. Avant son rendu, on lui donne ce meme angle : champs {@code angle} et
 * {@code prevAngle} (une copie virtuelle interpole entre les deux).
 */
public final class BearingAngles {
    private record Fields(Field angle, Field prevAngle) {
    }

    private static final ClassValue<Fields> FIELDS = new ClassValue<>() {
        @Override
        protected Fields computeValue(Class<?> type) {
            return new Fields(find(type, "angle"), find(type, "prevAngle"));
        }
    };

    private BearingAngles() {
    }

    public static void set(BlockEntity entity, float angle) {
        if (entity == null) return;
        Fields fields = FIELDS.get(entity.getClass());
        if (fields.angle == null) return;
        try {
            fields.angle.setFloat(entity, angle);
            if (fields.prevAngle != null) fields.prevAngle.setFloat(entity, angle);
        } catch (IllegalAccessException | RuntimeException ignored) {
        }
    }

    /** Premier champ {@code float name} non statique de la classe ou de ses parents. */
    private static Field find(Class<?> type, String name) {
        for (Class<?> at = type; at != null && at != BlockEntity.class; at = at.getSuperclass()) {
            try {
                Field field = at.getDeclaredField(name);
                if (field.getType() != float.class || Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
            } catch (RuntimeException | LinkageError e) {
                return null;
            }
        }
        return null;
    }
}

package com.matthieu.planariumview.client.scenery.entities;

import com.mojang.logging.LogUtils;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Apparence recue du serveur pour une copie (mobs du dehors, mobs des miniatures vivantes) : donnees synchronisees et
 * equipement, encodes par OutsideMobsServer. Un tableau vide veut dire "rien n'a change".
 */
public final class MobAppearance {
    private static final Logger LOGGER = LogUtils.getLogger();

    private MobAppearance() {
    }

    /** Pose, variante, bebe, en feu, particules des effets de potion... */
    public static void data(Entity entity, byte[] bytes, RegistryAccess registry) {
        if (bytes == null || bytes.length == 0) return;
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(bytes), registry);
        try {
            List<SynchedEntityData.DataValue<?>> values = new ArrayList<>();
            while (buf.isReadable()) {
                int id = buf.readUnsignedByte();
                if (id == 255) break;
                values.add(SynchedEntityData.DataValue.read(buf, id));
            }
            entity.getEntityData().assignValues(values);
        } catch (RuntimeException e) {
            LOGGER.debug("[Planarium Panorama] Donnees d'une copie de mob ignorees", e);
        } finally {
            buf.release();
        }
    }

    /** Mains, armure et armure du corps, dans l'ordre d'EquipmentSlot. */
    public static void equipment(LivingEntity entity, byte[] bytes, RegistryAccess registry) {
        if (bytes == null || bytes.length == 0) return;
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(bytes), registry);
        try {
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                if (!buf.isReadable()) break;
                ItemStack stack = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
                entity.setItemSlot(slot, stack);
            }
        } catch (RuntimeException e) {
            LOGGER.debug("[Planarium Panorama] Equipement d'une copie de mob ignore", e);
        } finally {
            buf.release();
        }
    }
}

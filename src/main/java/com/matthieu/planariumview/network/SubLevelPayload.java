package com.matthieu.planariumview.network;

import com.matthieu.planariumview.PlanariumView;
import io.netty.buffer.ByteBuf;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/** Independent optional protocol: geometry is sent only when it changes; poses are small frequent updates. */
public record SubLevelPayload(ResourceLocation dimension, boolean miniature, CompoundTag scene)
        implements CustomPacketPayload {
    public static final Type<SubLevelPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(PlanariumView.MODID, "sub_levels"));
    private static final StreamCodec<ByteBuf, byte[]> BYTES = ByteBufCodecs.byteArray(1_000_000);
    public static final StreamCodec<ByteBuf, SubLevelPayload> STREAM_CODEC = StreamCodec.ofMember(
            SubLevelPayload::write, SubLevelPayload::read);

    private void write(ByteBuf buffer) {
        ResourceLocation.STREAM_CODEC.encode(buffer, dimension);
        buffer.writeBoolean(miniature);
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            NbtIo.writeCompressed(scene, bytes);
            BYTES.encode(buffer, bytes.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static SubLevelPayload read(ByteBuf buffer) {
        ResourceLocation dimension = ResourceLocation.STREAM_CODEC.decode(buffer);
        boolean miniature = buffer.readBoolean();
        try {
            CompoundTag scene = NbtIo.readCompressed(new ByteArrayInputStream(BYTES.decode(buffer)),
                    NbtAccounter.create(32L * 1024 * 1024));
            return new SubLevelPayload(dimension, miniature, scene);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

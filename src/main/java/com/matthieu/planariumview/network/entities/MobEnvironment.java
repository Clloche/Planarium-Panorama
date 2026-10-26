package com.matthieu.planariumview.network.entities;

import net.minecraft.network.FriendlyByteBuf;

/** Visual inputs normally refreshed by entity ticks, carried alongside the interpolated position. */
public record MobEnvironment(boolean inWater, boolean underwater, boolean waterOrBubble, boolean onGround,
                             float velocityX, float velocityY, float velocityZ, SquidPose squid) {
    /** Body angles are degrees; the tentacle angle is radians, as in SquidRenderer. */
    public record SquidPose(float pitch, float roll, float tentacleAngle) {}

    public void write(FriendlyByteBuf buf) {
        int flags = (inWater ? 1 : 0) | (underwater ? 2 : 0) | (waterOrBubble ? 4 : 0) | (onGround ? 8 : 0)
                | (squid != null ? 16 : 0);
        buf.writeByte(flags);
        buf.writeFloat(velocityX);
        buf.writeFloat(velocityY);
        buf.writeFloat(velocityZ);
        if (squid != null) {
            buf.writeFloat(squid.pitch());
            buf.writeFloat(squid.roll());
            buf.writeFloat(squid.tentacleAngle());
        }
    }

    public static MobEnvironment read(FriendlyByteBuf buf) {
        int flags = buf.readUnsignedByte();
        float x = buf.readFloat(), y = buf.readFloat(), z = buf.readFloat();
        SquidPose squid = (flags & 16) != 0 ? new SquidPose(buf.readFloat(), buf.readFloat(), buf.readFloat()) : null;
        return new MobEnvironment((flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0, (flags & 8) != 0, x, y, z, squid);
    }
}

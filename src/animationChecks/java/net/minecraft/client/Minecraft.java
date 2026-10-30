package net.minecraft.client;

import net.minecraft.client.multiplayer.ClientLevel;

/** Only the client-world boundary is replaced; checks call the real OutsideMobs implementation. */
public final class Minecraft {
    private static final Minecraft INSTANCE = new Minecraft();
    public ClientLevel level;
    public static Minecraft getInstance() { return INSTANCE; }
    /** Shader fixtures ignore window dimensions; no native window is created. */
    public com.mojang.blaze3d.platform.Window getWindow() { return null; }
}

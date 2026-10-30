package com.matthieu.planariumview;

import com.matthieu.planariumview.client.sky.LunarClient;
import com.matthieu.planariumview.client.scenery.SceneryParticles;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.Camera;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.BiConsumer;

/** Optional-mod reflection and notification transitions without a running client. */
public final class LunarNotificationChecks {
    public enum Mode { NONE, CHAT, HOT_BAR }
    public record Text(Component component) { public Component getComponent() { return component; } }
    public record Notification(Mode notificationType, Text customTranslationTextComponent) {}
    public record Event(Optional<Notification> startNotification, Optional<Notification> endNotification) {}
    private record Display(String text, boolean hotBar) {}
    private static Optional<Notification> notice(String text, Mode mode) {
        return Optional.of(new Notification(mode, new Text(Component.literal(text))));
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static Field field(Class<?> type, String name) throws Exception {
        Field f = type.getDeclaredField(name); f.setAccessible(true); return f;
    }
    public static class TestParticle extends Particle {
        public TestParticle() { super(null, 0, 0, 0); }
        public void render(VertexConsumer buffer, Camera camera, float partial) {}
        public ParticleRenderType getRenderType() { return ParticleRenderType.NO_RENDER; }
    }

    @SuppressWarnings("unchecked") public static void run() throws Exception {
        Method update = LunarClient.class.getDeclaredMethod("updateNotices", Object.class, BiConsumer.class);
        update.setAccessible(true);
        List<Display> displayed = new ArrayList<>();
        BiConsumer<Component, Boolean> sink = (text, bar) -> displayed.add(new Display(text.getString(), bar));
        var blood = Holder.direct(new Event(notice("blood starts", Mode.CHAT), notice("blood ends", Mode.CHAT)));
        var blue = Holder.direct(new Event(notice("blue starts", Mode.HOT_BAR), notice("blue ends", Mode.NONE)));
        var normal = Holder.direct(new Event(Optional.empty(), Optional.empty()));
        LunarClient.clear();
        field(LunarClient.class, "noticesFailed").setBoolean(null, true);
        LunarClient.clear();
        require(!field(LunarClient.class, "noticesFailed").getBoolean(null), "clearing a world retries lunar notifications after failure");
        try {
            update.invoke(null, blood, sink);
            require(displayed.isEmpty(), "entering a pocket during an event does not notify");
            update.invoke(null, blood, sink);
            require(displayed.isEmpty(), "unchanged event does not notify");
            update.invoke(null, blue, sink);
            require(displayed.equals(List.of(new Display("blood ends", false), new Display("blue starts", true))),
                    "old event ends before the new event starts, in each configured location");
            displayed.clear(); update.invoke(null, normal, sink);
            require(displayed.isEmpty(), "NONE and missing notifications are silent");
            update.invoke(null, blood, sink);
            require(displayed.equals(List.of(new Display("blood starts", false))), "normal-to-special transition notifies once");
            displayed.clear(); LunarClient.clear(); update.invoke(null, blue, sink);
            require(displayed.isEmpty(), "reentering a pocket starts a fresh comparison");
        } finally { LunarClient.clear(); }

        Method allocate = MobAnimationIntegrationChecks.class.getDeclaredMethod("fixture", Class.class);
        allocate.setAccessible(true);
        TestParticle particle = (TestParticle) allocate.invoke(null, TestParticle.class);
        Map<Particle, SceneryParticles.Space> spaces = (Map<Particle, SceneryParticles.Space>) field(SceneryParticles.class, "SPACE").get(null);
        require(!SceneryParticles.miniature(particle), "ordinary particles keep vanilla scope behavior");
        try {
            spaces.put(particle, new SceneryParticles.Space(null, 1.0 / 32, 0, 0, 0, null, false, null));
            require(SceneryParticles.miniature(particle), "tracked miniature particles bypass scope fading");
            spaces.put(particle, new SceneryParticles.Space(null, 32, 0, 0, 0, null, false, null));
            require(!SceneryParticles.miniature(particle), "giant scenery keeps vanilla scope behavior");
        } finally { spaces.remove(particle); }
        require(!SceneryParticles.miniature(particle), "removed particles lose miniature classification");
        for (Class<?> type : List.of(net.minecraft.world.entity.monster.hoglin.Hoglin.class,
                net.minecraft.world.entity.monster.Zoglin.class)) {
            require(type.getDeclaredField("attackAnimationRemainingTicks").getType() == int.class,
                    "both targets define the shared accessor field");
        }
        require(net.minecraft.client.particle.SpellParticle.class.getDeclaredMethod("isCloseToScopingPlayer").getReturnType() == boolean.class,
                "scope mixin target exists in this Minecraft version");
        System.out.println("PASS: lunar entry, transitions, message order, chat/action bar/NONE; miniature scope classification; shared accessor targets");
    }
}

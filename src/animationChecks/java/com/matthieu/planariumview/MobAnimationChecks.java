package com.matthieu.planariumview;

import com.matthieu.planariumview.client.scenery.entities.MobAnimationTimeline;
import com.matthieu.planariumview.network.entities.MobAnimations;
import com.matthieu.planariumview.network.entities.OutsideMobsPayload;
import com.matthieu.planariumview.server.entities.MobEventQueue;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import java.util.List;

/** Executable without bootstrapping a game or depending on ignored developer fixtures. */
public final class MobAnimationChecks {
    private static final MobAnimations.Snapshot IDLE = new MobAnimations.Snapshot(0, 0, false, 0, false, false, 0, 6);

    private static MobAnimations.Event event(long sequence, int tick, MobAnimations.Kind kind) {
        return new MobAnimations.Event(sequence, 71, tick, kind, false, kind == MobAnimations.Kind.HURT ? 10 : 6);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void near(float expected, float actual, String message) {
        require(Math.abs(expected - actual) < 0.00001, message + ": " + actual);
    }

    private static void eventsBetweenSnapshots() {
        MobAnimationTimeline view = new MobAnimationTimeline();
        view.snapshot(98, IDLE);
        view.event(event(1, 100, MobAnimations.Kind.HURT));
        view.event(event(2, 101, MobAnimations.Kind.SWING));
        view.advance(99);
        require(view.state().hurtTime() == 0 && !view.state().swinging(), "events wait for rendered server time");
        view.advance(100);
        require(view.state().hurtTime() == 10 && view.takeHurt(), "first hit is applied once");
        require(!view.takeHurt(), "rendering repeatedly cannot replay a hit");
        view.advance(104);
        require(view.state().hurtTime() == 6 && view.state().swingTime() == 2, "both events survive a two-tick send batch");
        near(2F / 6, view.attack(), "main-hand attack phase");
        near(1F / 6, view.previousAttack(), "previous attack phase for partial ticks");
        view.event(event(1, 100, MobAnimations.Kind.HURT));
        view.advance(105);
        require(view.state().hurtTime() == 5 && !view.takeHurt(), "duplicate does not restart hurt");
        view.advance(110);
        require(view.state().hurtTime() == 0 && !view.state().swinging(), "visual counters finish without entity ticks");
    }

    private static void swingDurations() {
        for (boolean offHand : List.of(false, true)) {
            for (int duration : List.of(3, 6, 12)) {
                MobAnimationTimeline view = new MobAnimationTimeline();
                view.event(new MobAnimations.Event(1, 71, 200, MobAnimations.Kind.SWING, offHand, duration));
                view.advance(203);
                require(view.state().offHand() == offHand, "correct swinging hand");
                near(2F / duration, view.attack(), "source duration survives missing potion state on copy");
                view.advance(200 + duration + 1);
                require(!view.state().swinging(), "swing finishes at source duration");
                near((duration - 1F) / duration, view.previousAttack(), "final frame wraps smoothly to zero");
                view.advance(202 + duration);
                near(0, view.previousAttack(), "finished swing stops interpolating");
            }
        }
    }

    private static void resynchronization() {
        MobAnimationTimeline view = new MobAnimationTimeline();
        view.snapshot(100, new MobAnimations.Snapshot(4, 10, false, 0, true, true, 3, 12));
        view.advance(102);
        require(view.state().hurtTime() == 2 && view.state().swingTime() == 5, "join resumes existing phases");
        require(!view.takeHurt(), "snapshot is not a new hit");
        view.snapshot(103, new MobAnimations.Snapshot(1, 10, false, 0, true, true, 6, 12));
        view.advance(104);
        require(view.state().hurtTime() == 0 && view.state().swingTime() == 7, "full snapshot does not restart animations");
        view.event(event(5, 105, MobAnimations.Kind.HURT));
        view.resetPending();
        view.snapshot(106, IDLE);
        view.advance(107);
        require(view.state().hurtTime() == 0 && !view.takeHurt(), "overflow recovery discards incomplete queued history");
        MobAnimationTimeline late = new MobAnimationTimeline();
        late.advance(130);
        late.event(event(1, 100, MobAnimations.Kind.HURT));
        late.advance(131);
        require(late.state().hurtTime() == 0 && !late.takeHurt(), "expired hit is not replayed after warmup or lag");
        view.snapshot(108, IDLE);
        view.advance(108);
        require(!view.state().dying(), "an alive snapshot without a death event remains alive");
    }

    private static void death() {
        MobAnimationTimeline view = new MobAnimationTimeline();
        view.event(event(1, 100, MobAnimations.Kind.HURT));
        view.event(event(2, 100, MobAnimations.Kind.DEATH));
        view.event(event(3, 120, MobAnimations.Kind.POOF));
        view.advance(117);
        require(view.state().deathTime() == 17 && !view.ended() && !view.takePoof(), "early removal packet does not shorten delayed death");
        view.advance(119);
        require(view.state().deathTime() == 19 && !view.ended(), "last death frames remain visible");
        view.advance(120);
        require(view.state().deathTime() == 20 && view.ended() && view.takePoof(), "terminal event produces one poof");
        view.event(event(3, 120, MobAnimations.Kind.POOF));
        view.advance(121);
        require(!view.takePoof(), "duplicate terminal event cannot emit a second poof");
        MobAnimationTimeline joined = new MobAnimationTimeline();
        joined.snapshot(110, new MobAnimations.Snapshot(0, 10, true, 10, false, false, 0, 6));
        joined.advance(115);
        require(joined.state().deathTime() == 15, "joining during death resumes instead of replaying twenty ticks");
        MobAnimationTimeline outOfRange = new MobAnimationTimeline();
        outOfRange.event(event(1, 100, MobAnimations.Kind.DEATH));
        outOfRange.advance(140);
        require(!outOfRange.takePoof(), "death age alone does not invent a terminal particle event");
    }

    private static void observersAndOverflow() {
        MobEventQueue first = new MobEventQueue(), second = new MobEventQueue();
        MobAnimations.Event hit = event(1, 100, MobAnimations.Kind.HURT);
        first.add(hit); second.add(hit);
        require(first.drain().equals(List.of(hit)), "first observer receives hit");
        require(first.drain().isEmpty() && second.drain().equals(List.of(hit)), "draining one observer does not consume another's events");
        for (int i = 0; i < MobAnimations.MAX_EVENTS; i++) first.add(event(i, 100, MobAnimations.Kind.SWING));
        require(!first.resetRequired(), "bounded queue accepts its documented capacity");
        first.add(hit);
        require(first.resetRequired() && first.drain().isEmpty(), "overflow requests snapshot instead of a truncated history");
        first.add(hit); first.clear();
        require(first.drain().isEmpty() && !first.resetRequired(), "leaving view clears queue and recovery state");
    }

    private static void protocol() {
        var snapshot = new MobAnimations.Snapshot(7, 10, true, 3, true, true, -1, 12);
        var entry = new OutsideMobsPayload.Entry(71, 1, 2, 3, 170, 0, 20, 10, 70, 1, null, null, new byte[0], new byte[0], snapshot);
        var events = List.of(event(300, 100, MobAnimations.Kind.HURT), event(301, 101, MobAnimations.Kind.DEATH),
                new MobAnimations.Event(302, 71, 102, MobAnimations.Kind.SWING, true, 12));
        var packet = new OutsideMobsPayload(ResourceLocation.parse("test:pocket"), 14, new BlockPos(-100, 64, 200), 102,
                true, List.of(entry), new int[]{91}, events, true);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            OutsideMobsPayload.STREAM_CODEC.encode(buffer, packet);
            var read = OutsideMobsPayload.STREAM_CODEC.decode(buffer);
            require(read.events().equals(events) && read.entries().getFirst().living().equals(snapshot), "events and snapshot round trip");
            require(read.resync() == 14 && read.resetAnimations() && read.entries().getFirst().vehicleId() == 70
                    && read.entries().getFirst().seat() == 1 && read.removed()[0] == 91 && !buffer.isReadable(), "epoch, mount links and removals coexist with events");
            buffer.clear();
            buffer.writeResourceLocation(packet.pocketDim()); buffer.writeVarInt(14); buffer.writeBlockPos(BlockPos.ZERO);
            buffer.writeVarInt(100); buffer.writeBoolean(false); buffer.writeBoolean(false);
            buffer.writeVarInt(0); buffer.writeVarIntArray(new int[0]); buffer.writeVarInt(MobAnimations.MAX_EVENTS + 1);
            boolean rejected = false;
            try { OutsideMobsPayload.STREAM_CODEC.decode(buffer); } catch (IllegalArgumentException expected) { rejected = true; }
            require(rejected, "decoder bounds event count before allocation");
        } finally { buffer.release(); }
    }

    public static void main(String[] args) {
        eventsBetweenSnapshots(); swingDurations(); resynchronization(); death(); observersAndOverflow(); protocol();
        System.out.println("PASS: delayed events, both hands/durations, resync, twenty-tick death, unique poof, observers, overflow and codec");
    }
}

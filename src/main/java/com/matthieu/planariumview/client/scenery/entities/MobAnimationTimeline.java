package com.matthieu.planariumview.client.scenery.entities;

import com.matthieu.planariumview.network.entities.MobAnimations;
import java.util.Comparator;
import java.util.PriorityQueue;

/** Advance only visual counters on the delayed server clock, including while the copies are hidden. */
public final class MobAnimationTimeline {
    private record Change(int tick, long order, MobAnimations.Event event, MobAnimations.Snapshot snapshot) {}
    private final PriorityQueue<Change> pending = new PriorityQueue<>(Comparator.comparingInt(Change::tick)
            .thenComparingLong(Change::order));
    private long lastSequence = -1;
    private int tick = Integer.MIN_VALUE;
    private int hurtTime, hurtDuration, deathTime, swingTime;
    private int swingDuration = 6;
    private boolean dying, swinging, offHand, ended, poof, hurtStarted;
    private float attack, previousAttack;

    public void event(MobAnimations.Event event) {
        if (event.sequence() <= lastSequence) return;
        lastSequence = event.sequence();
        pending.add(new Change(event.tick(), event.sequence(), event, null));
    }

    public void snapshot(int at, MobAnimations.Snapshot snapshot) {
        pending.add(new Change(at, Long.MAX_VALUE, null, snapshot));
    }

    public void resetPending() { pending.clear(); }

    public void advance(int target) {
        target = Math.max(target, tick);
        while (!pending.isEmpty() && pending.peek().tick() <= target) {
            Change change = pending.remove();
            advanceFields(change.tick());
            int age = Math.max(0, tick - change.tick());
            if (change.snapshot() != null) {
                MobAnimations.Snapshot state = change.snapshot();
                hurtTime = Math.max(0, state.hurtTime() - age);
                hurtDuration = state.hurtDuration();
                dying = state.dying();
                deathTime = dying ? Math.min(MobAnimations.MAX_DURATION, state.deathTime() + age) : 0;
                setSwing(state.swinging(), state.offHand(), state.swingTime(), state.swingDuration(), age);
            } else {
                MobAnimations.Event event = change.event();
                switch (event.kind()) {
                    case HURT -> {
                        hurtDuration = event.duration();
                        hurtTime = Math.max(0, hurtDuration - age);
                        hurtStarted = true;
                    }
                    case SWING -> setSwing(true, event.offHand(), -1, Math.max(1, event.duration()), age);
                    case DEATH -> { dying = true; deathTime = age; }
                    case POOF -> { ended = true; poof = true; }
                }
            }
        }
        advanceFields(target);
    }

    private void advanceFields(int target) {
        if (tick == Integer.MIN_VALUE) { tick = target; return; }
        int delta = target - tick;
        if (delta <= 0) return;
        hurtTime = Math.max(0, hurtTime - delta);
        if (dying) deathTime = Math.min(MobAnimations.MAX_DURATION, deathTime + delta);
        setSwing(swinging, offHand, swingTime, swingDuration, delta);
        tick = target;
    }

    private void setSwing(boolean active, boolean hand, int phase, int duration, int age) {
        offHand = hand;
        swingDuration = duration;
        int current = phase + age;
        int previous = current - 1;
        previousAttack = active && previous >= 0 && previous < duration ? (float) previous / duration : 0;
        swinging = active && current < duration;
        swingTime = swinging ? Math.max(-1, current) : 0;
        attack = swinging ? (float) Math.max(0, swingTime) / duration : 0;
    }

    public boolean takeHurt() {
        boolean result = hurtStarted && hurtTime > 0;
        hurtStarted = false;
        return result;
    }

    public boolean takePoof() {
        boolean result = poof;
        poof = false;
        return result;
    }

    public boolean ended() { return ended; }
    public MobAnimations.Snapshot state() {
        return new MobAnimations.Snapshot(hurtTime, hurtDuration, dying, deathTime, swinging, offHand, swingTime, swingDuration);
    }
    public float attack() { return attack; }
    public float previousAttack() { return previousAttack; }
}

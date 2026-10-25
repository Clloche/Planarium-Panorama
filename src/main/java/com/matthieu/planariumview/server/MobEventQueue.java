package com.matthieu.planariumview.server;

import com.matthieu.planariumview.network.MobAnimations;
import java.util.ArrayList;
import java.util.List;

/** One queue per viewer. Overflow requests a current snapshot instead of replaying an incomplete history. */
public final class MobEventQueue {
    private final List<MobAnimations.Event> events = new ArrayList<>();
    private boolean reset;

    public void add(MobAnimations.Event event) {
        if (reset) return;
        if (events.size() == MobAnimations.MAX_EVENTS) {
            events.clear();
            reset = true;
        } else {
            events.add(event);
        }
    }

    public boolean resetRequired() { return reset; }

    public List<MobAnimations.Event> drain() {
        List<MobAnimations.Event> result = List.copyOf(events);
        clear();
        return result;
    }

    public void clear() {
        events.clear();
        reset = false;
    }
}

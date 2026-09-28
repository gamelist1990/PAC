package org.pexserver.pac.movement;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Keeps the short client-view history needed to compensate movement packets. */
public final class ClientWorldHistory {
    public record Frame(int tick, long capturedAt, MotionEnvironment.Snapshot environment,
                        MotionCollisionSnapshot collisions) { }

    private static final int MAX_FRAMES = 40;
    private final ConcurrentHashMap<UUID, Deque<Frame>> histories = new ConcurrentHashMap<>();

    public void add(UUID uuid, Frame frame) {
        if (uuid == null || frame == null) return;
        Deque<Frame> history = histories.computeIfAbsent(uuid, ignored -> new ArrayDeque<>());
        synchronized (history) {
            history.addLast(frame);
            while (history.size() > MAX_FRAMES) history.removeFirst();
        }
    }

    /** Selects the newest frame captured no later than the compensated target time. */
    public Frame atOrBefore(UUID uuid, long targetAt) {
        Deque<Frame> history = histories.get(uuid);
        if (history == null) return null;
        synchronized (history) {
            Frame selected = null;
            for (Frame frame : history) {
                if (frame.capturedAt() > targetAt) break;
                selected = frame;
            }
            return selected == null ? history.peekFirst() : selected;
        }
    }

    public void forget(UUID uuid) { histories.remove(uuid); }
}
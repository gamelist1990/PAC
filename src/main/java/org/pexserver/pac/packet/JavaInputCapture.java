package org.pexserver.pac.packet;

import org.pexserver.pac.movement.MotionPredictor;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Captures the latest Java movement keys, with a two-frame transition allowance. */
public final class JavaInputCapture {
    public record Window(MotionPredictor.Input current, MotionPredictor.Input previous) { }
    private static final class State {
        MotionPredictor.Input current;
        MotionPredictor.Input previous;
        int transitionFrames;
    }
    private final ConcurrentHashMap<UUID, State> states = new ConcurrentHashMap<>();

    public void update(UUID uuid, MotionPredictor.Input input) {
        State state = states.computeIfAbsent(uuid, ignored -> new State());
        synchronized (state) {
            if (input.equals(state.current)) return;
            state.previous = state.current;
            state.current = input;
            state.transitionFrames = state.previous == null ? 0 : 2;
        }
    }

    /** Snapshot input without consuming the transition on a packet that may be rejected. */
    public Window nextMovement(UUID uuid) {
        State state = states.get(uuid);
        if (state == null) return null;
        synchronized (state) {
            MotionPredictor.Input previous = state.transitionFrames > 0 ? state.previous : null;
            return new Window(state.current, previous);
        }
    }

    public void acceptedPosition(UUID uuid, Window window) {
        if (window == null || window.previous() == null) return;
        State state = states.get(uuid);
        if (state == null) return;
        synchronized (state) {
            // A newer PLAYER_INPUT packet must keep its own two-frame allowance.
            if (state.current == window.current() && state.previous == window.previous()
                    && state.transitionFrames > 0) state.transitionFrames--;
        }
    }

    public void forget(UUID uuid) { states.remove(uuid); }
}

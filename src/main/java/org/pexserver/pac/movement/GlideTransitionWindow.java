package org.pexserver.pac.movement;

/** Server-observed glide transitions, including toggles between world samples. */
public final class GlideTransitionWindow {
    public record State(long sequence, boolean gliding, double horizontalSpeed, long at, long until) {
        public boolean settling(long now) { return now >= at && now < until; }
    }
    private State state;
    public synchronized void observe(boolean gliding, double horizontalSpeed, long now, int ping) {
        if (!Double.isFinite(horizontalSpeed) || horizontalSpeed < 0) return;
        if (state == null && !gliding) return;
        if (state == null || state.gliding() != gliding) {
            double retained = state == null || now - state.at() > 500 ? horizontalSpeed
                    : Math.max(horizontalSpeed, state.horizontalSpeed());
            state = new State(state == null ? 1 : state.sequence() + 1, gliding, retained, now,
                    now + Math.max(150L, Math.min(500L, (long) ping + 150)));
        } else if (gliding) {
            state = new State(state.sequence(), true, horizontalSpeed, now, state.until());
        }
    }
    public synchronized State get() { return state; }
}

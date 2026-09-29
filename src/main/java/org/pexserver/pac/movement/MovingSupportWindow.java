package org.pexserver.pac.movement;

/** Bounded interpolation/transport window after contact with a moving entity floor. */
public final class MovingSupportWindow {
    private volatile long until;

    public synchronized void contact(long now, int pingMillis) {
        long duration = Math.max(300L, Math.min(1000L, (long) Math.max(0, pingMillis) + 200L));
        until = Math.max(until, now + duration);
    }

    public boolean uncertain(long now) { return now < until; }
}

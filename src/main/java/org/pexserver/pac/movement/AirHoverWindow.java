package org.pexserver.pac.movement;

import java.util.ArrayDeque;

/**
 * Detects sustained airborne hovering from a rolling position history.
 * Sampling is deliberately independent of the per-packet gravity predictor,
 * so brief Flight/Anti-Kick toggles cannot erase the evidence.
 */
public final class AirHoverWindow {
    private static final long WINDOW_MILLIS = 4_500;
    private static final long MINIMUM_SPAN_MILLIS = 2_500;
    private static final long MAX_SAMPLE_GAP_MILLIS = 2_500;
    private static final long MIN_SAMPLE_INTERVAL_MILLIS = 40;
    private static final double MAX_VERTICAL_RANGE = 0.35;
    private static final double MINIMUM_NEAR_STATIONARY_RATIO = 0.45;
    private static final double NEAR_STATIONARY_PER_TICK = 0.015;
    private static final int MINIMUM_SAMPLES = 4;
    private static final long INELIGIBLE_GRACE_MILLIS = 750;

    private record Sample(long at, double y) { }

    private final ArrayDeque<Sample> samples = new ArrayDeque<>();
    private long lastEligibleAt;

    public boolean sample(boolean eligible, double y, long now) {
        if (!Double.isFinite(y)) {
            samples.clear();
            lastEligibleAt = 0;
            return false;
        }
        if (!eligible) {
            // A 0.035m Anti-Kick dip can briefly flip the sampled ground or
            // collision mode. Dropping the entire rolling history here lets a
            // periodic dip reset evidence forever. Retain it across a bounded
            // geometry transition; longer legitimate mode changes still clear.
            if (lastEligibleAt == 0 || now < lastEligibleAt
                    || now - lastEligibleAt > INELIGIBLE_GRACE_MILLIS) {
                samples.clear();
                lastEligibleAt = 0;
            }
            return false;
        }
        lastEligibleAt = now;

        Sample previous = samples.peekLast();
        if (previous != null && (now < previous.at() || now - previous.at() > MAX_SAMPLE_GAP_MILLIS))
            samples.clear();

        previous = samples.peekLast();
        if (previous != null && now - previous.at() < MIN_SAMPLE_INTERVAL_MILLIS) samples.removeLast();
        samples.addLast(new Sample(now, y));
        while (!samples.isEmpty() && now - samples.peekFirst().at() > WINDOW_MILLIS)
            samples.removeFirst();

        Sample first = samples.peekFirst();
        if (first == null || samples.size() < MINIMUM_SAMPLES
                || now - first.at() < MINIMUM_SPAN_MILLIS) return false;

        double minimum = Double.POSITIVE_INFINITY;
        double maximum = Double.NEGATIVE_INFINITY;
        for (Sample sample : samples) {
            minimum = Math.min(minimum, sample.y());
            maximum = Math.max(maximum, sample.y());
        }
        if (maximum - minimum <= MAX_VERTICAL_RANGE) return true;

        long observedTicks = 0;
        long nearStationaryTicks = 0;
        previous = null;
        for (Sample sample : samples) {
            if (previous != null) {
                long ticks = Math.max(1, Math.min(20,
                        Math.round((sample.at() - previous.at()) / 50.0)));
                observedTicks += ticks;
                if (Math.abs(sample.y() - previous.y()) <= NEAR_STATIONARY_PER_TICK * ticks)
                    nearStationaryTicks += ticks;
            }
            previous = sample;
        }
        return observedTicks > 0
                && (double) nearStationaryTicks / observedTicks >= MINIMUM_NEAR_STATIONARY_RATIO;
    }
}

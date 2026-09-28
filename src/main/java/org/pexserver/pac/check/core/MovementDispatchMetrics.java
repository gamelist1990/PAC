package org.pexserver.pac.check.core;

import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.atomic.LongAccumulator;

/** Low-contention timing summary for the complete Java movement-packet check dispatch. */
public final class MovementDispatchMetrics {
    private final LongAdder samples = new LongAdder();
    private final LongAdder totalNanos = new LongAdder();
    private final LongAdder[] histogram = new LongAdder[64];
    private final LongAccumulator maximumNanos = new LongAccumulator(Math::max, 0);

    public MovementDispatchMetrics() {
        for (int i = 0; i < histogram.length; i++) histogram[i] = new LongAdder();
    }

    public void record(long elapsedNanos) {
        long elapsed = Math.max(0, elapsedNanos);
        samples.increment();
        totalNanos.add(elapsed);
        histogram[bucket(elapsed)].increment();
        maximumNanos.accumulate(elapsed);
    }

    public Snapshot snapshot() {
        long count = samples.sum();
        if (count == 0) return new Snapshot(0, 0, 0, 0);
        long percentileTarget = Math.max(1, (long) Math.ceil(count * 0.95));
        long seen = 0;
        long p95UpperBound = 0;
        for (int i = 0; i < histogram.length; i++) {
            seen += histogram[i].sum();
            if (seen >= percentileTarget) {
                p95UpperBound = bucketUpperBound(i);
                break;
            }
        }
        return new Snapshot(count, (double) totalNanos.sum() / count,
                p95UpperBound, maximumNanos.get());
    }

    private static int bucket(long nanos) {
        return nanos == 0 ? 0 : 63 - Long.numberOfLeadingZeros(nanos);
    }

    private static long bucketUpperBound(int bucket) {
        return bucket >= 62 ? Long.MAX_VALUE : (1L << (bucket + 1)) - 1;
    }

    public record Snapshot(long samples, double averageNanos,
                           long p95UpperBoundNanos, long maximumNanos) { }
}

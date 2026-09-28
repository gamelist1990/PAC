package org.pexserver.pac.check.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MovementDispatchMetricsTest {
    @Test void recordsAverageTailLatencyAndMaximumForDebugStatus() {
        MovementDispatchMetrics metrics = new MovementDispatchMetrics();
        metrics.record(100);
        metrics.record(200);
        metrics.record(900);
        metrics.record(2_000);

        MovementDispatchMetrics.Snapshot snapshot = metrics.snapshot();
        assertEquals(4, snapshot.samples());
        assertEquals(800, snapshot.averageNanos(), 1.0e-9);
        assertEquals(2_047, snapshot.p95UpperBoundNanos());
        assertEquals(2_000, snapshot.maximumNanos());
    }

    @Test void emptyMetricStartsAtZero() {
        MovementDispatchMetrics.Snapshot snapshot = new MovementDispatchMetrics().snapshot();
        assertEquals(0, snapshot.samples());
        assertEquals(0, snapshot.averageNanos());
        assertEquals(0, snapshot.p95UpperBoundNanos());
        assertEquals(0, snapshot.maximumNanos());
    }
}

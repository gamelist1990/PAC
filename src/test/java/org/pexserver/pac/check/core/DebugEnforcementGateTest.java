package org.pexserver.pac.check.core;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebugEnforcementGateTest {
    @Test void recordingModePreventsPunitiveWritesUntilItIsDisabled() throws Exception {
        var gate = new DebugEnforcementGate();
        var writes = new AtomicInteger();
        long originalGeneration = gate.generation();

        assertTrue(gate.runWhenLive(originalGeneration, writes::incrementAndGet));
        assertTrue(gate.setRecording(true));
        assertFalse(gate.runWhenLive(originalGeneration, writes::incrementAndGet));
        assertEquals(1, writes.get());
        assertTrue(gate.recording());

        assertTrue(gate.setRecording(false));
        assertFalse(gate.runWhenLive(originalGeneration, writes::incrementAndGet),
                "a task queued before a debug cycle stays cancelled after recording stops");
        assertFalse(gate.live(originalGeneration));
        assertTrue(gate.runWhenLive(gate.generation(), writes::incrementAndGet));
        assertEquals(2, writes.get());
    }

    @Test void modeChangeWaitsForAnAlreadyStartedWrite() throws Exception {
        var gate = new DebugEnforcementGate();
        var writeStarted = new CountDownLatch(1);
        var releaseWrite = new CountDownLatch(1);
        var modeChanged = new CountDownLatch(1);
        long generation = gate.generation();
        Thread writer = Thread.ofVirtual().start(() -> {
            try {
                gate.runWhenLive(generation, () -> {
                    writeStarted.countDown();
                    if (!releaseWrite.await(5, TimeUnit.SECONDS))
                        throw new IllegalStateException("write release timed out");
                });
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        assertTrue(writeStarted.await(5, TimeUnit.SECONDS));
        Thread switcher = Thread.ofVirtual().start(() -> {
            gate.setRecording(true);
            modeChanged.countDown();
        });
        try {
            assertFalse(modeChanged.await(100, TimeUnit.MILLISECONDS));
        } finally {
            releaseWrite.countDown();
            writer.join();
            switcher.join();
        }
        assertTrue(modeChanged.await(5, TimeUnit.SECONDS));
        assertFalse(gate.runWhenLive(generation,
                () -> { throw new AssertionError("write after debug switch"); }));
    }
}

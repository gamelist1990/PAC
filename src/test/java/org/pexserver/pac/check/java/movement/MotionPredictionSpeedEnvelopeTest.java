package org.pexserver.pac.check.java.movement;

import org.junit.jupiter.api.Test;
import org.pexserver.pac.movement.GroundMotionSequence;
import org.pexserver.pac.movement.MotionEnvironment;
import org.pexserver.pac.movement.SustainedSpeedEnvelope;

import static org.junit.jupiter.api.Assertions.*;

class MotionPredictionSpeedEnvelopeTest {
    @Test void flatFlightSpeedSurvivesGroundModelRebases() {
        assertTrue(flatMovementFlags(0.4),
                "horizontal flight must be detected even when ground replay keeps rebasing");
    }

    @Test void ordinaryFlatSprintRemainsLegalAcrossGroundModelRebases() {
        assertFalse(flatMovementFlags(0.28));
    }

    @Test void mildFlatFlightCannotBankToleranceAcrossGroundModelRebases() {
        assertTrue(flatMovementFlags(0.35));
    }

    private boolean flatMovementFlags(double speed) {
        var sequence = new GroundMotionSequence();
        var envelope = new SustainedSpeedEnvelope();
        long started = System.nanoTime();
        boolean flagged = false;
        for (int tick = 0; tick <= 40; tick++) {
            long now = System.currentTimeMillis();
            // A sprint-state transition invalidates the per-mode replay, but
            // cannot excuse constant horizontal acceleration on a flat floor.
            var environment = new MotionEnvironment.Snapshot(true, false, tick % 2 == 0,
                    false, 0, 0.13, tick * speed, 64, 0, tick, now);
            var sample = sequence.accept(true, false, tick * speed, 64, 0, 0,
                    environment, now);
            var bound = envelope.accept(tick * speed, 64, 0, environment, null,
                    started + tick * 50_000_000L, false);
            assertFalse(sample.evaluated());
            flagged |= bound.flagged() && !MotionPredictionCheck.explainsSpeedEnvelope(sample, 0.04);
        }
        return flagged;
    }

    @Test void zeroOffsetCannotExcuseIndependentSpeedExcess() {
        var sample = new GroundMotionSequence.Sample(true, 0, false, 0.4, 0,
                false, false, 0.05, 1);
        assertFalse(MotionPredictionCheck.explainsSpeedEnvelope(sample, 0.04));
    }

    @Test void matchingLegalReplayStillOverridesConservativeEnvelope() {
        var sample = new GroundMotionSequence.Sample(true, 0.001, false, 0.5, 0,
                false, false, 0, 0);
        assertTrue(MotionPredictionCheck.explainsSpeedEnvelope(sample, 0.04));
    }
}

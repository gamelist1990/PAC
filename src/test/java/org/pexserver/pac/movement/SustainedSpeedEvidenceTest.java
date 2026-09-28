package org.pexserver.pac.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SustainedSpeedEvidenceTest {
    @Test void severeSpeedExcessFlagsImmediatelyButModerateExcessNeedsTwoSamples() {
        var severe = new SustainedSpeedEvidence();
        assertTrue(severe.accept(0.29), "large single-frame excess should not be capped below detection");

        var moderate = new SustainedSpeedEvidence();
        assertFalse(moderate.accept(0.086));
        assertTrue(moderate.accept(0.086), "repeated smaller excess should accumulate quickly");
    }

    @Test void exactWurstSpeedHackHorizontalCapIsCaughtOnOrdinaryGround() {
        var sequence = new GroundMotionSequence();
        var evidence = new SustainedSpeedEvidence();
        double z = 0;
        sequence.accept(true, true, 0, 64, z, 0,
                ground(z, 1, 0.13), 1_000);
        boolean detected = false;
        for (int frame = 2; frame <= 12; frame++) {
            // SpeedHackHack multiplies horizontal delta movement by 1.8 and
            // clamps its magnitude to 0.66 while the player is grounded.
            z += 0.66;
            var sample = sequence.accept(true, false, 0, 64, z, 0,
                    ground(z, frame, 0.13), 1_000 + frame * 50L);
            if (sample.evaluated() && evidence.accept(sample.speedExcess())) {
                detected = true;
                break;
            }
        }
        assertTrue(detected, "the Wurst 0.66 blocks/tick cap must exceed the vanilla ground envelope");
    }

    @Test void catchesSmallRepeatedAccelerationBelowSingleFrameOffsetThreshold() {
        assertFalse(simulate(1.0, 0.1));
        assertTrue(simulate(1.1, 0.1));
        assertFalse(simulate(1.0, 0.5));
    }

    @Test void unrelatedPhysicsTransitionClearsAccumulatedEvidence() {
        var evidence = new SustainedSpeedEvidence();
        for (int i = 0; i < 10; i++) assertFalse(evidence.accept(0.012));
        evidence.reset();
        for (int i = 0; i < 10; i++) assertFalse(evidence.accept(0.012));
    }

    @Test void catchesNoSlowdownBelowTheSingleFrameOffsetThreshold() {
        var sequence = new GroundMotionSequence();
        var evidence = new SustainedSpeedEvidence();
        double z = 0, step = 0;
        sequence.accept(true, true, 0, 64, 0, 0, usingItemGround(0, 1), 1050);
        boolean detected = false;
        for (int frame = 2; frame <= 40; frame++) {
            step = step * (0.6f * 0.91f) + 0.04;
            z += step;
            var sample = sequence.accept(true, false, 0, 64, z, 0,
                    usingItemGround(z, frame), 1000 + frame * 50L);
            if (!sample.evaluated()) continue;
            assertTrue(sample.offset() < 0.04);
            if (evidence.accept(sample.speedExcess())) {
                detected = true;
                break;
            }
        }
        assertTrue(detected);
    }

    @Test void cobwebSlowdownIsAcceptedButFullSpeedIsAccumulatedAsNoSlow() {
        var legitimate = new GroundMotionSequence();
        var legitimateEvidence = new SustainedSpeedEvidence();
        double legalZ = 0, legalStep = 0;
        legitimate.accept(true, true, 0, 64, 0, 0, cobwebGround(0, 1), 1_000);
        for (int frame = 2; frame <= 30; frame++) {
            legalStep = (legalStep * (0.6f * 0.91f) + 0.1 * 0.98f) * 0.25;
            legalZ += legalStep;
            var sample = legitimate.accept(true, false, 0, 64, legalZ, 0,
                    cobwebGround(legalZ, frame), 1_000 + frame * 50L);
            if (!sample.evaluated()) continue;
            assertEquals(0, sample.offset(), 1.0e-9);
            assertFalse(legitimateEvidence.accept(sample.speedExcess()));
        }

        var noSlow = new GroundMotionSequence();
        var noSlowEvidence = new SustainedSpeedEvidence();
        double cheatedZ = 0, cheatedStep = 0;
        noSlow.accept(true, true, 0, 64, 0, 0, cobwebGround(0, 1), 1_000);
        boolean detected = false;
        for (int frame = 2; frame <= 30; frame++) {
            cheatedStep = cheatedStep * (0.6f * 0.91f) + 0.1 * 0.98f;
            cheatedZ += cheatedStep;
            var sample = noSlow.accept(true, false, 0, 64, cheatedZ, 0,
                    cobwebGround(cheatedZ, frame), 1_000 + frame * 50L);
            if (sample.evaluated() && noSlowEvidence.accept(sample.speedExcess())) {
                detected = true;
                break;
            }
        }
        assertTrue(detected);
    }

    private boolean simulate(double multiplier, double movementSpeed) {
        var sequence = new GroundMotionSequence();
        var evidence = new SustainedSpeedEvidence();
        double z = 0, step = 0;
        sequence.accept(true, true, 0, 64, 0, 0,
                ground(0, 1, movementSpeed), 1_050);
        for (int frame = 2; frame <= 45; frame++) {
            step = multiplier * (step * (0.6f * 0.91f) + movementSpeed * 0.98f);
            z += step;
            var sample = sequence.accept(true, false, 0, 64, z, 0,
                    ground(z, frame, movementSpeed), 1_000 + frame * 50L);
            if (!sample.evaluated()) continue;
            assertTrue(sample.offset() < 0.04, "single-frame threshold must miss this case");
            if (evidence.accept(sample.speedExcess())) return true;
        }
        return false;
    }

    private MotionEnvironment.Snapshot ground(double z, int frame, double movementSpeed) {
        return new MotionEnvironment.Snapshot(true, false, false, false,
                0, movementSpeed, 0, 64, z, frame, 1_000 + frame * 50L);
    }

    private MotionEnvironment.Snapshot usingItemGround(double z, int frame) {
        return new MotionEnvironment.Snapshot(true, false, false, false, true,
                0, 0.1, 0, 64, z, frame, 1000 + frame * 50L,
                false, false, 0.6f, 0.08, 0.91f, 0.98f, 0.42f,
                false, -1, false, 0.3f, 0.2f);
    }

    private MotionEnvironment.Snapshot cobwebGround(double z, int frame) {
        return new MotionEnvironment.Snapshot(true, false, false, false, false,
                0, 0.1, 0, 64, z, frame, 1_000 + frame * 50L,
                false, false, 0.6f, 0.08, 0.91f, 0.98f, 0.42f,
                false, -1, false, 0.3f, 1.0f, 0.25f, 0.05f);
    }
}

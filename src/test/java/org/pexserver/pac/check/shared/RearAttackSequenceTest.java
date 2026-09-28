package org.pexserver.pac.check.shared;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RearAttackSequenceTest {
    @Test void onlyTwoConsecutiveRearHitsConfirmAnAttackViewViolation() {
        RearAttackSequence sequence = new RearAttackSequence();

        assertFalse(sequence.record(true, 100), "a single unusual hit should not be flagged");
        assertTrue(sequence.record(true, 101), "a second consecutive rear hit confirms it");
    }

    @Test void frontHemisphereHitResetsTheRearHitStreak() {
        RearAttackSequence sequence = new RearAttackSequence();
        assertFalse(sequence.record(true, 100));
        assertFalse(sequence.record(false, 101));
        assertFalse(sequence.record(true, 102));
        assertTrue(sequence.record(true, 103));
    }

    @Test void rearHitsMustBeWithinTwentyTicksToBeConsecutiveForThisCheck() {
        RearAttackSequence sequence = new RearAttackSequence();
        assertFalse(sequence.record(true, 100));
        assertFalse(sequence.record(true, 121));
        assertTrue(sequence.record(true, 122));
    }
}

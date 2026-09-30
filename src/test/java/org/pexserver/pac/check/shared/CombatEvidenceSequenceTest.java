package org.pexserver.pac.check.shared;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatEvidenceSequenceTest {
    @Test void sameTickNetworkBurstDoesNotCountAsIndependentEvidence() {
        var sequence = new CombatEvidenceSequence(2, 20);
        for (int i = 0; i < 20; i++) assertFalse(sequence.record(true, 100));
        assertTrue(sequence.record(true, 101));
    }

    @Test void requiresConsecutiveEvidenceWithinTickWindow() {
        var sequence = new CombatEvidenceSequence(2, 20);
        assertFalse(sequence.record(true, 100));
        assertTrue(sequence.record(true, 101));
    }

    @Test void cleanSampleAndLongGapResetEvidence() {
        var sequence = new CombatEvidenceSequence(2, 20);
        assertFalse(sequence.record(true, 100));
        assertFalse(sequence.record(false, 101));
        assertFalse(sequence.record(true, 102));
        assertTrue(sequence.record(true, 103));

        sequence.reset();
        assertFalse(sequence.record(true, 200));
        assertFalse(sequence.record(true, 221));
        assertTrue(sequence.record(true, 222));
    }
}

package org.pexserver.pac.check.shared;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttackRotationSequenceTest {
    @Test void repeatedSnapAttackRestorePatternFlagsTypeCSignal() {
        var sequence = new AttackRotationSequence();
        long now = 1_000L;
        sequence.sampleRotation(0, 0, now);

        AttackRotationSequence.Finding finding = null;
        for (int i = 0; i < 3; i++) {
            now += 50;
            sequence.sampleRotation(30, 4, now);
            sequence.attackPacket(now + 2);
            now += 50;
            finding = sequence.sampleRotation(0, 0, now);
        }

        assertNotNull(finding);
        assertTrue(finding.snapDegrees() > 25);
        assertTrue(finding.returnErrorDegrees() < 0.01);
        assertTrue(finding.reversalCosine() < -0.99);
    }

    @Test void oneSnapRestoreIsNotEnoughEvidence() {
        var sequence = new AttackRotationSequence();
        sequence.sampleRotation(0, 0, 1_000);
        sequence.sampleRotation(40, 0, 1_050);
        sequence.attackPacket(1_052);
        assertNull(sequence.sampleRotation(0, 0, 1_100));
    }

    @Test void progressiveHumanLikeRotationDoesNotMatchSnapBack() {
        var sequence = new AttackRotationSequence();
        long now = 2_000;
        sequence.sampleRotation(0, 0, now);
        for (int i = 1; i <= 12; i++) {
            now += 50;
            sequence.sampleRotation(i * 6.0f, (i % 3) * 0.7f, now);
            if (i % 3 == 0) sequence.attackPacket(now + 2);
            now += 50;
            assertNull(sequence.sampleRotation(i * 6.0f + 2.5f, (i % 3) * 0.7f, now));
        }
    }
}

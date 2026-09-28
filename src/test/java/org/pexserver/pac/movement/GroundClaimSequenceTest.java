package org.pexserver.pac.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GroundClaimSequenceTest {
    private static final long BASE = 10_000;

    @Test void repeatedFalseClaimsOnServerSupportedGroundAreConfirmedAndRepaired() {
        var sequence = new GroundClaimSequence();
        GroundClaimSequence.Sample sample = null;
        for (int i = 0; i < 4; i++) {
            long now = BASE + i * 50L;
            double z = i * 0.28;
            sample = sequence.accept(true, 0, 64, z, false,
                    ground(now, 0, 64, z), now);
            if (i < 3) assertFalse(sample.confirmed());
        }
        assertTrue(sample.confirmed());
        assertTrue(sample.repairClaim());
        assertEquals(4, sample.falseGroundClaims());
    }

    @Test void oneLandingMismatchDoesNotConfirmAndAirClaimsAreIgnored() {
        var sequence = new GroundClaimSequence();
        var first = sequence.accept(true, 0, 64, 0, false, ground(BASE, 0, 64, 0), BASE);
        assertFalse(first.confirmed());
        var air = sequence.accept(true, 0, 64.42, 0, false,
                air(BASE + 50, 0, 64.42, 0), BASE + 50);
        assertFalse(air.confirmed());
        assertFalse(air.repairClaim());
    }

    @Test void onGroundOnlyRotationPacketsStillAccumulateAntiHungerEvidence() {
        var sequence = new GroundClaimSequence();
        sequence.accept(true, 0, 64, 0, false, ground(BASE, 0, 64, 0), BASE);
        GroundClaimSequence.Sample sample = null;
        for (int i = 1; i <= 3; i++) {
            long now = BASE + i * 50L;
            sample = sequence.accept(false, 0, 0, 0, false, ground(now, 0, 64, 0), now);
        }
        assertTrue(sample.confirmed());
        assertTrue(sample.repairClaim());
    }

    @Test void trueGroundClaimsDoNotBuildFalseClaimEvidence() {
        var sequence = new GroundClaimSequence();
        for (int i = 0; i < 8; i++) {
            long now = BASE + i * 50L;
            var sample = sequence.accept(true, 0, 64, 0.1 * i, true,
                    ground(now, 0, 64, 0.1 * i), now);
            assertFalse(sample.confirmed());
            assertFalse(sample.repairClaim());
        }
    }

    @Test void jumpOrStaleGroundSnapshotClearsTheEvidence() {
        var sequence = new GroundClaimSequence();
        sequence.accept(true, 0, 64, 0, false, ground(BASE, 0, 64, 0), BASE);
        sequence.accept(true, 0, 64, 0.1, false, ground(BASE + 50, 0, 64, 0.1), BASE + 50);
        var jump = sequence.accept(true, 0, 64.42, 0.2, false,
                air(BASE + 100, 0, 64.42, 0.2), BASE + 100);
        assertFalse(jump.confirmed());
        assertEquals(0, jump.falseGroundClaims());

        var stale = sequence.accept(true, 0, 64, 0.3, false, ground(BASE, 0, 64, 0.3), BASE + 500);
        assertFalse(stale.confirmed());
        assertEquals(0, stale.falseGroundClaims());
    }

    private static MotionEnvironment.Snapshot ground(long capturedAt, double x, double y, double z) {
        return new MotionEnvironment.Snapshot(true, false, true, false,
                0, 0.1, x, y, z, 1, capturedAt);
    }

    private static MotionEnvironment.Snapshot air(long capturedAt, double x, double y, double z) {
        return new MotionEnvironment.Snapshot(false, true, true, false,
                0, 0.1, x, y, z, 1, capturedAt);
    }
}

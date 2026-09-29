package org.pexserver.pac;

import org.junit.jupiter.api.Test;
import org.pexserver.pac.movement.RapidPositionJumpWindow;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RapidPositionJumpWindowTest {
    @Test void paperBypassPaddingThenDistantCoordinateIsRejected() {
        var window = new RapidPositionJumpWindow();
        long t = 1_000_000_000L;
        window.accept(true, 0, 64, 0, t, false, false, false, false);
        window.accept(true, 0, 64, 0, t + 5_000_000L, false, false, false, false);
        window.accept(true, 0, 64, 0, t + 10_000_000L, false, false, false, false);

        var finding = window.accept(true, 12, 64, 0, t + 15_000_000L,
                false, false, false, false);

        assertTrue(finding.impossible());
        assertTrue(finding.repeatedPositions() >= 2);
        assertTrue(finding.distance() > 8);
    }

    @Test void singleExtremeSameTickJumpIsRejected() {
        var window = new RapidPositionJumpWindow();
        long t = 1_000_000_000L;
        window.accept(true, 0, 64, 0, t, false, false, false, false);
        var finding = window.accept(true, 20, 64, 0, t + 50_000_000L,
                false, false, false, false);
        assertTrue(finding.impossible());
    }

    @Test void ordinaryMovementAndLagGapAreAccepted() {
        var window = new RapidPositionJumpWindow();
        long t = 1_000_000_000L;
        window.accept(true, 0, 64, 0, t, false, false, false, false);
        assertFalse(window.accept(true, 0.4, 64, 0, t + 50_000_000L,
                false, false, false, false).impossible());
        assertFalse(window.accept(true, 30, 64, 0, t + 500_000_000L,
                false, false, false, false).impossible());
    }

    @Test void serverTeleportAndExternalMotionResetEvidence() {
        var window = new RapidPositionJumpWindow();
        long t = 1_000_000_000L;
        window.accept(true, 0, 64, 0, t, false, false, false, false);
        assertFalse(window.accept(true, 50, 64, 0, t + 50_000_000L,
                false, true, false, false).impossible());

        window.accept(true, 0, 64, 0, t + 100_000_000L, false, false, false, false);
        assertFalse(window.accept(true, 50, 64, 0, t + 150_000_000L,
                false, false, true, false).impossible());
    }
}

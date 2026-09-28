package org.pexserver.pac.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TeleportSyncWindowTest {
    @Test void matchingAckReleasesPredictionAfterTwoHundredMilliseconds() {
        var window = new TeleportSyncWindow();
        window.sent(41, 1_000);
        assertTrue(window.suppressed(1_000));
        assertFalse(window.confirm(40, 1_050));
        assertTrue(window.suppressed(1_300));
        assertTrue(window.confirm(41, 1_350));
        assertFalse(window.confirm(41, 1_500));
        assertTrue(window.suppressed(1_549));
        assertFalse(window.suppressed(1_550));
    }

    @Test void oldAckCannotUnlockMoreRecentTeleport() {
        var window = new TeleportSyncWindow();
        window.sent(10, 2_000);
        window.sent(11, 2_100);
        assertFalse(window.confirm(10, 2_000));
        assertTrue(window.suppressed(5_000));
        assertTrue(window.confirm(11, 5_100));
        assertFalse(window.suppressed(5_300));
    }

    @Test void missingAckCannotSuppressChecksIndefinitely() {
        var window = new TeleportSyncWindow();
        window.sent(1, 1_000);
        assertTrue(window.suppressed(5_999));
        window.sent(2, 5_999);
        assertFalse(window.suppressed(6_000));
        assertTrue(window.pending());
        window.sent(3, 6_100);
        assertFalse(window.suppressed(6_100));
        assertTrue(window.confirm(3, 6_200));
        assertFalse(window.suppressed(6_200));
        assertFalse(window.pending());
        window.sent(4, 6_300);
        assertTrue(window.suppressed(6_300));
    }

    @Test void acknowledgedTeleportCanBeFollowedByNewTeleportWithoutMovementPacket() {
        var window = new TeleportSyncWindow();
        window.sent(1, 1_000);
        assertTrue(window.confirm(1, 1_100));
        window.sent(2, 10_000);
        assertTrue(window.suppressed(10_000));
        assertTrue(window.confirm(2, 10_100));
        assertFalse(window.suppressed(10_300));
    }
}

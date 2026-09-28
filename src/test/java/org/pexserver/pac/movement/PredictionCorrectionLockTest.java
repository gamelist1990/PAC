package org.pexserver.pac.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PredictionCorrectionLockTest {
    @Test void keepsRejectingSuspiciousSamplesUntilThreeLegalFramesArrive() {
        var lock = new PredictionCorrectionLock();
        var anchor = new PredictionCorrectionLock.Position(1, 64, 2);
        lock.begin(anchor);

        assertTrue(lock.reject(true, true, false));
        assertFalse(lock.reject(true, false, false));
        assertTrue(lock.reject(true, true, false));
        assertFalse(lock.reject(true, false, false));
        assertFalse(lock.reject(true, false, false));
        assertFalse(lock.reject(true, false, false));
        assertFalse(lock.active());
    }

    @Test void preservesTheFirstImmediateSetbackDuringAContinuingViolation() {
        var lock = new PredictionCorrectionLock();
        var anchor = new PredictionCorrectionLock.Position(3, 65, 7);
        lock.begin(anchor);
        lock.begin(new PredictionCorrectionLock.Position(40, 90, 40));

        assertEquals(anchor, lock.anchor());
        assertTrue(lock.reject(true, true, false));
    }

    @Test void pluginMotionReleasesTheLockAndUnknownSamplesDoNotFakeRecovery() {
        var lock = new PredictionCorrectionLock();
        lock.begin(new PredictionCorrectionLock.Position(0, 64, 0));

        assertFalse(lock.reject(false, false, false));
        assertTrue(lock.active());
        assertFalse(lock.reject(false, true, true));
        assertFalse(lock.active());
    }

    @Test void setbackBlocksOnlyNewCoordinatesDuringTeleportSynchronization() {
        var lock = new PredictionCorrectionLock();
        assertFalse(lock.holdDuringSync(true, true));
        lock.begin(new PredictionCorrectionLock.Position(0, 64, 0));
        assertTrue(lock.holdDuringSync(true, true));
        assertFalse(lock.holdDuringSync(false, true));
        assertFalse(lock.holdDuringSync(true, false));
        lock.clear();
        assertFalse(lock.holdDuringSync(true, true));
    }
}

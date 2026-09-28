package org.pexserver.pac;

import org.junit.jupiter.api.Test;
import org.pexserver.pac.movement.MotionEnvironment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MotionEnvironmentSnapshotTest {
    @Test void snapshotOnlyCoversNearbyPacketPositions() {
        var snapshot = new MotionEnvironment.Snapshot(true, false, false, false,
                0, 0.1, 10, 64, 10, 1, 0);
        assertTrue(snapshot.near(10.3, 64, 10.3));
        assertFalse(snapshot.near(12, 64, 10));
    }
}

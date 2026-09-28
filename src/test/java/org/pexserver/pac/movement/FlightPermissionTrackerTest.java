package org.pexserver.pac.movement;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlightPermissionTrackerTest {
    @Test void permissionWithoutActualFlightKeepsMovementChecksActive() {
        var tracker = new FlightPermissionTracker();
        UUID player = UUID.randomUUID();
        tracker.update(player, true, false, 0.1f, 0.05, 1_000);
        assertFalse(tracker.authorizedMovement(player, 1_050));
    }

    @Test void serverFlightAndLandingTransitionAreRecognized() {
        var tracker = new FlightPermissionTracker();
        UUID player = UUID.randomUUID();
        tracker.update(player, true, false, 0.1f, 0.05, 1_000);
        tracker.update(player, true, true, 0.2f, 0.3, 1_050);
        assertTrue(tracker.authorizedMovement(player, 1_080));
        assertEquals(0.3, tracker.get(player).attributeSpeed());
        tracker.update(player, false, false, 0.2f, 0.3, 1_100);
        assertTrue(tracker.authorizedMovement(player, 1_300));
        assertFalse(tracker.authorizedMovement(player, 1_401));
    }

    @Test void staleOrForgottenServerStateCannotExemptFlight() {
        var tracker = new FlightPermissionTracker();
        UUID player = UUID.randomUUID();
        tracker.update(player, true, true, 0.1f, 0.05, 1_000);
        assertFalse(tracker.authorizedMovement(player, 1_300));
        tracker.forget(player);
        assertFalse(tracker.authorizedMovement(player, 1_050));
    }
}

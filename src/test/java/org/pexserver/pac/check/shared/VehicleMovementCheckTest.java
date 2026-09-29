package org.pexserver.pac.check.shared;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VehicleMovementCheckTest {
    @Test void liquidBounceDefaultVehicleBoostIsRejectedAfterDismount() {
        var window = new VehicleMovementCheck.DismountBoostWindow();
        window.arm(1_000, 0, 0, 0);

        var finding = window.sample(1_050, 2.0, 1.0, 0, false);

        assertTrue(finding.impossible());
    }

    @Test void normalDismountStepAndVehicleMomentumAreAccepted() {
        var window = new VehicleMovementCheck.DismountBoostWindow();
        window.arm(1_000, 0.35, 0.1, 0);

        assertFalse(window.sample(1_050, 0.75, 0.25, 0, false).impossible());
    }

    @Test void externalMotionInvalidatesDismountEvidence() {
        var window = new VehicleMovementCheck.DismountBoostWindow();
        window.arm(1_000, 0, 0, 0);

        assertFalse(window.sample(1_050, 3.0, 2.0, 0, true).impossible());
    }

    @Test void sprintVehicleControlNeedsRepeatedExtremeMotion() {
        var window = new VehicleMovementCheck.GenericVehicleWindow();
        assertFalse(window.sample(1, 0, 64, 0, true, false).impossible());
        assertFalse(window.sample(2, 5, 66, 0, true, false).impossible());
        assertTrue(window.sample(3, 10, 68, 0, true, false).impossible());
    }

    @Test void ordinaryFastRideDoesNotTripGenericExtremeEnvelope() {
        var window = new VehicleMovementCheck.GenericVehicleWindow();
        assertFalse(window.sample(1, 0, 64, 0, false, false).impossible());
        for (int tick = 2; tick <= 8; tick++) {
            assertFalse(window.sample(tick, (tick - 1) * 0.5, 64, 0,
                    false, false).impossible());
        }
    }
}

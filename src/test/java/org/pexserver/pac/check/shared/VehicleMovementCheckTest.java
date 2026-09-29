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
    @Test void vehicleControlSprintPacketIsRejectedBeforeServerApply() {
        var finding = VehicleMovementCheck.packetFinding(
                0, 64, 0, 0.2, 0, 0,
                5.0, 66.0, 0, "HORSE");

        assertTrue(finding.evaluated());
        assertTrue(finding.impossible());
    }

    @Test void ordinaryVehiclePacketInsideConservativeEnvelopeIsAccepted() {
        var finding = VehicleMovementCheck.packetFinding(
                0, 64, 0, 0.35, 0.1, 0,
                0.65, 64.25, 0, "HORSE");

        assertTrue(finding.evaluated());
        assertFalse(finding.impossible());
    }

    @Test void legitimateHighServerVelocityExpandsVehiclePacketEnvelope() {
        var finding = VehicleMovementCheck.packetFinding(
                0, 64, 0, 2.0, 0.7, 0,
                5.5, 65.9, 0, "MINECART");

        assertFalse(finding.impossible(),
                "server-authoritative vehicle momentum must expand the packet envelope");
    }

    @Test void repeatedVehiclePacketsWithoutServerControlBecomeEvidence() {
        var window = new VehicleMovementCheck.UnauthorizedControlWindow();
        var vehicle = java.util.UUID.randomUUID();

        assertFalse(window.sample(vehicle, true, 1_000));
        assertTrue(window.sample(vehicle, true, 1_050));
    }

    @Test void authorizedControlAndVehicleChangesResetUnauthorizedEvidence() {
        var window = new VehicleMovementCheck.UnauthorizedControlWindow();
        var first = java.util.UUID.randomUUID();
        var second = java.util.UUID.randomUUID();

        assertFalse(window.sample(first, true, 1_000));
        assertFalse(window.sample(first, false, 1_050));
        assertFalse(window.sample(first, true, 1_100));
        assertFalse(window.sample(second, true, 1_150));
        assertTrue(window.sample(second, true, 1_200));
    }

    @Test void constantVehicleControlLiftViolatesLivingVehicleGravity() {
        var window = new VehicleMovementCheck.LivingVehicleAirWindow();
        var vehicle = java.util.UUID.randomUUID();
        long now = 1_000;
        double y = 64.0;
        boolean detected = false;

        assertFalse(window.sample(vehicle, true, y, 0.08, 0.98f, now).impossible());
        for (int tick = 0; tick < 6 && !detected; tick++) {
            y += 0.35;
            detected = window.sample(vehicle, true, y, 0.08, 0.98f, now += 50).impossible();
        }
        assertTrue(detected, "constant client lift must diverge from living-vehicle gravity quickly");
    }

    @Test void vanillaLivingVehicleFallRecurrenceStaysLegal() {
        var window = new VehicleMovementCheck.LivingVehicleAirWindow();
        var vehicle = java.util.UUID.randomUUID();
        long now = 2_000;
        double y = 70.0;
        double dy = 0.30;

        assertFalse(window.sample(vehicle, true, y, 0.08, 0.98f, now).impossible());
        y += dy;
        assertFalse(window.sample(vehicle, true, y, 0.08, 0.98f, now += 50).impossible());

        for (int tick = 0; tick < 8; tick++) {
            dy = (dy - 0.08) * (double) 0.98f;
            y += dy;
            assertFalse(window.sample(vehicle, true, y, 0.08, 0.98f, now += 50).impossible());
        }
    }

    @Test void liquidBounceVehicleGlideConstantDescentAlsoDiverges() {
        var window = new VehicleMovementCheck.LivingVehicleAirWindow();
        var vehicle = java.util.UUID.randomUUID();
        long now = 3_000;
        double y = 70.0;
        boolean detected = false;

        assertFalse(window.sample(vehicle, true, y, 0.08, 0.98f, now).impossible());
        for (int tick = 0; tick < 6 && !detected; tick++) {
            y -= 0.15;
            detected = window.sample(vehicle, true, y, 0.08, 0.98f, now += 50).impossible();
        }
        assertTrue(detected, "constant glide descent must diverge from vanilla gravity recurrence");
    }

    @Test void constantMinecartGlideViolatesVanillaAirRecurrence() {
        var window = new VehicleMovementCheck.LivingVehicleAirWindow();
        var vehicle = java.util.UUID.randomUUID();
        long now = 4_000;
        double y = 70.0;
        boolean detected = false;

        assertFalse(window.sample(vehicle, true, y, 0.04, 0.95f, now).impossible());
        for (int tick = 0; tick < 7 && !detected; tick++) {
            y -= 0.15;
            detected = window.sample(vehicle, true, y, 0.04, 0.95f, now += 50).impossible();
        }
        assertTrue(detected, "small minecart gravity residuals must accumulate instead of bypassing");
    }

    @Test void queuedVehiclePacketResetsVerticalRecurrenceEvidence() {
        var window = new VehicleMovementCheck.LivingVehicleAirWindow();
        var vehicle = java.util.UUID.randomUUID();

        window.sample(vehicle, true, 64.0, 0.08, 0.98f, 1_000);
        window.sample(vehicle, true, 64.35, 0.08, 0.98f, 1_050);
        assertFalse(window.sample(vehicle, true, 64.36, 0.08, 0.98f, 1_055).impossible());
        assertFalse(window.sample(vehicle, true, 64.70, 0.08, 0.98f, 1_105).impossible());
    }

}

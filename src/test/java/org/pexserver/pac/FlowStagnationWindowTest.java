package org.pexserver.pac;

import org.junit.jupiter.api.Test;
import org.pexserver.pac.movement.FlowStagnationWindow;
import org.pexserver.pac.movement.MotionPredictor;
import org.pexserver.pac.movement.WaterFlowEnvironment;
import org.pexserver.pac.packet.JavaInputCapture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowStagnationWindowTest {
    private static JavaInputCapture.Window idle() {
        return new JavaInputCapture.Window(new MotionPredictor.Input(
                false, false, false, false, false, false, false), null);
    }

    @Test void strongWaterCurrentRequiresRepeatedUnopposedStagnation() {
        var window = new FlowStagnationWindow();
        for (int step = 0; step < 12; step++) {
            long at = 1000 + step * 50L;
            assertFalse(window.accept(true, 0, 0, idle(),
                    new WaterFlowEnvironment.Snapshot(0, 64, 0, 1, 0, at), at));
        }
        long at = 1600;
        assertTrue(window.accept(true, 0, 0, idle(),
                new WaterFlowEnvironment.Snapshot(0, 64, 0, 1, 0, at), at));
    }

    @Test void noInputPacketYetStillRepresentsIdleModernClient() {
        var window = new FlowStagnationWindow();
        boolean detected = false;
        for (int step = 0; step < 14; step++) {
            long at = 1_000 + step * 50L;
            detected |= window.accept(true, 0, 0, null,
                    new WaterFlowEnvironment.Snapshot(0, 64, 0, 1, 0, at), at);
        }
        assertTrue(detected);
    }

    @Test void activeCounterSteeringDoesNotClaimFluidSuppression() {
        var window = new FlowStagnationWindow();
        var active = new JavaInputCapture.Window(new MotionPredictor.Input(
                false, false, true, false, false, false, false), null);
        for (int step = 0; step < 20; step++) {
            long at = 1000 + step * 50L;
            assertFalse(window.accept(true, 0, 0, active,
                    new WaterFlowEnvironment.Snapshot(0, 64, 0, 1, 0, at), at));
        }
    }

    @Test void vanillaMinimumFluidPushIsNotMistakenForStagnation() {
        var window = new FlowStagnationWindow();
        double x = 0;
        for (int step = 0; step < 30; step++) {
            long at = 1000 + step * 50L;
            x += 0.0045;
            assertFalse(window.accept(true, x, 0, idle(),
                    new WaterFlowEnvironment.Snapshot(x, 64, 0, 1, 0, at), at));
        }
    }

    @Test void crossStreamMovementStillNeedsFluidDrift() {
        var window = new FlowStagnationWindow();
        var crossing = new JavaInputCapture.Window(new MotionPredictor.Input(
                true, false, false, false, false, false, false), null);
        double z = 0;
        boolean detected = false;
        for (int step = 0; step < 24; step++) {
            long at = 1_000 + step * 50L;
            z += 0.08;
            detected |= window.accept(true, 0, z, 0, crossing,
                    new WaterFlowEnvironment.Snapshot(0, 64, z, 1, 0, at), at);
        }
        assertTrue(detected);
    }

    @Test void normalCrossStreamDriftDoesNotFlag() {
        var window = new FlowStagnationWindow();
        var crossing = new JavaInputCapture.Window(new MotionPredictor.Input(
                true, false, false, false, false, false, false), null);
        double x = 0, z = 0;
        for (int step = 0; step < 30; step++) {
            long at = 1_000 + step * 50L;
            x += 0.0045; z += 0.08;
            assertFalse(window.accept(true, x, z, 0, crossing,
                    new WaterFlowEnvironment.Snapshot(x, 64, z, 1, 0, at), at));
        }
    }
}

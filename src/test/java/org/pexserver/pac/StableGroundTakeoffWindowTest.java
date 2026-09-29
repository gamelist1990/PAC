package org.pexserver.pac;

import org.junit.jupiter.api.Test;
import org.pexserver.pac.movement.MotionEnvironment;
import org.pexserver.pac.movement.StableGroundTakeoffWindow;
import org.pexserver.pac.movement.ground.GroundStateService;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StableGroundTakeoffWindowTest {
    private static MotionEnvironment.Snapshot environment(double x, double y, int tick, long at) {
        return new MotionEnvironment.Snapshot(false, false, false, false, false,
                0, 0.1, x, y, 0, tick, at, false, false);
    }

    private static GroundStateService.GroundState ground(int tick, int consecutive) {
        return new GroundStateService.GroundState(true, true, 64, tick, consecutive, false);
    }

    private static GroundStateService.GroundState air(int tick) {
        return new GroundStateService.GroundState(true, false, Double.NaN, tick, 0, false);
    }

    @Test void ordinaryJumpFromStableGroundIsAccepted() {
        var window = new StableGroundTakeoffWindow();
        window.accept(true, 0, 64, 0, environment(0, 64, 10, 1_000),
                ground(10, 3), 0.42, 0.6, false, 1_000);

        var jump = window.accept(true, 0, 64.42, 0,
                environment(0, 64.42, 11, 1_050), air(11),
                Double.NaN, 0.6, false, 1_050);

        assertTrue(jump.evaluated());
        assertFalse(jump.excessive());
    }

    @Test void liquidBounceStyleBlockBounceIsRejected() {
        var window = new StableGroundTakeoffWindow();
        window.accept(true, 0, 64, 0, environment(0, 64, 10, 1_000),
                ground(10, 3), 0.42, 0.6, false, 1_000);

        var boosted = window.accept(true, 0, 64.84, 0,
                environment(0, 64.84, 11, 1_050), air(11),
                Double.NaN, 0.6, false, 1_050);

        assertTrue(boosted.evaluated());
        assertTrue(boosted.excessive());
    }

    @Test void honeyJumpFactorStillAllowsDetectingDefaultAddedBounce() {
        var window = new StableGroundTakeoffWindow();
        // Example: support block reduces the legal 0.42 jump to 0.21, while
        // the client adds another 0.42 locally.
        window.accept(true, 0, 64, 0, environment(0, 64, 20, 2_000),
                ground(20, 4), 0.21, 0.6, false, 2_000);

        var boosted = window.accept(true, 0, 64.63, 0,
                environment(0, 64.63, 21, 2_050), air(21),
                Double.NaN, 0.6, false, 2_050);

        assertTrue(boosted.excessive());
    }

    @Test void legalVanillaStepHeightIsNotAJumpViolation() {
        var window = new StableGroundTakeoffWindow();
        window.accept(true, 0, 64, 0, environment(0, 64, 30, 3_000),
                ground(30, 5), 0.42, 0.6, false, 3_000);

        var step = window.accept(true, 0.2, 64.6, 0,
                environment(0.2, 64.6, 31, 3_050), ground(31, 1),
                0.42, 0.6, false, 3_050);

        assertTrue(step.evaluated());
        assertFalse(step.excessive());
    }

    @Test void naturalLandingBounceIsNotArmedFromOneGroundTick() {
        var window = new StableGroundTakeoffWindow();
        window.accept(true, 0, 64, 0, environment(0, 64, 40, 4_000),
                ground(40, 1), 0.42, 0.6, false, 4_000);

        var bounce = window.accept(true, 0, 65.2, 0,
                environment(0, 65.2, 41, 4_050), air(41),
                Double.NaN, 0.6, false, 4_050);

        assertFalse(bounce.evaluated());
        assertFalse(bounce.excessive());
    }

    @Test void queuedSecondVanillaJumpFrameIsNotReclassifiedAsFreshTakeoff() {
        var window = new StableGroundTakeoffWindow();
        // First upward packet arrives while the last main-thread ground sample is
        // still current. It is legal and consumes that ground tick.
        var first = window.accept(true, 0, 64.42, 0,
                environment(0, 64, 50, 5_000), ground(50, 4),
                0.42, 0.6, false, 5_010);
        assertTrue(first.evaluated());
        assertFalse(first.excessive());

        var second = window.accept(true, 0, 64.7532, 0,
                environment(0, 64, 50, 5_000), ground(50, 4),
                0.42, 0.6, false, 5_011);
        assertFalse(second.evaluated());
    }

    @Test void delayedServerTimingDisablesStableGroundTakeoffEvidence() {
        var window = new StableGroundTakeoffWindow();
        var sample = window.accept(true, 0, 64.84, 0,
                environment(0, 64, 60, 6_000), ground(60, 5),
                0.42, 0.6, true, 6_010);
        assertFalse(sample.evaluated());
    }
}

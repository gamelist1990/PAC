package org.pexserver.pac;

import org.junit.jupiter.api.Test;
import org.pexserver.pac.movement.MotionEnvironment;
import org.pexserver.pac.movement.VerticalSurfaceMotionWindow;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VerticalSurfaceMotionWindowTest {
    @Test void liquidBounceBlockBounceNeedsTwoExcessSpecialSurfaceJumps() {
        var window = new VerticalSurfaceMotionWindow();

        assertEquals(VerticalSurfaceMotionWindow.Anomaly.NONE,
                window.accept(true, 0, 64.0, 0, specialGround(64.0, 0.21f, 0.0f, 1000),
                        1000, false).anomaly());

        // Honey jumpFactor=0.5 makes a normal 0.42 jump about 0.21 high.
        // LiquidBounce BlockBounce adds its default +0.42 motion on top.
        assertEquals(VerticalSurfaceMotionWindow.Anomaly.NONE,
                window.accept(true, 0, 64.64, 0, air(64.64, 1050), 1050, false).anomaly());

        window.accept(true, 0, 64.0, 0, specialGround(64.0, 0.21f, 0.0f, 1100),
                1100, false);
        var second = window.accept(true, 0, 64.64, 0, air(64.64, 1150),
                1150, false);

        assertEquals(VerticalSurfaceMotionWindow.Anomaly.EXCESS_SPECIAL_SURFACE_JUMP,
                second.anomaly());
    }

    @Test void legalHoneyJumpDoesNotAccumulateBlockBounceEvidence() {
        var window = new VerticalSurfaceMotionWindow();
        window.accept(true, 0, 64.0, 0, specialGround(64.0, 0.21f, 0.0f, 1000),
                1000, false);

        for (int cycle = 0; cycle < 4; cycle++) {
            long up = 1050 + cycle * 100L;
            long down = up + 50;
            assertEquals(VerticalSurfaceMotionWindow.Anomaly.NONE,
                    window.accept(true, 0, 64.21, 0, air(64.21, up), up, false).anomaly());
            assertEquals(VerticalSurfaceMotionWindow.Anomaly.NONE,
                    window.accept(true, 0, 64.0, 0,
                            specialGround(64.0, 0.21f, 0.0f, down), down, false).anomaly());
        }
    }

    @Test void antiBounceRequiresTwoIndependentSuppressedRestitutions() {
        var window = new VerticalSurfaceMotionWindow();

        // First fall -> slime landing -> client illegally stays flat instead of bouncing.
        window.accept(true, 0, 65.0, 0, air(65.0, 1000), 1000, false);
        window.accept(true, 0, 64.55, 0, air(64.55, 1050), 1050, false);
        window.accept(true, 0, 64.0, 0, specialGround(64.0, 0.42f, 1.0f, 1100),
                1100, false);
        assertEquals(VerticalSurfaceMotionWindow.Anomaly.NONE,
                window.accept(true, 0, 64.0, 0,
                        specialGround(64.0, 0.42f, 1.0f, 1150), 1150, false).anomaly());

        // A later independent fall repeats the same impossible suppression.
        window.accept(true, 0, 65.0, 0, air(65.0, 1200), 1200, false);
        window.accept(true, 0, 64.55, 0, air(64.55, 1250), 1250, false);
        window.accept(true, 0, 64.0, 0, specialGround(64.0, 0.42f, 1.0f, 1300),
                1300, false);
        var finding = window.accept(true, 0, 64.0, 0,
                specialGround(64.0, 0.42f, 1.0f, 1350), 1350, false);

        assertEquals(VerticalSurfaceMotionWindow.Anomaly.BOUNCE_SUPPRESSION,
                finding.anomaly());
    }

    @Test void sneakingMayLegitimatelySuppressSlimeBounce() {
        var window = new VerticalSurfaceMotionWindow();
        window.accept(true, 0, 65.0, 0, air(65.0, 1000), 1000, false);
        window.accept(true, 0, 64.5, 0, air(64.5, 1050), 1050, false);

        for (int cycle = 0; cycle < 3; cycle++) {
            long land = 1100 + cycle * 150L;
            window.accept(true, 0, 64.0, 0,
                    specialGround(64.0, 0.42f, 1.0f, land).withSprinting(false, 0.1),
                    land, false);
            // Snapshot constructor helper below returns non-sneaking, so use
            // a dedicated sneaking snapshot for the flat post-landing frame.
            assertEquals(VerticalSurfaceMotionWindow.Anomaly.NONE,
                    window.accept(true, 0, 64.0, 0,
                            sneakingSpecialGround(64.0, 0.42f, 1.0f, land + 50),
                            land + 50, false).anomaly());
            window.accept(true, 0, 64.5, 0, air(64.5, land + 100), land + 100, false);
        }
    }

    private static MotionEnvironment.Snapshot specialGround(
            double y, float legalJump, float restitution, long at) {
        return new MotionEnvironment.Snapshot(true, false, false, false,
                0, 0.1, 0, y, 0, (int) (at / 50), at)
                .withSurfaceVerticalPhysics(legalJump, restitution);
    }

    private static MotionEnvironment.Snapshot sneakingSpecialGround(
            double y, float legalJump, float restitution, long at) {
        return new MotionEnvironment.Snapshot(true, false, false, true,
                0, 0.1, 0, y, 0, (int) (at / 50), at)
                .withSurfaceVerticalPhysics(legalJump, restitution);
    }

    private static MotionEnvironment.Snapshot air(double y, long at) {
        return new MotionEnvironment.Snapshot(false, true, false, false,
                0, 0.1, 0, y, 0, (int) (at / 50), at);
    }
}

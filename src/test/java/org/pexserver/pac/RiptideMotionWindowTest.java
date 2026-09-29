package org.pexserver.pac;

import org.junit.jupiter.api.Test;
import org.pexserver.pac.movement.RiptideMotionWindow;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiptideMotionWindowTest {
    @Test void vanillaRiptideImpulseStaysInsideAuthoritativeEnvelope() {
        var window = new RiptideMotionWindow();
        window.grant(0, 64, 0, 0, 0.2, 1.6, 1000);

        var first = window.accept(true, 0, 64.18, 1.55,
                1050, false, false);
        var second = window.accept(true, 0, 64.31, 3.02,
                1100, false, false);

        assertTrue(first.evaluated());
        assertFalse(first.excessive());
        assertFalse(second.excessive());
    }

    @Test void doubledHorizontalTridentBoostFlagsOnRepeatedExcess() {
        var window = new RiptideMotionWindow();
        window.grant(0, 64, 0, 0, 0, 1.5, 1000);

        var first = window.accept(true, 0, 64, 3.0,
                1050, false, false);
        var second = window.accept(true, 0, 64, 5.8,
                1100, false, false);

        assertFalse(first.excessive());
        assertTrue(second.excessive());
        assertTrue(second.horizontalPerFrame() > second.allowedHorizontal());
    }

    @Test void doubledVerticalTridentBoostAlsoFlags() {
        var window = new RiptideMotionWindow();
        window.grant(0, 64, 0, 0, 1.2, 0.2, 1000);

        var first = window.accept(true, 0, 66.4, 0.2,
                1050, false, false);
        var second = window.accept(true, 0, 68.6, 0.4,
                1100, false, false);

        assertFalse(first.excessive());
        assertTrue(second.excessive());
        assertTrue(second.verticalPerFrame() > second.allowedVertical());
    }

    @Test void uncertainTimingOrExternalVelocityInvalidatesWindow() {
        var window = new RiptideMotionWindow();
        window.grant(0, 64, 0, 0, 0, 1.5, 1000);
        assertFalse(window.accept(true, 0, 64, 3.0,
                1050, true, false).evaluated());
        assertFalse(window.active());

        window.grant(0, 64, 0, 0, 0, 1.5, 1100);
        assertFalse(window.accept(true, 0, 64, 3.0,
                1150, false, true).evaluated());
        assertFalse(window.active());
    }

    @Test void twoTickAggregatedMovementIsNormalizedPerPhysicsFrame() {
        var window = new RiptideMotionWindow();
        window.grant(0, 64, 0, 0, 0, 1.5, 1000);

        var sample = window.accept(true, 0, 64, 3.0,
                1100, false, false);

        assertTrue(sample.evaluated());
        assertFalse(sample.excessive());
    }
}

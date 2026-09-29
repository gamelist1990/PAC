package org.pexserver.pac.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StableBounceGroundPolicyTest {
    @Test void landingTickOnBouncyBlockRemainsExcluded() {
        assertFalse(MotionEnvironment.stableBounceGroundPredictable(0.8f, 1));
    }

    @Test void stableStandingOnBouncyBlockCanUseHorizontalGroundPrediction() {
        assertTrue(MotionEnvironment.stableBounceGroundPredictable(0.8f, 2));
        assertTrue(MotionEnvironment.stableBounceGroundPredictable(0.8f, 8));
    }

    @Test void OrdinaryNonBouncyGroundIsAlwaysPredictable() {
        assertTrue(MotionEnvironment.stableBounceGroundPredictable(0.0f, 1));
    }
}

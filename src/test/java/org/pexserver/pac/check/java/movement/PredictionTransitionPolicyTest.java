package org.pexserver.pac.check.java.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PredictionTransitionPolicyTest {
    @Test void airDashVelocityCannotOverrideCancelledFlightTransition() {
        assertTrue(PredictionTransitionPolicy.suspendForAuthorizedFlight(true, true, true));
        assertTrue(PredictionTransitionPolicy.suspendForAuthorizedFlight(true, false, true));
    }

    @Test void ordinaryAuthorizedFlightTransitionSuspendsPrediction() {
        assertTrue(PredictionTransitionPolicy.suspendForAuthorizedFlight(true, false, false));
    }

    @Test void standaloneCombatImpulseRemainsVisibleDuringTransition() {
        assertFalse(PredictionTransitionPolicy.suspendForAuthorizedFlight(true, true, false));
    }

    @Test void normalMovementIsNeverSuspendedByThisPolicy() {
        assertFalse(PredictionTransitionPolicy.suspendForAuthorizedFlight(false, false, false));
        assertFalse(PredictionTransitionPolicy.suspendForAuthorizedFlight(false, true, true));
    }
}

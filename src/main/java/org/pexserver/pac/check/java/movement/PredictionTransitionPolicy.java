package org.pexserver.pac.check.java.movement;

/**
 * Decides whether ordinary movement prediction must pause during a
 * server-authorized client flight transition.
 *
 * <p>Cancelled double-jump handlers such as AirDash briefly put the Java
 * client into flight movement before the server correction arrives. When that
 * transition is paired with a non-combat server-motion grant, the grant remains
 * available after the transition and can safely re-seed prediction then.
 *
 * <p>A standalone external impulse without a grant is kept visible so combat
 * knockback response is not discarded.
 */
final class PredictionTransitionPolicy {
    private PredictionTransitionPolicy() { }

    static boolean suspendForAuthorizedFlight(boolean authorizedFlightMovement,
                                               boolean externalImpulsePresent,
                                               boolean serverMotionGrantPresent) {
        if (!authorizedFlightMovement) return false;
        return serverMotionGrantPresent || !externalImpulsePresent;
    }
}

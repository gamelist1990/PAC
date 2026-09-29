package org.pexserver.pac.movement;

/** Vanilla 26.2+ vertical restitution helper for player landings. */
public final class SurfaceBouncePredictor {
    private SurfaceBouncePredictor() { }

    public static boolean shouldBounce(double incomingY, double gravity,
                                       boolean slowFalling, float restitution,
                                       boolean suppressingBounce) {
        if (!Double.isFinite(incomingY) || !Double.isFinite(gravity)
                || !Float.isFinite(restitution) || restitution <= 0.0f
                || suppressingBounce || incomingY >= 0.0) return false;
        double effectiveGravity = slowFalling ? Math.min(gravity, 0.01) : gravity;
        return -incomingY >= effectiveGravity;
    }

    /**
     * Returns the movement Y that should be used by the next physics step after
     * a downward collision. The formula mirrors Entity#restituteMovementAfterCollisions
     * followed by LivingEntity#travelInAir gravity and vertical drag.
     */
    public static double nextDisplacementAfterBounce(double incomingY, double clippedY,
                                                     double gravity, float verticalDrag,
                                                     boolean slowFalling, float restitution) {
        if (!shouldBounce(incomingY, gravity, slowFalling, restitution, false)
                || !Double.isFinite(clippedY) || !Float.isFinite(verticalDrag))
            return Double.NaN;
        double effectiveGravity = slowFalling ? Math.min(gravity, 0.01) : gravity;
        double portionWithMovement = clippedY / incomingY;
        if (!Double.isFinite(portionWithMovement)
                || portionWithMovement < -1.0e-6 || portionWithMovement > 1.000001)
            return Double.NaN;
        double gravityCompensation = portionWithMovement * effectiveGravity;
        double bouncedY = (gravityCompensation - incomingY) * restitution;
        // After the collision restitution is applied, positive motion uses the
        // normal gravity value even when Slow Falling is active.
        return (bouncedY - gravity) * verticalDrag;
    }
}

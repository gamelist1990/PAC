package org.pexserver.pac.movement;

/** Vertical free-flight recurrence for ordinary Java Edition gravity and drag. */
public final class AirPredictor {
    private static final double GRAVITY = 0.08;
    private static final double DRAG = (double) 0.98f;

    private AirPredictor() { }

    public static double nextDisplacement(double previousDisplacement) {
        return (previousDisplacement - GRAVITY) * DRAG;
    }

    public static double nextDisplacement(double previousDisplacement,
                                          double gravity, float verticalDrag) {
        return (previousDisplacement - gravity) * verticalDrag;
    }

    public static double nextDisplacement(double previousDisplacement,
                                          double gravity, float verticalDrag,
                                          boolean slowFalling) {
        return nextDisplacement(previousDisplacement, gravity, verticalDrag,
                slowFalling, -1);
    }

    public static double nextDisplacement(double previousDisplacement,
                                          double gravity, float verticalDrag,
                                          boolean slowFalling, int levitationAmplifier) {
        if (levitationAmplifier >= 0) {
            double target = 0.05 * (levitationAmplifier + 1);
            return (previousDisplacement + (target - previousDisplacement) * 0.2)
                    * verticalDrag;
        }
        double effectiveGravity = slowFalling && previousDisplacement <= 0
                ? Math.min(gravity, 0.01) : gravity;
        return (previousDisplacement - effectiveGravity) * verticalDrag;
    }

    public static double offset(double previousDisplacement, double actualDisplacement) {
        return Math.abs(actualDisplacement - nextDisplacement(previousDisplacement));
    }

    public static double offset(double previousDisplacement, double actualDisplacement,
                                double gravity, float verticalDrag) {
        return Math.abs(actualDisplacement - nextDisplacement(previousDisplacement, gravity, verticalDrag));
    }

    public static double offset(double previousDisplacement, double actualDisplacement,
                                double gravity, float verticalDrag, boolean slowFalling) {
        return offset(previousDisplacement, actualDisplacement,
                gravity, verticalDrag, slowFalling, -1);
    }

    public static double offset(double previousDisplacement, double actualDisplacement,
                                double gravity, float verticalDrag, boolean slowFalling,
                                int levitationAmplifier) {
        return Math.abs(actualDisplacement - nextDisplacement(
                previousDisplacement, gravity, verticalDrag,
                slowFalling, levitationAmplifier));
    }
}

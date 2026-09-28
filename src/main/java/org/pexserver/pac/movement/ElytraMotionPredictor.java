package org.pexserver.pac.movement;

/** Vanilla 26.3 fall-flying velocity recurrence, mirrored from LivingEntity. */
public final class ElytraMotionPredictor {
    private ElytraMotionPredictor() { }

    public static MotionPredictor.Motion next(MotionPredictor.Motion velocity,
                                              float yaw, float pitch, double gravity) {
        return next(velocity, yaw, pitch, gravity, false);
    }

    public static MotionPredictor.Motion next(MotionPredictor.Motion velocity,
                                              float yaw, float pitch, double gravity,
                                              boolean slowFalling) {
        double yawRadians = Math.toRadians(yaw);
        double pitchRadians = Math.toRadians(pitch);
        double lookX = -Math.sin(yawRadians) * Math.cos(pitchRadians);
        double lookZ = Math.cos(yawRadians) * Math.cos(pitchRadians);
        double lookHorizontal = Math.hypot(lookX, lookZ);
        double horizontalSpeed = Math.hypot(velocity.dx(), velocity.dz());
        double pitchCosineSquared = Math.cos(pitchRadians) * Math.cos(pitchRadians);

        double effectiveGravity = slowFalling && velocity.dy() <= 0
                ? Math.min(gravity, 0.01) : gravity;
        double x = velocity.dx();
        double y = velocity.dy() + effectiveGravity * (-1 + pitchCosineSquared * 0.75);
        double z = velocity.dz();
        if (y < 0 && lookHorizontal > 0) {
            double lift = y * -0.1 * pitchCosineSquared;
            x += lookX * lift / lookHorizontal;
            y += lift;
            z += lookZ * lift / lookHorizontal;
        }
        if (pitchRadians < 0 && lookHorizontal > 0) {
            double climb = horizontalSpeed * -Math.sin(pitchRadians) * 0.04;
            x += -lookX * climb / lookHorizontal;
            y += climb * 3.2;
            z += -lookZ * climb / lookHorizontal;
        }
        if (lookHorizontal > 0) {
            x += (lookX / lookHorizontal * horizontalSpeed - x) * 0.1;
            z += (lookZ / lookHorizontal * horizontalSpeed - z) * 0.1;
        }
        return new MotionPredictor.Motion(x * 0.99, y * 0.98, z * 0.99);
    }
}

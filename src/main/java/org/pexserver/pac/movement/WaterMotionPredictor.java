package org.pexserver.pac.movement;

import org.pexserver.pac.packet.JavaInputCapture;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** One-step simulation for a submerged player, including fluid currents and collisions. */
public final class WaterMotionPredictor {
    public record Result(double offset, MotionPredictor.Motion expected) { }
    private record Control(MotionPredictor.Input input, boolean sprinting) { }

    private WaterMotionPredictor() { }

    public static Result predict(MotionPredictor.Motion previous, MotionPredictor.Motion actual,
                                 float yaw, WaterMotionEnvironment.Snapshot environment,
                                 JavaInputCapture.Window inputs, double startX, double startY, double startZ,
                                 MotionCollisionSnapshot collisions) {
        if (previous == null || actual == null || environment == null || collisions == null
                || !collisions.complete() || !Double.isFinite(yaw)) return null;
        List<Control> controls = controls(inputs, environment.sprinting());
        double best = Double.POSITIVE_INFINITY;
        MotionPredictor.Motion bestExpected = null;
        MotionCollisionSnapshot.MoveBuffer moves = MotionCollisionSnapshot.predictionBuffer();
        for (Control control : controls) {
            MotionPredictor.Motion free = step(previous, yaw, environment,
                    control.input(), control.sprinting());
            int moveCount = collisions.resolveInto(startX, startY, startZ,
                    free.dx(), free.dy(), free.dz(), false, moves);
            for (int moveIndex = 0; moveIndex < moveCount; moveIndex++) {
                double moveX = moves.x(moveIndex), moveY = moves.y(moveIndex), moveZ = moves.z(moveIndex);
                double horizontal = MotionCollisionSnapshot.horizontalResidual(
                        actual.dx() - moveX, actual.dz() - moveZ,
                        collisions.entityPushHorizontalAllowance());
                double offset = Math.hypot(horizontal, actual.dy() - moveY);
                if (offset < best) {
                    best = offset;
                    bestExpected = new MotionPredictor.Motion(moveX, moveY, moveZ);
                }
            }
        }
        return Double.isFinite(best) ? new Result(best, bestExpected) : null;
    }

    static MotionPredictor.Motion step(MotionPredictor.Motion previous, float yaw,
                                       WaterMotionEnvironment.Snapshot environment,
                                       MotionPredictor.Input input, boolean sprinting) {
        double efficiency = environment.movementEfficiency();
        float drag = sprinting ? 0.9f : 0.8f;
        if (efficiency > 0) drag += (0.54600006f - drag) * (float) efficiency;
        if (environment.dolphinsGrace()) drag = 0.96f;

        float acceleration = 0.02f;
        if (efficiency > 0) {
            acceleration += ((float) environment.movementSpeed() - acceleration) * (float) efficiency;
        }
        float sneakScale = environment.sneaking() ? environment.sneakingSpeed() : 1.0f;
        MotionPredictor.ClientAxes axes = MotionPredictor.clientAxes(input.forwardAxis(),
                input.strafeAxis(), sneakScale, environment.itemUseMultiplier());
        double radians = Math.toRadians(yaw);
        double carriedX = previous.dx() * drag;
        double carriedZ = previous.dz() * drag;
        double currentX = environment.currentX();
        double currentY = environment.currentY();
        double currentZ = environment.currentZ();
        double currentLength = Math.sqrt(square(currentX) + square(currentY) + square(currentZ));
        if (Math.abs(carriedX) < 0.003 && Math.abs(carriedZ) < 0.003
                && currentLength > 0 && currentLength < 0.0045) {
            double scale = 0.0045 / currentLength;
            currentX *= scale;
            currentY *= scale;
            currentZ *= scale;
        }
        double dx = carriedX + currentX
                + (axes.strafe() * Math.cos(radians) - axes.forward() * Math.sin(radians)) * acceleration;
        double dz = carriedZ + currentZ
                + (axes.forward() * Math.cos(radians) + axes.strafe() * Math.sin(radians)) * acceleration;

        double dy = previous.dy() * 0.8f;
        double gravity = environment.slowFalling() && previous.dy() <= 0
                ? Math.min(environment.gravity(), 0.01) : environment.gravity();
        if (gravity != 0 && !sprinting) {
            boolean falling = previous.dy() <= 0;
            if (falling && Math.abs(dy - 0.005) < 0.003
                    && Math.abs(dy - gravity / 16) < 0.003) dy = -0.003;
            else dy -= gravity / 16;
        }
        dy += currentY;
        // jumpInLiquid adds this impulse before the current movement step.
        if (input.jump()) dy += 0.04f;
        return new MotionPredictor.Motion(dx, dy, dz);
    }

    private static List<Control> controls(JavaInputCapture.Window inputs, boolean serverSprinting) {
        LinkedHashSet<MotionPredictor.Input> candidates = new LinkedHashSet<>();
        if (inputs != null && inputs.current() != null) {
            candidates.add(inputs.current());
            if (inputs.previous() != null) candidates.add(inputs.previous());
        } else {
            for (int forward = -1; forward <= 1; forward++) {
                for (int strafe = -1; strafe <= 1; strafe++) {
                    for (boolean jump : new boolean[] {false, true}) {
                        candidates.add(new MotionPredictor.Input(forward > 0, forward < 0,
                                strafe > 0, strafe < 0, jump, false, false));
                    }
                }
            }
        }
        List<Control> result = new ArrayList<>(candidates.size() * 2);
        for (MotionPredictor.Input input : candidates) {
            result.add(new Control(input, serverSprinting));
            if (input.sprint() != serverSprinting) result.add(new Control(input, input.sprint()));
        }
        return List.copyOf(result);
    }

    private static double square(double value) { return value * value; }
}

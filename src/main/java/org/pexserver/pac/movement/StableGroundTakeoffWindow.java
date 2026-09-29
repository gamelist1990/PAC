package org.pexserver.pac.movement;

import org.pexserver.pac.movement.ground.GroundStateService;

/**
 * Checks only the first upward coordinate after a player has been stably
 * supported for multiple server ticks. This covers client-only jump boosts on
 * bouncy/slow terrain without confusing a natural landing bounce with a jump.
 */
public final class StableGroundTakeoffWindow {
    private static final long MAX_ARM_MILLIS = 250L;
    private static final double JUMP_MARGIN = 0.07;
    private static final double STEP_MARGIN = 0.025;

    public record Sample(boolean evaluated, boolean excessive, double rise,
                         double allowedJump, double allowedStep) {
        static Sample skipped() { return new Sample(false, false, 0, 0, 0); }
    }

    private boolean armed;
    private int armedGroundTick = -1;
    private int consumedGroundTick = -1;
    private double x, y, z;
    private double allowedJump, allowedStep;
    private long armedAt;

    public Sample accept(boolean hasPosition, double packetX, double packetY, double packetZ,
                         MotionEnvironment.Snapshot environment,
                         GroundStateService.GroundState ground,
                         double legalJumpVelocity, double maxStepHeight,
                         boolean serverDelayed, long now) {
        if (!hasPosition) {
            expire(now);
            return Sample.skipped();
        }
        if (!finite(packetX, packetY, packetZ)) {
            reset();
            return Sample.skipped();
        }

        if (armed) {
            if (now < armedAt || now - armedAt > MAX_ARM_MILLIS) {
                resetArm();
            } else {
                double rise = packetY - y;
                double horizontal = Math.hypot(packetX - x, packetZ - z);
                if (rise > 0.035) {
                    int tick = armedGroundTick;
                    double jump = allowedJump;
                    double step = allowedStep;
                    resetArm();
                    consumedGroundTick = tick;
                    return new Sample(true, rise,
                            rise > jump && rise > step, jump, step);
                }
                if (rise < -0.04 || horizontal > 1.5) resetArm();
            }
        }

        boolean stableGround = !serverDelayed && ground != null && ground.known()
                && ground.onGround() && !ground.entitySupport() && ground.consecutiveTicks() >= 2
                && environment != null && now >= environment.capturedAt()
                && now - environment.capturedAt() <= 200
                && Double.isFinite(legalJumpVelocity) && legalJumpVelocity >= 0
                && Double.isFinite(maxStepHeight) && maxStepHeight >= 0
                && Math.abs(environment.x() - packetX) <= 0.6
                && Math.abs(environment.z() - packetZ) <= 0.6;
        if (!stableGround || ground.tick() == consumedGroundTick) return Sample.skipped();

        double jumpLimit = legalJumpVelocity + JUMP_MARGIN;
        double stepLimit = maxStepHeight + STEP_MARGIN;
        double riseFromSnapshot = packetY - environment.y();
        if (riseFromSnapshot > 0.035) {
            consumedGroundTick = ground.tick();
            return new Sample(true, riseFromSnapshot,
                    riseFromSnapshot > jumpLimit && riseFromSnapshot > stepLimit,
                    jumpLimit, stepLimit);
        }

        if (Math.abs(riseFromSnapshot) <= 0.03) {
            armed = true;
            armedGroundTick = ground.tick();
            x = packetX;
            y = packetY;
            z = packetZ;
            allowedJump = jumpLimit;
            allowedStep = stepLimit;
            armedAt = now;
        }
        return Sample.skipped();
    }

    public void reset() {
        resetArm();
        consumedGroundTick = -1;
    }

    private void expire(long now) {
        if (armed && (now < armedAt || now - armedAt > MAX_ARM_MILLIS)) resetArm();
    }

    private void resetArm() {
        armed = false;
        armedGroundTick = -1;
        armedAt = 0;
    }

    private static boolean finite(double... values) {
        for (double value : values) if (!Double.isFinite(value)) return false;
        return true;
    }
}

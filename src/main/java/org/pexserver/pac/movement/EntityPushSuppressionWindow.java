package org.pexserver.pac.movement;

import org.pexserver.pac.packet.JavaInputCapture;

/**
 * High-confidence detector for client-side entity-push suppression.
 *
 * <p>The server already samples the exact vanilla horizontal push vector from
 * nearby pushable entities. We compare the same movement packet against two
 * otherwise-identical client simulations: one with that server-confirmed push
 * and one without it. Evidence is accepted only when the player has neutral
 * movement input and collision geometry proves that a wall did not absorb the
 * push.</p>
 */
public final class EntityPushSuppressionWindow {
    private static final double MINIMUM_PUSH = 0.025;
    private static final double WITHOUT_PUSH_MATCH = 0.012;
    private static final double WITH_PUSH_MISS = 0.020;
    private static final double MINIMUM_ADVANTAGE = 0.016;
    private static final double COLLISION_EPSILON = 0.005;
    private static final int REQUIRED_SAMPLES = 4;

    public record Sample(boolean eligible, boolean suspicious, boolean flagged,
                         double pushMagnitude, double withPushOffset,
                         double withoutPushOffset, int streak) {
        static Sample skipped() {
            return new Sample(false, false, false, 0, 0, 0, 0);
        }
    }

    private int streak;

    public Sample accept(MotionPredictor.Motion previous,
                         double actualX, double actualZ,
                         double startX, double startY, double startZ,
                         MotionEnvironment.Snapshot environment,
                         MotionCollisionSnapshot collisions,
                         JavaInputCapture.Window inputs) {
        if (previous == null || environment == null || !environment.ordinaryGround()
                || collisions == null || !collisions.complete()
                || !collisions.blockGeometryComplete()
                || collisions.hardEntityCollisionPossible()
                || collisions.entityPushCount() <= 0
                || inputs == null || inputs.current() == null) {
            reset();
            return Sample.skipped();
        }

        MotionPredictor.Input input = inputs.current();
        if (input.forwardAxis() != 0 || input.strafeAxis() != 0 || input.jump()) {
            reset();
            return Sample.skipped();
        }

        double pushX = collisions.entityPushX();
        double pushZ = collisions.entityPushZ();
        double pushMagnitude = Math.hypot(pushX, pushZ);
        if (!Double.isFinite(pushMagnitude) || pushMagnitude < MINIMUM_PUSH) {
            reset();
            return Sample.skipped();
        }

        float sneakScale = environment.sneaking() ? environment.sneakingSpeed() : 1.0f;
        MotionPredictor.Motion actual = new MotionPredictor.Motion(actualX, 0, actualZ);
        MotionPredictor.Result withoutPush = MotionPredictor.predictGroundInputClient(
                previous, actual, environment.yaw(), environment.movementSpeed(),
                environment.groundFriction(), environment.horizontalDrag(),
                sneakScale, environment.itemUseMultiplier(), input);
        MotionPredictor.Motion pushedPrevious = new MotionPredictor.Motion(
                previous.dx() + pushX, previous.dy(), previous.dz() + pushZ);
        MotionPredictor.Result withPush = MotionPredictor.predictGroundInputClient(
                pushedPrevious, actual, environment.yaw(), environment.movementSpeed(),
                environment.groundFriction(), environment.horizontalDrag(),
                sneakScale, environment.itemUseMultiplier(), input);

        if (!unclipped(withoutPush.closest(), startX, startY, startZ, collisions)
                || !unclipped(withPush.closest(), startX, startY, startZ, collisions)) {
            reset();
            return Sample.skipped();
        }

        double withoutOffset = withoutPush.offset();
        double withOffset = withPush.offset();
        boolean suspicious = withoutOffset <= WITHOUT_PUSH_MATCH
                && withOffset >= WITH_PUSH_MISS
                && withOffset - withoutOffset >= MINIMUM_ADVANTAGE;
        streak = suspicious ? Math.min(REQUIRED_SAMPLES, streak + 1)
                : Math.max(0, streak - 1);
        return new Sample(true, suspicious, streak >= REQUIRED_SAMPLES,
                pushMagnitude, withOffset, withoutOffset, streak);
    }

    public void reset() {
        streak = 0;
    }

    private static boolean unclipped(MotionPredictor.Motion wanted,
                                     double startX, double startY, double startZ,
                                     MotionCollisionSnapshot collisions) {
        if (wanted == null) return false;
        for (MotionCollisionSnapshot.Move move : collisions.resolve(
                startX, startY, startZ, wanted.dx(), 0, wanted.dz(), true)) {
            if (Math.abs(move.x() - wanted.dx()) <= COLLISION_EPSILON
                    && Math.abs(move.z() - wanted.dz()) <= COLLISION_EPSILON)
                return true;
        }
        return false;
    }
}

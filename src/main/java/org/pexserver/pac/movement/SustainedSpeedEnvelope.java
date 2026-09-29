package org.pexserver.pac.movement;

/**
 * A conservative speed ceiling across short ground/air transitions. This is
 * deliberately separate from the per-mode simulator so low hops cannot keep
 * resetting the movement model before sustained acceleration is measured.
 */
public final class SustainedSpeedEnvelope {
    private ServerTickTiming.Snapshot timing = ServerTickTiming.Snapshot.NORMAL;
    public void timing(ServerTickTiming.Snapshot timing) { this.timing = timing; }

    private static final long MAX_SAMPLE_GAP_NANOS = 500_000_000L;
    private static final long UNKNOWN_CONTEXT_HOLD_NANOS = 200_000_000L;
    private static final long EXTERNAL_MOTION_HOLD_NANOS = 150_000_000L;
    private static final double ROUNDING_AND_COLLISION_MARGIN = 0.08;
    private static final double LEVEL_GROUND_MARGIN = 0.02;

    public record Sample(boolean evaluated, boolean flagged, double speed,
                         double legalSpeed, GroundMotionSequence.Position rollbackAnchor) {
        static Sample skipped() { return new Sample(false, false, 0, 0, null); }
    }

    private final SustainedSpeedEvidence evidence = new SustainedSpeedEvidence();
    private double x, y, z;
    private long lastAtNanos;
    private long suspendedUntilNanos;
    private boolean initialized;
    private double sprintJumpAllowance;
    private double externalMomentumBound;
    private double previousHorizontalSpeed;
    private boolean previousGround;
    private boolean serverVelocityActive;
    private GroundMotionSequence.Position suspiciousAnchor;

    /** Seed the ceiling from a velocity chosen by the server, before the client responds. */
    public void serverVelocity(double horizontalSpeed, double x, double y, double z,
                               long nowNanos) {
        if (!Double.isFinite(horizontalSpeed) || horizontalSpeed < 0
                || !finite(x, y, z)) return;
        evidence.reset();
        suspiciousAnchor = null;
        sprintJumpAllowance = 0;
        externalMomentumBound = horizontalSpeed;
        // A server-selected replacement velocity is also the previous legal
        // horizontal motion for the next client physics step. Starting carry
        // from zero makes an AirDash tail collapse toward the ordinary speed
        // cap too early, especially when it lands on ice.
        previousHorizontalSpeed = horizontalSpeed;
        serverVelocityActive = true;
        suspendedUntilNanos = nowNanos + EXTERNAL_MOTION_HOLD_NANOS;
        baseline(x, y, z, nowNanos);
    }

    /**
     * The grant lifetime is owned by PacketChecks. Ending it stops the special
     * uncertain-snapshot hold, but deliberately keeps the already modeled
     * momentum so ordinary drag/input can carry it forward.
     */
    public void endServerVelocity() {
        serverVelocityActive = false;
    }

    public Sample accept(double nextX, double nextY, double nextZ,
                         MotionEnvironment.Snapshot environment,
                         MotionCollisionSnapshot collisions,
                         long nowNanos, boolean externalMotion) {
        if (!finite(nextX, nextY, nextZ)) {
            reset();
            return Sample.skipped();
        }
        long nowMillis = System.currentTimeMillis();
        boolean fresh = environment != null && nowMillis >= environment.capturedAt()
                && nowMillis - environment.capturedAt() <= 200;
        // verticalAir is the conservative mode used above ordinary terrain
        // where the 3x3 horizontal volume is not fully clear. Excluding it
        // reset the cross-mode envelope on every low hop, which let Wurst
        // Speed/Fly repeatedly discard motion-prediction evidence.
        boolean trusted = fresh && (environment.ordinaryGround()
                || environment.ordinaryAir() || environment.verticalAir());
        if (externalMotion) {
            evidence.reset();
            suspiciousAnchor = null;
            sprintJumpAllowance = 0;
            suspendedUntilNanos = nowNanos + EXTERNAL_MOTION_HOLD_NANOS;
            // The launch packet may arrive while the sampled environment is
            // between ground and air, so seed before the ordinary-mode gate.
            if (initialized && nowNanos >= lastAtNanos
                    && nowNanos - lastAtNanos <= MAX_SAMPLE_GAP_NANOS) {
                int frames = timing.physicsFrames(
                        Math.max(1, (nowNanos - lastAtNanos) / 1_000_000L), 0);
                externalMomentumBound = Math.hypot(nextX - x, nextZ - z) / frames;
            } else externalMomentumBound = 0;
            baseline(nextX, nextY, nextZ, nowNanos);
            return Sample.skipped();
        }
        if (nowNanos < suspendedUntilNanos) {
            if (initialized && nowNanos >= lastAtNanos) {
                int frames = timing.physicsFrames(
                        Math.max(1, (nowNanos - lastAtNanos) / 1_000_000L), 0);
                externalMomentumBound = Math.max(externalMomentumBound,
                        Math.hypot(nextX - x, nextZ - z) / frames);
            }
            baseline(nextX, nextY, nextZ, nowNanos);
            return Sample.skipped();
        }
        if (!trusted) {
            // A plugin/server velocity can move the client farther than the
            // main-thread snapshot's normal near() window before that snapshot
            // catches up. Do not erase the authoritative launch merely because
            // this one environment sample cannot yet classify ground vs air.
            // We also do not learn extra momentum from the untrusted frame.
            boolean authorizedVelocityGap = serverVelocityActive && initialized
                    && environment != null && fresh
                    && nowNanos >= lastAtNanos
                    && nowNanos - lastAtNanos <= MAX_SAMPLE_GAP_NANOS;
            if (authorizedVelocityGap) {
                evidence.reset();
                suspiciousAnchor = null;
                baseline(nextX, nextY, nextZ, nowNanos);
                return Sample.skipped();
            }

            // Ground state can briefly be unknown while a low hop changes the
            // server's ground/air sample. Keep the last trusted anchor across
            // that short gap and judge the aggregate displacement on the next
            // trusted frame. Long gaps, stale samples, and remote positions
            // still discard the evidence so transitions cannot carry over.
            boolean transientUnknown = initialized && environment != null
                    && nowNanos >= lastAtNanos
                    && nowNanos - lastAtNanos <= UNKNOWN_CONTEXT_HOLD_NANOS
                    && fresh
                    && (environment.near(x, y, z) || environment.near(nextX, nextY, nextZ));
            if (!transientUnknown) reset();
            return Sample.skipped();
        }
        if (!initialized || nowNanos < lastAtNanos
                || nowNanos - lastAtNanos > MAX_SAMPLE_GAP_NANOS) {
            evidence.reset();
            suspiciousAnchor = null;
            sprintJumpAllowance = 0;
            externalMomentumBound = 0;
            previousHorizontalSpeed = 0;
            previousGround = environment.ordinaryGround();
            baseline(nextX, nextY, nextZ, nowNanos);
            return Sample.skipped();
        }

        GroundMotionSequence.Position previous = new GroundMotionSequence.Position(x, y, z);
        long elapsedMillis = Math.max(1, (nowNanos - lastAtNanos) / 1_000_000L);
        int frames = timing.physicsFrames(elapsedMillis, 0);
        double dx = nextX - x, dy = nextY - y, dz = nextZ - z;
        if (collisions != null) {
            dx -= collisions.entityPushX();
            dz -= collisions.entityPushZ();
        }
        double drag = Math.max(0, Math.min(0.995, environment.horizontalDrag()));
        double speedCap = maximumSustainableSpeed(environment);
        externalMomentumBound = speedCap + Math.max(0, externalMomentumBound - speedCap)
                * Math.pow(drag, frames);
        double carryLegalSpeed = environment.ordinaryGround() && previousGround && Math.abs(dy) <= 0.03
            ? MotionPredictor.maximumGroundStepClient(
                new MotionPredictor.Motion(0, 0, previousHorizontalSpeed),
                environment.movementSpeed(), environment.groundFriction(),
                environment.horizontalDrag(),
                environment.sneaking() ? environment.sneakingSpeed() : 1.0f,
                environment.itemUseMultiplier())
            : MotionPredictor.maximumAirStep(
                new MotionPredictor.Motion(0, 0, previousHorizontalSpeed),
                environment.sprinting(), environment.horizontalDrag());
        sprintJumpAllowance *= Math.pow(Math.min(1, environment.horizontalDrag()), frames);
        VerticalTransition transition = legalVerticalTransition(collisions, x, y, z,
                dx, dy, dz, environment.jumpStrength(), environment.sprinting(),
                environment.ordinaryGround(), nowMillis);
        // A main-thread snapshot can already be airborne on the takeoff packet.
        // Normal jump height is still recognizable from the preceding trusted
        // ground sample when collision geometry is temporarily unavailable.
        if (transition == VerticalTransition.NONE && previousGround && frames == 1
                && matchesVanillaTakeoff(dy, environment))
            transition = VerticalTransition.JUMP;
        if (transition == VerticalTransition.JUMP && environment.sprinting()) {
            // Takeoff uses ground input acceleration even if the environment
            // snapshot has advanced to air. Landing keeps the previous air drag.
            carryLegalSpeed = Math.max(carryLegalSpeed,
                    MotionPredictor.maximumGroundStepClient(
                            new MotionPredictor.Motion(0, 0, previousHorizontalSpeed),
                            environment.movementSpeed(), environment.groundFriction(),
                            environment.horizontalDrag(),
                            environment.sneaking() ? environment.sneakingSpeed() : 1.0f,
                            environment.itemUseMultiplier()));
            // Each grounded sprint jump adds a horizontal impulse. On ice the
            // previous impulse is retained, and a low ceiling can clip Y to
            // nearly zero while repeated jumps keep accelerating the player.
            sprintJumpAllowance = Math.min(2.0, sprintJumpAllowance + 0.2);
        } else if (transition == VerticalTransition.STEP) {
            sprintJumpAllowance = Math.max(sprintJumpAllowance,
                    environment.sprinting() ? 0.2 : 0.1);
        }
        double entityPushAllowance = collisions == null ? 0 : collisions.entityPushHorizontalAllowance();
        double speed = MotionCollisionSnapshot.horizontalResidual(
                dx / frames, dz / frames, entityPushAllowance);
        double legalSpeed = Math.max(Math.max(speedCap, externalMomentumBound), carryLegalSpeed)
            + sprintJumpAllowance;
        // Nearby entities can add a real server-side push. Keep collecting speed
        // evidence, but widen the accepted envelope until a block-only replay is safe.
        // Carry uses observed velocity. At a constant 0.4 blocks/tick it
        // already allows about 0.348 on normal ground; adding the transition
        // margin again hides the entire repeated acceleration. Level ground
        // needs only rounding tolerance. Confirmed jumps/steps keep their margin.
        double margin = environment.ordinaryGround() && Math.abs(dy) <= 0.03
                && transition == VerticalTransition.NONE
                ? LEVEL_GROUND_MARGIN : ROUNDING_AND_COLLISION_MARGIN;
        double excess = Math.max(0, speed - legalSpeed - margin);
        if (excess > 0 && suspiciousAnchor == null) suspiciousAnchor = previous;
        if (excess == 0) suspiciousAnchor = null;
        boolean flagged = evidence.accept(excess);
        // A tolerance is for measuring this frame, not acceleration the client
        // may bank as legitimate momentum for the next frame.
        previousHorizontalSpeed = Math.min(speed, legalSpeed);
        previousGround = environment.ordinaryGround();
        GroundMotionSequence.Position rollback = suspiciousAnchor == null ? previous : suspiciousAnchor;
        baseline(nextX, nextY, nextZ, nowNanos);
        return new Sample(true, flagged, speed, legalSpeed, flagged ? rollback : null);
    }

    /**
     * Depending on packet/snapshot ordering, the first airborne coordinate can
     * expose either the raw jump impulse or the first gravity+drag result. Both
     * are ordinary Vanilla takeoff signatures and carry the +0.2 sprint-jump
     * horizontal impulse.
     */
    private static boolean matchesVanillaTakeoff(double dy, MotionEnvironment.Snapshot environment) {
        if (dy <= 0.03) return false;
        double jump = environment.jumpStrength();
        if (Math.abs(dy - jump) <= 0.02) return true;
        double afterGravity = AirPredictor.nextDisplacement(jump,
                environment.gravity(), environment.verticalDrag(),
                environment.slowFalling(), environment.levitationAmplifier());
        return Math.abs(dy - afterGravity) <= 0.02;
    }

    private static double maximumSustainableSpeed(MotionEnvironment.Snapshot environment) {
        MotionPredictor.Motion zero = new MotionPredictor.Motion(0, 0, 0);
        float groundFriction = environment.ordinaryGround() ? environment.groundFriction() : 0.6f;
        double groundAcceleration = MotionPredictor.maximumGroundStepClient(zero,
                environment.movementSpeed(), groundFriction, environment.horizontalDrag(),
                environment.sneaking() ? environment.sneakingSpeed() : 1.0f,
                environment.itemUseMultiplier());
        double groundRetention = Math.max(0, Math.min(0.995,
                groundFriction * environment.horizontalDrag()));
        double groundCap = groundAcceleration / (1 - groundRetention);

        double airAcceleration = MotionPredictor.maximumAirStep(zero,
                environment.sprinting(), environment.horizontalDrag());
        double airRetention = Math.max(0, Math.min(0.995, environment.horizontalDrag()));
        double airCap = airAcceleration / (1 - airRetention);
        return Math.max(groundCap, airCap) * environment.stuckHorizontalMultiplier();
    }

    /** Realign position/time after a rejected move without discarding active evidence. */
    public void rebase(double x, double y, double z, long nowNanos) {
        if (finite(x, y, z)) baseline(x, y, z, nowNanos);
    }

    /** Arrival jitter invalidates evidence, but does not remove legitimate momentum. */
    public void suspend(double x, double y, double z, long nowNanos) {
        if (!finite(x, y, z)) {
            reset();
            return;
        }
        evidence.reset();
        suspiciousAnchor = null;
        baseline(x, y, z, nowNanos);
    }

    public void reset() {
        initialized = false;
        lastAtNanos = 0;
        evidence.reset();
        suspiciousAnchor = null;
        suspendedUntilNanos = 0;
        sprintJumpAllowance = 0;
        externalMomentumBound = 0;
        previousHorizontalSpeed = 0;
        previousGround = false;
        serverVelocityActive = false;
    }

    private enum VerticalTransition { NONE, STEP, JUMP }

    private static VerticalTransition legalVerticalTransition(MotionCollisionSnapshot collisions,
                                                   double x, double y, double z,
                                                   double dx, double dy, double dz,
                                                   float jumpStrength, boolean sprinting,
                                                   boolean onGround, long nowMillis) {
        if (collisions == null || !collisions.complete()
                || nowMillis < collisions.capturedAt()
                || nowMillis - collisions.capturedAt() > 200) return VerticalTransition.NONE;
        MotionCollisionSnapshot.MoveBuffer moves = MotionCollisionSnapshot.predictionBuffer();
        boolean clippedHeadJump = sprinting && onGround && dy >= -0.015
                && dy < jumpStrength - 0.04;
        if ((dy > 0.03 || clippedHeadJump)
                && matchesVerticalTransition(collisions, moves, x, y, z,
                dx, dy, dz, jumpStrength)) return VerticalTransition.JUMP;
        if (dy > 0.03 && matchesVerticalTransition(collisions, moves, x, y, z,
                dx, dy, dz, 0)) return VerticalTransition.STEP;
        return VerticalTransition.NONE;
    }

    private static boolean matchesVerticalTransition(MotionCollisionSnapshot collisions,
                                                     MotionCollisionSnapshot.MoveBuffer moves,
                                                     double x, double y, double z,
                                                     double dx, double dy, double dz,
                                                     double desiredY) {
        int count = collisions.resolveInto(x, y, z, dx, desiredY, dz, true, moves);
        for (int i = 0; i < count; i++) {
            if (Math.abs(moves.x(i) - dx) <= 0.03
                    && Math.abs(moves.y(i) - dy) <= 0.015
                    && Math.abs(moves.z(i) - dz) <= 0.03) return true;
        }
        return false;
    }

    private void baseline(double x, double y, double z, long nowNanos) {
        this.x = x;
        this.y = y;
        this.z = z;
        lastAtNanos = nowNanos;
        initialized = true;
    }

    private static boolean finite(double x, double y, double z) {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
    }
}

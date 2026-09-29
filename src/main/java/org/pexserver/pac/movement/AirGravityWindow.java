package org.pexserver.pac.movement;

import java.util.Locale;

/** Fits a continuous free-fall trajectory, including omitted position packets. */
public final class AirGravityWindow {
    /**
     * Packet quantization and ground/air transition timing can make two fitted
     * initial-velocity intervals miss by only a few thousandths. Do not turn
     * that numerical edge into a hard Flight finding; real sustained Flight
     * produces a much wider contradiction.
     */
    private static final double MIN_VELOCITY_CONTRADICTION = 0.01;

    private long lastAt;
    private double origin, gravity, drag, velocityFactor, gravityVelocity, factorSum, gravitySum;
    private double minimumVelocity, maximumVelocity;
    private int frames;
    private int intervals;
    private int lastSteps;
    private long lastIntervalMillis;
    private double lastY;
    private int positionlessFrames;

    public void reset() {
        lastAt = 0;
        frames = 0;
        intervals = 0;
        lastSteps = 0;
        lastIntervalMillis = 0;
        positionlessFrames = 0;
    }

    /** TCP arrival spacing is not a client physics clock. Count actual packets. */
    public boolean packet(boolean eligible, boolean hasPosition, double y,
                          double gravity, float drag, long now) {
        if (!eligible) {
            reset();
            return false;
        }
        if (!hasPosition) {
            positionlessFrames = Math.min(40, positionlessFrames + 1);
            return false;
        }
        int steps = positionlessFrames + 1;
        positionlessFrames = 0;
        return sample(true, y, gravity, drag, now, steps);
    }

    /** Compact evidence for a gravity-fit mismatch, useful in server logs. */
    public String diagnostic() {
        return String.format(Locale.ROOT,
                "frames=%d steps=%d interval=%dms y=%.4f->%.4f initialVy=[%.4f,%.4f] g=%.4f drag=%.4f",
                frames, lastSteps, lastIntervalMillis, origin, lastY,
                minimumVelocity, maximumVelocity, gravity, drag);
    }

    public boolean sample(boolean eligible, double y, double gravity, float drag, long now) {
        return sample(eligible, y, gravity, drag, now, 1);
    }

    /**
     * Add one observed endpoint after {@code physicsFrames} ordered client
     * steps. Positionless look packets are not stationary samples; their
     * elapsed physics is folded into the next coordinate observation.
     */
    public boolean sample(boolean eligible, double y, double gravity, float drag,
                          long now, int physicsFrames) {
        if (!eligible || !Double.isFinite(y)) {
            lastAt = 0;
            return false;
        }
        int steps = Math.max(1, Math.min(40, physicsFrames));
        long elapsed = now - lastAt;
        if (lastAt == 0 || elapsed < 0
            || this.gravity != gravity || this.drag != drag || intervals >= 8) {
            origin = y;
            this.gravity = gravity;
            this.drag = drag;
            velocityFactor = 1;
            gravityVelocity = factorSum = gravitySum = 0;
            minimumVelocity = Double.NEGATIVE_INFINITY;
            maximumVelocity = Double.POSITIVE_INFINITY;
            frames = 0;
            intervals = 0;
            lastSteps = 0;
            lastIntervalMillis = 0;
            lastY = y;
            lastAt = now;
            return false;
        }
        // Multiple coordinate packets can be processed in one server tick,
        // while delayed coordinate packets can span many physics frames.
        // The caller supplies the ordered movement-frame count, so wall time
        // must not discard an otherwise evaluable endpoint interval.
        lastAt = now;
        frames += steps;
        intervals++;
        lastSteps = steps;
        lastIntervalMillis = elapsed;
        lastY = y;
        for (int i = 0; i < steps; i++) {
            velocityFactor *= drag;
            gravityVelocity = (gravityVelocity - gravity) * drag;
            factorSum += velocityFactor;
            gravitySum += gravityVelocity;
        }
        // Each omitted position can differ by up to 0.03 blocks, including
        // the initial anchor. Fit every possible initial vertical velocity.
        double displacement = y - origin - gravitySum;
        minimumVelocity = Math.max(minimumVelocity, (displacement - 0.062) / factorSum);
        maximumVelocity = Math.min(maximumVelocity, (displacement + 0.062) / factorSum);
        // Three consecutive physics intervals overdetermine the one unknown
        // initial velocity, but tiny disjoint intervals still occur at legal
        // sprint-jump/landing boundaries because packet Y is quantized and the
        // main-thread support snapshot can move one frame earlier/later.
        // Require a meaningful contradiction before treating the fit as Flight.
        return frames >= 3
                && minimumVelocity - maximumVelocity > MIN_VELOCITY_CONTRADICTION;
    }

    public static boolean clearVerticalSweep(MotionCollisionSnapshot collisions,
                                             double x, double y, double z, long now) {
        if (collisions == null || !collisions.complete() || !collisions.blockGeometryComplete()
                || collisions.hardEntityCollisionPossible() || now < collisions.capturedAt()
                || now - collisions.capturedAt() > 200) return false;
        var moves = MotionCollisionSnapshot.predictionBuffer();
        for (double dy : new double[]{-0.6, 0.6}) {
            int count = collisions.resolveInto(x, y, z, 0, dy, 0, false, moves);
            if (count == 0) return false;
            for (int i = 0; i < count; i++)
                if (Math.abs(moves.y(i) - dy) > 1.0e-7) return false;
        }
        return true;
    }

    /**
     * A gravity-only fit is valid only if the whole observed 3D segment has
     * room to continue vertically. Checking the endpoint alone misses a step
     * or block contact crossed during a fast horizontal dash.
     */
    public static boolean clearVerticalCorridor(MotionCollisionSnapshot collisions,
                                                double startX, double startY, double startZ,
                                                double endX, double endY, double endZ, long now) {
        if (!Double.isFinite(startX) || !Double.isFinite(startY) || !Double.isFinite(startZ)
                || !Double.isFinite(endX) || !Double.isFinite(endY) || !Double.isFinite(endZ))
            return false;
        double distance = Math.max(Math.max(Math.abs(endX - startX), Math.abs(endY - startY)),
                Math.abs(endZ - startZ));
        int samples = Math.max(1, (int) Math.ceil(distance / 0.2));
        if (samples > 40) return false;
        for (int i = 0; i <= samples; i++) {
            double t = (double) i / samples;
            if (!clearVerticalSweep(collisions,
                    startX + (endX - startX) * t,
                    startY + (endY - startY) * t,
                    startZ + (endZ - startZ) * t, now)) return false;
        }
        return true;
    }
}

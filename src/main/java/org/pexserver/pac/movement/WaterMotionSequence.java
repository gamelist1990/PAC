package org.pexserver.pac.movement;

import org.pexserver.pac.packet.ExternalMotionTracker;
import org.pexserver.pac.packet.JavaInputCapture;

/** Packet-order state for submerged Java movement in a stable water volume. */
public final class WaterMotionSequence {
    private ServerTickTiming.Snapshot timing = ServerTickTiming.Snapshot.NORMAL;
    public void timing(ServerTickTiming.Snapshot timing) { this.timing = timing; }

    public record Position(double x, double y, double z) { }
    public record Sample(boolean evaluated, double offset, int skippedFrames,
                         boolean suppressedCurrent) {
        public Sample(boolean evaluated, double offset, int skippedFrames) {
            this(evaluated, offset, skippedFrames, false);
        }
    }

    private double x, y, z;
    private MotionPredictor.Motion previous = new MotionPredictor.Motion(0, 0, 0);
    private WaterMotionEnvironment.Snapshot previousEnvironment;
    private final ExternalMotionWindow externalMotion = new ExternalMotionWindow();
    private long lastAt;
    private long lastTick = -1;
    private int skippedFrames;
    private boolean initialized, reliable;

    public Position lastPosition() { return initialized ? new Position(x, y, z) : null; }
    public MotionPredictor.Motion motion() { return previous; }

    public Sample accept(boolean hasPosition, double packetX, double packetY, double packetZ,
                         float yaw, WaterMotionEnvironment.Snapshot environment,
                         MotionCollisionSnapshot collisions, JavaInputCapture.Window inputs,
                         ExternalMotionTracker.Impulse impulse, long now) {
        if (!hasPosition) {
            skippedFrames = Math.min(40, skippedFrames + 1);
            return new Sample(false, 0, skippedFrames);
        }
        if (!Double.isFinite(packetX) || !Double.isFinite(packetY) || !Double.isFinite(packetZ)) {
            reset();
            return new Sample(false, 0, 0);
        }
        if (environment == null || now - environment.capturedAt() > 200
                || collisions == null || !collisions.complete()
                || collisions.hardEntityCollisionPossible()
                || now - collisions.capturedAt() > 200) {
            reset();
            return new Sample(false, 0, skippedFrames);
        }

        boolean externalTransition = externalMotion.rebase(impulse);
        if (!initialized || skippedFrames > 0) {
            rebase(packetX, packetY, packetZ, environment, now);
            return new Sample(false, 0, skippedFrames);
        }

        double dx = packetX - x, dy = packetY - y, dz = packetZ - z;
        long elapsed = now - lastAt;
        long elapsedTicks = environment.tick() - lastTick;
        boolean stable = elapsed >= 0 && (timing.delayed() ? elapsed <= 200 : elapsed >= 25 && elapsed <= 150)
                && elapsedTicks >= 0 && elapsedTicks <= 2
                && previousEnvironment.samePhysics(environment)
                && (environment.near(x, y, z) || environment.near(packetX, packetY, packetZ));
        if (!stable) {
            rebase(packetX, packetY, packetZ, environment, now);
            return new Sample(false, 0, 0);
        }

        MotionPredictor.Motion actual = new MotionPredictor.Motion(dx, dy, dz);
        if (externalTransition) {
            MotionPredictor.Motion seeded = externalMotion.seed(previous, impulse);
            WaterMotionPredictor.Result response = WaterMotionPredictor.predict(seeded, actual,
                    Float.isFinite(yaw) ? yaw : environment.yaw(), environment, inputs,
                    x, y, z, collisions);
            previous = response != null && response.offset() <= 0.08
                    ? response.expected() : actual;
            reliable = true;
            update(packetX, packetY, packetZ, environment, now);
            return new Sample(false, 0, 0);
        }
        if (!reliable) {
            previous = actual;
            reliable = true;
            update(packetX, packetY, packetZ, environment, now);
            return new Sample(false, 0, 0);
        }

        WaterMotionPredictor.Result prediction = WaterMotionPredictor.predict(previous, actual,
                Float.isFinite(yaw) ? yaw : environment.yaw(), environment, inputs,
                x, y, z, collisions);
        boolean suppressedCurrent = false;
        double currentStrength = Math.sqrt(environment.currentX() * environment.currentX()
                + environment.currentY() * environment.currentY()
                + environment.currentZ() * environment.currentZ());
        if (prediction != null && currentStrength >= 0.008
                && prediction.offset() >= 0.008 && prediction.offset() <= 0.04) {
            var noCurrent = WaterMotionPredictor.predict(previous, actual,
                    Float.isFinite(yaw) ? yaw : environment.yaw(),
                    environment.withoutCurrent(), inputs, x, y, z, collisions);
            suppressedCurrent = noCurrent != null && noCurrent.offset() <= 0.012
                    && prediction.offset() - noCurrent.offset() >= 0.006;
        }
        previous = actual;
        update(packetX, packetY, packetZ, environment, now);
        return prediction == null ? new Sample(false, 0, 0)
                : new Sample(true, prediction.offset(), 0, suppressedCurrent);
    }

    private void rebase(double nextX, double nextY, double nextZ,
                        WaterMotionEnvironment.Snapshot environment, long now) {
        initialized = true;
        reliable = false;
        skippedFrames = 0;
        previousEnvironment = environment;
        update(nextX, nextY, nextZ, environment, now);
    }

    /** Rebase a rejected sample while retaining the last trusted water velocity. */
    public void rebaseForCorrection(double nextX, double nextY, double nextZ,
                                    MotionPredictor.Motion velocity,
                                    WaterMotionEnvironment.Snapshot environment, long now) {
        if (environment == null || !Double.isFinite(nextX) || !Double.isFinite(nextY)
                || !Double.isFinite(nextZ)) {
            reset();
            return;
        }
        initialized = true;
        reliable = true;
        skippedFrames = 0;
        previous = velocity == null ? new MotionPredictor.Motion(0, 0, 0) : velocity;
        previousEnvironment = environment;
        update(nextX, nextY, nextZ, environment, now);
    }

    private void update(double nextX, double nextY, double nextZ,
                        WaterMotionEnvironment.Snapshot environment, long now) {
        x = nextX; y = nextY; z = nextZ;
        lastAt = now;
        lastTick = environment.tick();
        previousEnvironment = environment;
    }

    private void reset() {
        initialized = false;
        reliable = false;
        skippedFrames = 0;
        previousEnvironment = null;
        lastAt = 0;
        lastTick = -1;
    }
}

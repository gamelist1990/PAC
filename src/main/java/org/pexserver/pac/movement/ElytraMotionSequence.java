package org.pexserver.pac.movement;

import org.pexserver.pac.movement.MotionEnvironment.ElytraSnapshot;
import org.pexserver.pac.packet.ExternalMotionTracker;

import java.util.ArrayList;
import java.util.List;

/** Packet-order replay for vanilla Elytra flight, including block collision outcomes. */
public final class ElytraMotionSequence {
    private ServerTickTiming.Snapshot timing = ServerTickTiming.Snapshot.NORMAL;
    public void timing(ServerTickTiming.Snapshot timing) { this.timing = timing; }

    public record Sample(boolean evaluated, double horizontalOffset, double verticalOffset) {
        static Sample skipped() { return new Sample(false, 0, 0); }
        public double offset() { return Math.hypot(horizontalOffset, verticalOffset); }
    }
    public record Position(double x, double y, double z) { }
    private record Path(double x, double y, double z, MotionPredictor.Motion velocity) { }

    private double x, y, z;
    private double velocityX, velocityY, velocityZ;
    private long lastAt;
    private int positionlessFrames;
    private float yaw, pitch;
    private boolean initialized, velocityInitialized, rotationKnown;

    public Position lastPosition() { return initialized ? new Position(x, y, z) : null; }

    public Sample accept(boolean hasPosition, boolean hasRotation,
                        double packetX, double packetY, double packetZ,
                        float packetYaw, float packetPitch,
                        ElytraSnapshot environment, MotionCollisionSnapshot collisions,
                        ExternalMotionTracker.Impulse impulse, long now) {
        float previousYaw = yaw;
        float previousPitch = pitch;
        if (hasRotation && Float.isFinite(packetYaw) && Float.isFinite(packetPitch)) {
            yaw = packetYaw;
            pitch = packetPitch;
            rotationKnown = true;
        }
        if (!hasPosition) {
            positionlessFrames = Math.min(40, positionlessFrames + 1);
            return Sample.skipped();
        }
        if (!finite(packetX, packetY, packetZ)) {
            reset();
            return Sample.skipped();
        }
        if (environment == null || !environment.gliding()
                || now < environment.capturedAt() || now - environment.capturedAt() > 200
                || !environment.near(packetX, packetY, packetZ, 2.0)
                || impulse != null) {
            reset();
            return Sample.skipped();
        }
        if (collisions == null || !collisions.blockGeometryComplete()
                || now < collisions.capturedAt()
                || now - collisions.capturedAt() > 200) {
            velocityInitialized = false;
            baseline(packetX, packetY, packetZ, now);
            return Sample.skipped();
        }

        int skipped = positionlessFrames;
        positionlessFrames = 0;
        if (!initialized) {
            baseline(packetX, packetY, packetZ, now);
            return Sample.skipped();
        }
        long elapsedMillis = Math.max(0, now - lastAt);
        if ((!timing.delayed() && elapsedMillis == 0) || elapsedMillis > 200) {
            velocityInitialized = false;
            baseline(packetX, packetY, packetZ, now);
            return Sample.skipped();
        }
        int frames = timing.physicsFrames(elapsedMillis, skipped);
        if (frames > 3 || !rotationKnown) {
            velocityInitialized = false;
            baseline(packetX, packetY, packetZ, now);
            return Sample.skipped();
        }

        double actualX = packetX - x;
        double actualY = packetY - y;
        double actualZ = packetZ - z;
        if (!velocityInitialized) {
            setObservedVelocity(actualX, actualY, actualZ, frames);
            baseline(packetX, packetY, packetZ, now);
            velocityInitialized = true;
            return Sample.skipped();
        }

        double bestHorizontal = Double.POSITIVE_INFINITY;
        double bestVertical = Double.POSITIVE_INFINITY;
        double bestTotal = Double.POSITIVE_INFINITY;
        MotionCollisionSnapshot.MoveBuffer moves = MotionCollisionSnapshot.predictionBuffer();
        float[] yawCandidates = unique(yaw, previousYaw);
        float[] pitchCandidates = unique(pitch, previousPitch);
        for (float candidateYaw : yawCandidates) {
            for (float candidatePitch : pitchCandidates) {
                List<Path> paths = new ArrayList<>();
                paths.add(new Path(x, y, z,
                        new MotionPredictor.Motion(velocityX, velocityY, velocityZ)));
                boolean completeReplay = true;
                for (int frame = 0; frame < frames && completeReplay; frame++) {
                    List<Path> next = new ArrayList<>();
                    for (Path path : paths) {
                        for (MotionPredictor.Motion wanted : frameCandidates(
                                path.velocity(), candidateYaw, candidatePitch, environment)) {
                            int count = collisions.resolveInto(path.x(), path.y(), path.z(),
                                    wanted.dx(), wanted.dy(), wanted.dz(), false, moves);
                            if (count == 0) {
                                completeReplay = false;
                                break;
                            }
                            for (int index = 0; index < count; index++) {
                                double movedX = moves.x(index), movedY = moves.y(index), movedZ = moves.z(index);
                                MotionPredictor.Motion resolved = new MotionPredictor.Motion(
                                        Math.abs(movedX - wanted.dx()) > 1.0E-7 ? 0 : wanted.dx(),
                                        Math.abs(movedY - wanted.dy()) > 1.0E-7 ? 0 : wanted.dy(),
                                        Math.abs(movedZ - wanted.dz()) > 1.0E-7 ? 0 : wanted.dz());
                                next.add(new Path(path.x() + movedX, path.y() + movedY,
                                        path.z() + movedZ, resolved));
                                if (next.size() >= 64) break;
                            }
                            if (!completeReplay || next.size() >= 64) break;
                        }
                        if (!completeReplay || next.size() >= 64) break;
                    }
                    paths = next;
                }
                if (!completeReplay) continue;
                double entityPushAllowance = collisions.entityPushHorizontalAllowance();
                for (Path path : paths) {
                    double dx = packetX - path.x();
                    double dy = packetY - path.y();
                    double dz = packetZ - path.z();
                    double horizontal = MotionCollisionSnapshot.horizontalResidual(dx, dz,
                            entityPushAllowance);
                    double total = Math.hypot(horizontal, dy);
                    if (total < bestTotal) {
                        bestTotal = total;
                        bestHorizontal = horizontal;
                        bestVertical = Math.abs(dy);
                    }
                }
            }
        }
        setObservedVelocity(actualX, actualY, actualZ, frames);
        baseline(packetX, packetY, packetZ, now);
        if (!Double.isFinite(bestTotal)) return Sample.skipped();
        return new Sample(true, bestHorizontal, bestVertical);
    }

    private static MotionPredictor.Motion[] frameCandidates(
            MotionPredictor.Motion velocity, float yaw, float pitch,
            ElytraSnapshot environment) {
        MotionPredictor.Motion ordinary = ElytraMotionPredictor.next(
                velocity, yaw, pitch, environment.gravity(), environment.slowFalling());
        if (!environment.fireworkBoost()) return new MotionPredictor.Motion[] {ordinary};

        // Firework and living-entity ticks can straddle the packet sample
        // boundary. Accept both vanilla orderings, plus a boundary frame with
        // no boost. A client-side multiplier still falls outside every vanilla
        // branch on repeated boost frames.
        MotionPredictor.Motion boostAfter =
                ElytraMotionPredictor.fireworkBoost(ordinary, yaw, pitch);
        MotionPredictor.Motion boostBefore = ElytraMotionPredictor.next(
                ElytraMotionPredictor.fireworkBoost(velocity, yaw, pitch),
                yaw, pitch, environment.gravity(), environment.slowFalling());
        return new MotionPredictor.Motion[] {ordinary, boostAfter, boostBefore};
    }

    public void reset() {
        initialized = false;
        velocityInitialized = false;
        rotationKnown = false;
        lastAt = 0;
        positionlessFrames = 0;
    }

    private void baseline(double nextX, double nextY, double nextZ, long now) {
        x = nextX;
        y = nextY;
        z = nextZ;
        lastAt = now;
        initialized = true;
    }

    private void setObservedVelocity(double dx, double dy, double dz, int frames) {
        velocityX = dx / frames;
        velocityY = dy / frames;
        velocityZ = dz / frames;
    }

    private static float[] unique(float first, float second) {
        return Math.abs(first - second) < 1.0E-4f ? new float[] {first} : new float[] {first, second};
    }

    private static boolean finite(double... values) {
        for (double value : values) if (!Double.isFinite(value)) return false;
        return true;
    }
}

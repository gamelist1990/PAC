package org.pexserver.pac.check.shared;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

/**
 * Shared Java/Bedrock combat evidence. Client rotations are analyzed only while
 * real attack packets and confirmed hits show that the player is tracking a target.
 */
final class CombatPatternMonitor {
    private static final long COMBAT_WINDOW_MILLIS = 3_500L;
    private static final long RAPID_SWITCH_MILLIS = 100L;
    private static final int ROTATION_WINDOW = 15;
    private static final int ATTACK_WINDOW = 20;
    private static final double SMOOTH_VARIANCE = 0.0001;

    record Finding(String source, String detail, Map<String, Double> metrics, int weight) { }

    private final Deque<Double> rotationDeltas = new ArrayDeque<>();
    private final Deque<Long> attackTimes = new ArrayDeque<>();
    private final Deque<Long> hitTimes = new ArrayDeque<>();
    private float lastYaw;
    private float lastPitch;
    private boolean hasRotation;
    private long lastAttackAt;
    private int lastPacketTarget = Integer.MIN_VALUE;
    private long lastPacketTargetAt;
    private int rapidSwitchStreak;
    private double smoothViolations;

    synchronized Finding sampleRotation(float yaw, float pitch, long now) {
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) return null;
        if (!hasRotation) {
            lastYaw = yaw;
            lastPitch = pitch;
            hasRotation = true;
            return null;
        }

        double yawDelta = Math.abs(wrapDegrees(yaw - lastYaw));
        double pitchDelta = Math.abs(pitch - lastPitch);
        double totalDelta = yawDelta + pitchDelta;
        lastYaw = yaw;
        lastPitch = pitch;

        prune(now);
        boolean trackingRecentHit = lastAttackAt > 0L && now - lastAttackAt <= COMBAT_WINDOW_MILLIS
                && hitTimes.size() >= 1;
        if (!trackingRecentHit || totalDelta <= 0.01) {
            smoothViolations = Math.max(0.0, smoothViolations - 0.5);
            if (!trackingRecentHit) rotationDeltas.clear();
            return null;
        }

        rotationDeltas.addLast(totalDelta);
        while (rotationDeltas.size() > ROTATION_WINDOW) rotationDeltas.removeFirst();
        if (rotationDeltas.size() < ROTATION_WINDOW) return null;

        double variance = variance(rotationDeltas);
        if (variance < SMOOTH_VARIANCE) smoothViolations++;
        else smoothViolations = Math.max(0.0, smoothViolations - 0.2);

        if (smoothViolations < 25.0 || hitTimes.size() < 5) return null;
        int recentAttacks = attackTimes.size();
        Finding finding = new Finding("aimbot-smoothing",
                String.format(java.util.Locale.ROOT,
                        "stable rotation deltas while repeatedly landing hits: variance=%.7f samples=%d hits=%d attacks=%d",
                        variance, rotationDeltas.size(), hitTimes.size(), recentAttacks),
                Map.of("rotation_delta_variance", variance,
                        "rotation_samples", (double) rotationDeltas.size(),
                        "confirmed_hits", (double) hitTimes.size(),
                        "recent_attack_packets", (double) recentAttacks,
                        "smoothing_score", smoothViolations), 2);
        smoothViolations = 0.0;
        return finding;
    }

    synchronized Finding attackPacket(int targetEntityId, long now) {
        prune(now);
        attackTimes.addLast(now);
        while (attackTimes.size() > ATTACK_WINDOW) attackTimes.removeFirst();
        lastAttackAt = now;

        long switchInterval = lastPacketTargetAt == 0L ? Long.MAX_VALUE : now - lastPacketTargetAt;
        if (targetEntityId != lastPacketTarget) {
            if (lastPacketTarget != Integer.MIN_VALUE && switchInterval >= 0L
                    && switchInterval < RAPID_SWITCH_MILLIS) {
                rapidSwitchStreak++;
            } else {
                rapidSwitchStreak = 0;
            }
            lastPacketTarget = targetEntityId;
            lastPacketTargetAt = now;
        } else {
            rapidSwitchStreak = Math.max(0, rapidSwitchStreak - 1);
            lastPacketTargetAt = now;
        }

        if (rapidSwitchStreak < 3 || hitTimes.size() < 2) return null;
        Finding finding = new Finding("rapid-target-switch",
                String.format(java.util.Locale.ROOT,
                        "rapid target switching during confirmed combat: streak=%d interval=%dms hits=%d attacks=%d",
                        rapidSwitchStreak, switchInterval, hitTimes.size(), attackTimes.size()),
                Map.of("rapid_switch_streak", (double) rapidSwitchStreak,
                        "switch_interval_millis", (double) switchInterval,
                        "confirmed_hits", (double) hitTimes.size(),
                        "recent_attack_packets", (double) attackTimes.size()), 2);
        rapidSwitchStreak = 0;
        return finding;
    }

    synchronized void confirmedHit(int targetEntityId, long now) {
        prune(now);
        hitTimes.addLast(now);
        while (hitTimes.size() > ATTACK_WINDOW) hitTimes.removeFirst();
        lastAttackAt = now;
        // Bukkit's actual hit is authoritative if a bridge's attack packet did
        // not reach PacketEvents in time. Keep target-switch state packet-based.
        if (lastPacketTarget == Integer.MIN_VALUE) lastPacketTarget = targetEntityId;
    }

    synchronized void reset() {
        rotationDeltas.clear();
        attackTimes.clear();
        hitTimes.clear();
        hasRotation = false;
        lastAttackAt = 0L;
        lastPacketTarget = Integer.MIN_VALUE;
        lastPacketTargetAt = 0L;
        rapidSwitchStreak = 0;
        smoothViolations = 0.0;
    }

    private void prune(long now) {
        while (!attackTimes.isEmpty() && now - attackTimes.peekFirst() > COMBAT_WINDOW_MILLIS)
            attackTimes.removeFirst();
        while (!hitTimes.isEmpty() && now - hitTimes.peekFirst() > COMBAT_WINDOW_MILLIS)
            hitTimes.removeFirst();
        if (lastAttackAt > 0L && now - lastAttackAt > COMBAT_WINDOW_MILLIS) {
            lastAttackAt = 0L;
            rapidSwitchStreak = 0;
            lastPacketTarget = Integer.MIN_VALUE;
            lastPacketTargetAt = 0L;
        }
    }

    private static double variance(Deque<Double> values) {
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        return values.stream().mapToDouble(value -> (value - mean) * (value - mean)).average().orElse(0.0);
    }

    private static float wrapDegrees(float degrees) {
        degrees %= 360.0f;
        if (degrees >= 180.0f) degrees -= 360.0f;
        if (degrees < -180.0f) degrees += 360.0f;
        return degrees;
    }
}

package org.pexserver.pac.check.shared;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;

/** Bounded, time-window mining statistics used by the experimental Xray check. */
final class XrayMiningProfile {
    private static final int MAX_WINDOW_SAMPLES = 8_192;
    private static final long SUSPICION_DECAY_INTERVAL_MILLIS = 30_000L;
    private static final long ANCIENT_DEBRIS_BURST_MILLIS = 45_000L;

    record Settings(long windowMillis, int minimumBlocks,
                    double rareHighRatio, double rareMediumRatio,
                    double commonHighRatio, double commonMediumRatio,
                    double suspicionThreshold, double rareJumpDistance) { }

    record Finding(String reason, double suspicion, int windowBlocks, int oreBlocks,
                   int rareOreBlocks, double trackedOreRatio, double rareRatio,
                   double threshold, int weight) {
        Map<String, Double> metrics() {
            return Map.of("xray_suspicion", suspicion,
                    "xray_window_blocks", (double) windowBlocks,
                    "xray_ore_blocks", (double) oreBlocks,
                    "xray_tracked_ore_ratio", trackedOreRatio,
                    "xray_rare_ore_blocks", (double) rareOreBlocks,
                    "xray_rare_ore_ratio", rareRatio,
                    "xray_suspicion_threshold", threshold,
                    "xray_violation_weight", (double) weight);
        }
    }

    private record Sample(long at, boolean ore, boolean rare) { }

    private final Deque<Sample> samples = new ArrayDeque<>();
    private UUID worldId;
    private int windowOreBlocks;
    private int windowRareOreBlocks;
    private long lastDecayAt;
    private long lastRatioAwardAt;
    private long lastAncientDebrisAt;
    private int lastOreX;
    private int lastOreY;
    private int lastOreZ;
    private long lastOreAt;
    private int veinJumpChain;
    private double suspicion;
    private boolean reported;
    private String latestEvidence = "mining statistics";

    /**
     * Adds one successfully broken block. Rarity and visibility are supplied by
     * the caller from the server's block state, never from client claims.
     */
    Finding observe(long now, UUID world, int x, int y, int z,
                    boolean trackedOre, boolean rareOre, int oreWeight,
                    boolean ancientDebris, boolean hidden,
                    Settings settings) {
        if (worldId != null && !worldId.equals(world)) resetWindow();
        worldId = world;
        decay(now, settings.suspicionThreshold());
        prune(now, settings.windowMillis());

        samples.addLast(new Sample(now, trackedOre, rareOre));
        if (trackedOre) windowOreBlocks++;
        if (rareOre) windowRareOreBlocks++;
        while (samples.size() > MAX_WINDOW_SAMPLES) removeOldest();

        if (trackedOre) {
            if (rareOre && hidden) addEvidence(oreWeight + 2.0,
                    "enclosed rare ore mined (weight=" + oreWeight + ")");

            if (lastOreAt > 0L && now >= lastOreAt && now - lastOreAt <= settings.windowMillis()) {
                double distance = distance(x, y, z, lastOreX, lastOreY, lastOreZ);
                double jumpLimit = oreWeight >= 5 ? settings.rareJumpDistance()
                        : settings.rareJumpDistance() * 1.5;
                if (distance > jumpLimit) {
                    veinJumpChain++;
                    if (veinJumpChain >= 3) {
                        addEvidence(oreWeight + 3.0,
                                String.format(java.util.Locale.ROOT,
                                        "repeated distant ore finds (chain=%d distance=%.2f limit=%.2f)",
                                        veinJumpChain, distance, jumpLimit));
                        veinJumpChain = 0;
                    }
                } else {
                    veinJumpChain = 0;
                }
            } else {
                veinJumpChain = 0;
            }
            lastOreX = x;
            lastOreY = y;
            lastOreZ = z;
            lastOreAt = now;

            if (ancientDebris && lastAncientDebrisAt > 0L && now >= lastAncientDebrisAt
                    && now - lastAncientDebrisAt < ANCIENT_DEBRIS_BURST_MILLIS) {
                addEvidence(oreWeight + 3.0, "ancient debris burst within 45 seconds");
            }
            if (ancientDebris) lastAncientDebrisAt = now;

            if (samples.size() >= settings.minimumBlocks()
                    && (lastRatioAwardAt == 0L || now - lastRatioAwardAt >= settings.windowMillis())) {
                double ratio = (double) windowOreBlocks / samples.size();
                double high = oreWeight >= 5 ? settings.rareHighRatio() : settings.commonHighRatio();
                double medium = oreWeight >= 5 ? settings.rareMediumRatio() : settings.commonMediumRatio();
                if (ratio > high) {
                    addEvidence(oreWeight + 2.0,
                            String.format(java.util.Locale.ROOT,
                                    "high tracked-ore ratio (%.4f > %.4f)", ratio, high));
                    lastRatioAwardAt = now;
                } else if (ratio > medium) {
                    addEvidence(oreWeight,
                            String.format(java.util.Locale.ROOT,
                                    "elevated tracked-ore ratio (%.4f > %.4f)", ratio, medium));
                    lastRatioAwardAt = now;
                }
            }
        }

        if (reported || suspicion < settings.suspicionThreshold()) return null;
        reported = true;
        double trackedOreRatio = samples.isEmpty() ? 0.0 : (double) windowOreBlocks / samples.size();
        double rareRatio = samples.isEmpty() ? 0.0 : (double) windowRareOreBlocks / samples.size();
        int scoreWeight = Math.max(1, Math.min(10, (int) Math.ceil(suspicion / 4.0)));
        return new Finding(latestEvidence, suspicion, samples.size(), windowOreBlocks,
                windowRareOreBlocks, trackedOreRatio, rareRatio,
                settings.suspicionThreshold(), scoreWeight);
    }

    private void decay(long now, double threshold) {
        if (lastDecayAt == 0L || now < lastDecayAt) {
            lastDecayAt = now;
            return;
        }
        long intervals = (now - lastDecayAt) / SUSPICION_DECAY_INTERVAL_MILLIS;
        if (intervals > 0) {
            suspicion = Math.max(0.0, suspicion - intervals * 3.0);
            lastDecayAt += intervals * SUSPICION_DECAY_INTERVAL_MILLIS;
            if (suspicion < threshold * 0.5) reported = false;
        }
    }

    private void addEvidence(double amount, String reason) {
        suspicion = Math.min(100.0, suspicion + Math.max(0.0, amount));
        latestEvidence = reason;
    }

    private void prune(long now, long windowMillis) {
        while (!samples.isEmpty() && (now < samples.peekFirst().at()
                || now - samples.peekFirst().at() > windowMillis)) removeOldest();
    }

    private void removeOldest() {
        Sample removed = samples.removeFirst();
        if (removed.ore()) windowOreBlocks--;
        if (removed.rare()) windowRareOreBlocks--;
    }

    private void resetWindow() {
        samples.clear();
        windowOreBlocks = 0;
        windowRareOreBlocks = 0;
        suspicion = 0.0;
        reported = false;
        latestEvidence = "mining statistics";
        lastDecayAt = 0L;
        lastRatioAwardAt = 0L;
        lastAncientDebrisAt = 0L;
        lastOreAt = 0L;
        veinJumpChain = 0;
    }

    private static double distance(int x, int y, int z, int otherX, int otherY, int otherZ) {
        long dx = (long) x - otherX;
        long dy = (long) y - otherY;
        long dz = (long) z - otherZ;
        return Math.sqrt((double) dx * dx + (double) dy * dy + (double) dz * dz);
    }
}

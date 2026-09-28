package org.pexserver.pac.check.java.action;

import java.util.LinkedHashMap;

/** Bounded sliding one-second view of block-digging packets for one player. */
final class NukerPacketWindow {
    private static final long WINDOW_NANOS = 1_000_000_000L;
    private static final int MAX_PACKET_SAMPLES = 8_192;
    private static final int MAX_TRACKED_TARGETS = 8_192;

    record Snapshot(int packets, int uniqueTargets) { }
    private record Target(int x, int y, int z) { }

    private long[] packetTimes = new long[128];
    private int packetHead;
    private int packetCount;
    private long lastNow = Long.MIN_VALUE;
    /** Access order keeps each target's latest start packet at the tail. */
    private final LinkedHashMap<Target, Long> targetLastSeen =
            new LinkedHashMap<>(128, 0.75f, true);

    synchronized Snapshot record(long nowNanos, Integer x, Integer y, Integer z) {
        if (nowNanos < lastNow) {
            packetHead = 0;
            packetCount = 0;
            targetLastSeen.clear();
        }
        lastNow = nowNanos;
        while (packetCount > 0 && nowNanos - packetTimes[packetHead] >= WINDOW_NANOS) {
            packetHead = (packetHead + 1) % packetTimes.length;
            packetCount--;
        }
        while (!targetLastSeen.isEmpty()
                && nowNanos - targetLastSeen.firstEntry().getValue() >= WINDOW_NANOS)
            targetLastSeen.pollFirstEntry();

        if (packetCount == packetTimes.length && packetTimes.length < MAX_PACKET_SAMPLES)
            growPacketTimes();
        if (packetCount == MAX_PACKET_SAMPLES) {
            packetHead = (packetHead + 1) % packetTimes.length;
            packetCount--;
        }
        packetTimes[(packetHead + packetCount) % packetTimes.length] = nowNanos;
        packetCount++;

        if (x != null && y != null && z != null) {
            targetLastSeen.put(new Target(x, y, z), nowNanos);
            if (targetLastSeen.size() > MAX_TRACKED_TARGETS)
                targetLastSeen.pollFirstEntry();
        }
        return new Snapshot(packetCount, targetLastSeen.size());
    }

    private void growPacketTimes() {
        long[] grown = new long[Math.min(MAX_PACKET_SAMPLES, packetTimes.length * 2)];
        for (int i = 0; i < packetCount; i++)
            grown[i] = packetTimes[(packetHead + i) % packetTimes.length];
        packetTimes = grown;
        packetHead = 0;
    }

    static boolean suspicious(Snapshot snapshot, int maxPacketsPerSecond, int maxTargetsPerSecond) {
        return snapshot.packets() >= maxPacketsPerSecond
                || (snapshot.uniqueTargets() >= maxTargetsPerSecond
                && snapshot.packets() >= maxTargetsPerSecond * 2);
    }
}

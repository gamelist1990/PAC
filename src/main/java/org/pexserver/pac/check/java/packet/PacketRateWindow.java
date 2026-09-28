package org.pexserver.pac.check.java.packet;

/** Allocation-free timestamps for a bounded, exact one-second packet window. */
final class PacketRateWindow {
    private static final long WINDOW_NANOS = 1_000_000_000L;
    private static final int MAX_SAMPLES = 8_192;
    private long[] times = new long[128];
    private int head;
    private int size;
    private long lastNow = Long.MIN_VALUE;

    int record(long now) {
        if (now < lastNow) {
            head = 0;
            size = 0;
        }
        lastNow = now;
        while (size > 0 && now - times[head] >= WINDOW_NANOS) {
            head = (head + 1) % times.length;
            size--;
        }
        if (size == times.length && times.length < MAX_SAMPLES) grow();
        if (size == MAX_SAMPLES) {
            head = (head + 1) % times.length;
            size--;
        }
        times[(head + size) % times.length] = now;
        return ++size;
    }

    private void grow() {
        long[] grown = new long[Math.min(MAX_SAMPLES, times.length * 2)];
        for (int i = 0; i < size; i++) grown[i] = times[(head + i) % times.length];
        times = grown;
        head = 0;
    }
}

package org.pexserver.pac.check.java.packet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PacketRateWindowTest {
    @Test void countsBurstAcrossFormerFixedWindowBoundary() {
        PacketRateWindow window = new PacketRateWindow();
        long start = 1_000_000_000L;
        window.record(start);
        for (int i = 0; i < 120; i++) window.record(start + 990_000_000L + i);
        int count = 0;
        for (int i = 0; i < 121; i++) count = window.record(start + 1_010_000_000L + i);
        assertEquals(241, count);
    }

    @Test void expiresOldPacketsAndCapsFloodMemory() {
        PacketRateWindow window = new PacketRateWindow();
        for (int i = 0; i < 10_000; i++) window.record(1_000_000_000L + i);
        assertEquals(8_192, window.record(1_000_010_000L));
        assertEquals(1, window.record(2_000_010_000L));
    }
}

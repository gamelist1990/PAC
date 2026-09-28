package org.pexserver.pac.check.java.action;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NukerPacketWindowTest {
    @Test void repeatedDigPacketsCrossConfiguredRateLimit() {
        NukerPacketWindow window = new NukerPacketWindow();
        NukerPacketWindow.Snapshot snapshot = null;
        for (int i = 0; i < 79; i++) snapshot = window.record(1_000_000_000L + i, null, null, null);
        assertFalse(NukerPacketWindow.suspicious(snapshot, 80, 24));
        snapshot = window.record(1_000_000_079L, null, null, null);
        assertTrue(NukerPacketWindow.suspicious(snapshot, 80, 24));
    }

    @Test void broadTargetSweepIsDetectedBelowRawPacketLimit() {
        NukerPacketWindow window = new NukerPacketWindow();
        NukerPacketWindow.Snapshot snapshot = null;
        long now = 1_000_000_000L;
        for (int target = 0; target < 24; target++) {
            snapshot = window.record(now++, target, 64, 0);
            snapshot = window.record(now++, null, null, null);
        }
        assertEquals(48, snapshot.packets());
        assertEquals(24, snapshot.uniqueTargets());
        assertTrue(NukerPacketWindow.suspicious(snapshot, 80, 24));
    }

    @Test void packetAndTargetCountsResetAfterOneSecond() {
        NukerPacketWindow window = new NukerPacketWindow();
        for (int i = 0; i < 80; i++) window.record(2_000_000_000L + i, i, 64, 0);
        var next = window.record(3_000_000_079L, 4, 64, 0);
        assertEquals(1, next.packets());
        assertEquals(1, next.uniqueTargets());
        assertFalse(NukerPacketWindow.suspicious(next, 80, 24));
    }

    @Test void burstAcrossOldFixedWindowBoundaryIsStillDetected() {
        NukerPacketWindow window = new NukerPacketWindow();
        long start = 1_000_000_000L;
        window.record(start, null, null, null);
        for (int i = 0; i < 40; i++)
            window.record(start + 990_000_000L + i, null, null, null);
        NukerPacketWindow.Snapshot snapshot = null;
        for (int i = 0; i < 40; i++)
            snapshot = window.record(start + 1_010_000_000L + i, null, null, null);
        assertEquals(80, snapshot.packets());
        assertTrue(NukerPacketWindow.suspicious(snapshot, 80, 24));
    }

    @Test void targetUniquenessExpiresPerPacket() {
        NukerPacketWindow window = new NukerPacketWindow();
        long start = 1_000_000_000L;
        window.record(start, 1, 64, 0);
        window.record(start + 500_000_000L, 2, 64, 0);
        var snapshot = window.record(start + 1_000_000_000L, 2, 64, 0);
        assertEquals(2, snapshot.packets());
        assertEquals(1, snapshot.uniqueTargets());
    }

    @Test void repeatedStartsDoNotEvictDistinctTargetsWithinOneSecond() {
        NukerPacketWindow window = new NukerPacketWindow();
        NukerPacketWindow.Snapshot snapshot = null;
        long start = 1_000_000_000L;
        for (int target = 0; target < 512; target++) {
            snapshot = window.record(start++, target, 64, 0);
            snapshot = window.record(start++, target, 64, 0);
            snapshot = window.record(start++, target, 64, 0);
        }
        assertEquals(1_536, snapshot.packets());
        assertEquals(512, snapshot.uniqueTargets());
        assertTrue(NukerPacketWindow.suspicious(snapshot, 5_000, 512));
    }
}

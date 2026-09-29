package org.pexserver.pac.packet;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FishingPullPhysicsTest {
    @Test void fishingPullMatchesVanillaOwnerMinusHookScale() {
        var pull = PacketChecks.fishingPull(10, 66, -4, 7, 64, 1);

        assertEquals(0.3, pull.dx(), 1.0e-12);
        assertEquals(0.2, pull.dy(), 1.0e-12);
        assertEquals(-0.5, pull.dz(), 1.0e-12);
    }

    @Test void fishingPullRemainsAdditiveInExternalMotionTracker() {
        var tracker = new ExternalMotionTracker();
        UUID player = UUID.randomUUID();
        long now = 1_000;

        tracker.velocity(player, 0.2, 0.1, 0, now);
        var pull = PacketChecks.fishingPull(5, 65, 0, 3, 64, 0);
        tracker.addImpulse(player, pull.dx(), pull.dy(), pull.dz(), now + 1);

        var combined = tracker.since(player, 0, now + 2);
        assertTrue(combined != null);
        assertEquals(0.4, combined.x(), 1.0e-12);
        assertEquals(0.2, combined.y(), 1.0e-12);
        assertEquals(0.0, combined.z(), 1.0e-12);
    }
}

package org.pexserver.pac.packet;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerMotionGrantTest {
    @Test void airDashAllowanceLastsUntilItsHorizontalMomentumHasDecayed() {
        long duration = PacketChecks.serverMotionDuration(1.4, 0.3);
        assertTrue(duration >= 1_000);
        assertTrue(duration <= 3_000);
    }

    @Test void damageTaggedVelocityDoesNotBecomeOrdinaryPluginMotion() {
        var tracker = new ExternalMotionTracker();
        UUID player = UUID.randomUUID();
        tracker.markCombatDamage(player, 1_000);
        assertTrue(tracker.recentCombatDamage(player, 1_100));
        assertFalse(tracker.recentCombatDamage(player, 1_501));
    }
}

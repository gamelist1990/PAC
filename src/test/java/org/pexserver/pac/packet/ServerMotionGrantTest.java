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

    @Test void smallPluginVelocityRetainsItsPhysicalTail() {
        assertTrue(PacketChecks.serverMotionDuration(0.30, 0) >= 1_000,
                "0.30 horizontal velocity is still significant after 300ms");
        assertTrue(PacketChecks.serverMotionDuration(0.10, 0) >= 800,
                "small setVelocity must not become ordinary client acceleration immediately");
    }

    @Test void downwardAndZeroVelocityAreAuthoritativeChanges() {
        assertTrue(PacketChecks.serverMotionDuration(0, -0.8) >= 700);
        assertTrue(PacketChecks.serverMotionDuration(0, 0) >= 300,
                "setVelocity(0,0,0) still replaces the predicted motion");
    }

    @Test void damageTaggedVelocityDoesNotBecomeOrdinaryPluginMotion() {
        var tracker = new ExternalMotionTracker();
        UUID player = UUID.randomUUID();
        tracker.markCombatDamage(player, 1_000);
        assertTrue(tracker.recentCombatDamage(player, 1_100));
        assertFalse(tracker.recentCombatDamage(player, 1_501));
    }
}

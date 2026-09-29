package org.pexserver.pac.check.shared;

import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class CombatTargetHistoryTest {
    @Test void reportedPingNeverGrantsReachRewind() {
        assertEquals(0, CombatTargetHistory.trustedRewindMillis(0));
        assertEquals(0, CombatTargetHistory.trustedRewindMillis(100));
        assertEquals(0, CombatTargetHistory.trustedRewindMillis(500));
        assertEquals(0, CombatTargetHistory.trustedRewindMillis(25_000));
    }

    @Test void selectsHistoricalBoxAtOrBeforeAttackViewTime() {
        var history = new CombatTargetHistory();
        UUID player = UUID.randomUUID();
        UUID world = UUID.randomUUID();
        history.sample(player, world, new BoundingBox(0, 0, 0, 1, 2, 1), 1_000);
        history.sample(player, world, new BoundingBox(1, 0, 0, 2, 2, 1), 1_050);
        history.sample(player, world, new BoundingBox(2, 0, 0, 3, 2, 1), 1_100);

        var frame = history.atOrBefore(player, world, 1_075);
        assertNotNull(frame);
        assertEquals(1.0, frame.box().getMinX(), 1.0e-12);
    }

    @Test void spoofedHugePingStillUsesAttackReceiveTime() {
        var history = new CombatTargetHistory();
        UUID player = UUID.randomUUID();
        UUID world = UUID.randomUUID();
        for (int tick = 0; tick <= 10; tick++) {
            double x = tick;
            history.sample(player, world, new BoundingBox(x, 0, 0, x + 0.6, 1.8, 0.6),
                    1_000 + tick * 50L);
        }

        long attackAt = 1_500;
        long rewind = CombatTargetHistory.trustedRewindMillis(25_000);
        var frame = history.atOrBefore(player, world, attackAt - rewind);

        assertNotNull(frame);
        assertEquals(10.0, frame.box().getMinX(), 1.0e-12,
                "25 second fake ping must not move the reach geometry into older history");
    }
    @Test void strictGeometryRequiresSettledFreshHistory() {
        var history = new CombatTargetHistory();
        UUID target = UUID.randomUUID(), world = UUID.randomUUID();
        var box = new BoundingBox(0, 0, 0, 0.9, 1.4, 0.9);
        assertNull(history.stableBox(target, world, box, 1_000));
        for (long at = 1_000; at <= 1_300; at += 50) history.sample(target, world, box, at);
        assertNotNull(history.stableBox(target, world, box, 1_300));
        assertNull(history.stableBox(target, UUID.randomUUID(), box, 1_300));
        assertNull(history.stableBox(target, world, box, 1_450));
        var moved = box.clone().shift(0.5, 0, 0);
        history.sample(target, world, moved, 1_350);
        assertNull(history.stableBox(target, world, moved, 1_350),
                "a moving cow may still be displayed at its previous position");
        for (long at = 1_400; at <= 1_750; at += 50) history.sample(target, world, moved, at);
        assertNotNull(history.stableBox(target, world, moved, 1_750));
        history.prune(3_000);
        assertNull(history.stableBox(target, world, moved, 3_000));
    }

    @Test void overlappingNearbyPlayersDoNotEvictTheSettlingWindow() {
        var history = new CombatTargetHistory();
        UUID target = UUID.randomUUID(), world = UUID.randomUUID();
        var box = new BoundingBox(0, 0, 0, 1, 2, 1);
        for (long at = 1_000; at <= 1_300; at += 50)
            for (int observer = 0; observer < 50; observer++) history.sample(target, world, box, at);
        assertNotNull(history.stableBox(target, world, box, 1_300));
    }
}

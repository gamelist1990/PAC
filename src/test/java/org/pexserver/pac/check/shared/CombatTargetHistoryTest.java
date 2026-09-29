package org.pexserver.pac.check.shared;

import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class CombatTargetHistoryTest {
    @Test void reportedPingCannotRewindBeyondBoundedWindow() {
        assertEquals(0, CombatTargetHistory.trustedRewindMillis(0));
        assertEquals(50, CombatTargetHistory.trustedRewindMillis(100));
        assertEquals(250, CombatTargetHistory.trustedRewindMillis(500));
        assertEquals(250, CombatTargetHistory.trustedRewindMillis(25_000));
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

    @Test void spoofedHugePingStillOnlySelectsBoundedHistory() {
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
        assertEquals(5.0, frame.box().getMinX(), 1.0e-12,
                "25 second fake ping must not rewind farther than five ticks");
    }
}

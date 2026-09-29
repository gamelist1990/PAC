package org.pexserver.pac;

import org.junit.jupiter.api.Test;
import org.pexserver.pac.movement.MovementPacketTimer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MovementPacketTimerTest {
    @Test void ordinaryTwentyPacketsPerSecondRemainWithinBudget() {
        var timer = new MovementPacketTimer();
        for (int packet = 0; packet < 1000; packet++) {
            assertFalse(timer.accept(1_000_000_000L + packet * 50_000_000L));
        }
    }

    @Test void sustainedFortyPacketsPerSecondExceedsBudget() {
        var timer = new MovementPacketTimer();
        boolean flagged = false;
        for (int packet = 0; packet < 200; packet++) {
            flagged |= timer.accept(1_000_000_000L + packet * 25_000_000L);
        }
        assertTrue(flagged);
    }

    @Test void confirmedSpeedBurstRejectsMovementUntilTheClockCatchesUp() {
        var timer = new MovementPacketTimer();
        long now = 1_000_000_000L;
        boolean confirmed = false;
        int consecutiveRejected = 0;
        for (int packet = 0; packet < 200; packet++) {
            now += 25_000_000L;
            boolean rejected = timer.accept(now);
            if (rejected) {
                confirmed = true;
                consecutiveRejected++;
            } else if (confirmed) break;
        }

        assertTrue(confirmed);
        assertTrue(consecutiveRejected > 1,
                "after confirmation, movement packets should remain rejected while excess time is repaid");
        assertFalse(timer.accept(now + 50_000_000L),
                "normal movement should resume after the accumulated lead has been repaid");
    }

    @Test void shortLagBurstDoesNotFlag() {
        var timer = new MovementPacketTimer();
        timer.accept(1_000_000_000L);
        for (int packet = 0; packet < 30; packet++) {
            assertFalse(timer.accept(3_000_000_000L + packet * 1_000_000L));
        }
    }
    @Test void liquidBounceDamageNcpBurstIsCutOffBeforeAllPacketsApply() {
        var timer = new MovementPacketTimer();
        long now = 5_000_000_000L;
        int accepted = 0;
        int rejected = 0;
        int firstRejectedAt = -1;

        // Damage(NCP), damage=1 emits 65 pairs of +0.049 / baseline
        // position packets back-to-back before the final on-ground packet.
        for (int packet = 0; packet < 130; packet++) {
            boolean blocked = timer.accept(now);
            if (blocked) {
                rejected++;
                if (firstRejectedAt < 0) firstRejectedAt = packet;
            } else {
                accepted++;
            }
        }

        assertTrue(firstRejectedAt >= 0 && firstRejectedAt <= 36,
                "the 130-packet damage burst must enter rejection near the timer confirmation boundary");
        assertTrue(rejected > accepted,
                "most packets in the self-damage burst must be rejected after confirmation");
    }

    @Test void liquidBounceTimeShiftFullPacketBurstIsRejected() {
        var timer = new MovementPacketTimer();
        long now = 9_000_000_000L;
        int rejected = 0;
        int firstRejectedAt = -1;

        // Current TimeShift can emit up to 100 FULL movement packets from one
        // client tick. Equal coordinates still represent client simulation
        // steps because the packet includes a position.
        for (int packet = 0; packet < 100; packet++) {
            boolean blocked = timer.accept(now);
            if (blocked) {
                rejected++;
                if (firstRejectedAt < 0) firstRejectedAt = packet;
            }
        }

        assertTrue(firstRejectedAt >= 0 && firstRejectedAt <= 36);
        assertTrue(rejected > 50,
                "most injected TimeShift simulation steps must be rejected");
    }

}

package org.pexserver.pac.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VanillaPositionBurstTest {
    @Test void rejectsWurstLegitStepSplitInsideOnePhysicsTick() {
        var burst = new VanillaPositionBurst();
        long start = 1_000_000_000L;
        assertFalse(burst.accept(true, 64, start, false).impossible());
        assertFalse(burst.accept(true, 64.42, start + 1_000_000L, false).impossible());
        assertFalse(burst.accept(true, 64.753, start + 2_000_000L, false).impossible());
        assertTrue(burst.accept(true, 65, start + 3_000_000L, false).impossible());
    }

    @Test void acceptsTheSameCoordinatesWhenNetworkRecoveryExplainsBatching() {
        var burst = new VanillaPositionBurst();
        long start = 1_000_000_000L;
        burst.accept(true, 64, start, false);
        burst.accept(true, 64.42, start + 1_000_000L, true);
        burst.accept(true, 64.753, start + 2_000_000L, true);
        assertFalse(burst.accept(true, 65, start + 3_000_000L, true).impossible());
    }

    @Test void ordinaryOnePacketJumpAndUnrelatedBurstAreAccepted() {
        var burst = new VanillaPositionBurst();
        long start = 1_000_000_000L;
        burst.accept(true, 64, start, false);
        assertFalse(burst.accept(true, 64.42, start + 50_000_000L, false).impossible());
        assertFalse(burst.accept(true, 64.7532, start + 100_000_000L, false).impossible());
        assertFalse(burst.accept(true, 65.0013, start + 150_000_000L, false).impossible());

        burst.reset();
        burst.accept(true, 64, start, false);
        burst.accept(true, 64.2, start + 1_000_000L, false);
        burst.accept(true, 64.55, start + 2_000_000L, false);
        assertFalse(burst.accept(true, 65, start + 3_000_000L, false).impossible());
    }
}

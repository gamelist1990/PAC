package org.pexserver.pac.check.java.packet;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CriticalPacketCheckTest {
    @Test void recognizesWurstPacketCriticalOffsetSequence() {
        List<CriticalPacketCheck.Step> steps = List.of(
                new CriticalPacketCheck.Step(0, 64, 0, 0, 0.0625, 0, true, 1_000),
                new CriticalPacketCheck.Step(0, 64.0625, 0, 0, -0.0625, 0, false, 1_001),
                new CriticalPacketCheck.Step(0, 64, 0, 0, 0.000011, 0, false, 1_002),
                new CriticalPacketCheck.Step(0, 64.000011, 0, 0, -0.000011, 0, false, 1_003));

        assertTrue(CriticalPacketCheck.isWurstPacketCriticalSequence(steps));
        assertTrue(CriticalPacketCheck.isWurstPacketCriticalSequence(new ArrayDeque<>(steps)));
    }

    @Test void ignoresOrdinaryJumpAndNonSpoofMovementSequences() {
        List<CriticalPacketCheck.Step> jump = List.of(
                new CriticalPacketCheck.Step(0, 64, 0, 0, 0.42, 0, false, 1_000),
                new CriticalPacketCheck.Step(0, 64.42, 0, 0, 0.333, 0, false, 1_050),
                new CriticalPacketCheck.Step(0, 64.753, 0, 0, 0.248, 0, false, 1_100),
                new CriticalPacketCheck.Step(0, 65.001, 0, 0, 0.165, 0, false, 1_150));

        assertFalse(CriticalPacketCheck.isWurstPacketCriticalSequence(jump));
    }

    @Test void recognizesTheMaceDmgVerticalRoundTripDelta() {
        double spoof = Math.sqrt(500.0);
        assertTrue(CriticalPacketCheck.isWurstMaceDmgDelta(0, spoof, 0));
        assertTrue(CriticalPacketCheck.isWurstMaceDmgDelta(0, -spoof, 0));
        assertFalse(CriticalPacketCheck.isWurstMaceDmgDelta(0.1, spoof, 0));
        assertFalse(CriticalPacketCheck.isWurstMaceDmgDelta(0, 0.42, 0));
    }

    @Test void distinguishesMiniJumpFromFullJumpAndAOnePacketBump() {
        var takeoff = new CriticalPacketCheck.Step(0, 64, 0, 0, 0.1, 0, false, 1_000);
        var continuation = new CriticalPacketCheck.Step(0, 64.1, 0, 0, 0.018, 0, false, 1_050);
        assertTrue(CriticalPacketCheck.isMiniJumpTakeoff(takeoff));
        assertTrue(CriticalPacketCheck.isMiniJumpContinuation(takeoff, continuation));
        assertFalse(CriticalPacketCheck.isMiniJumpTakeoff(new CriticalPacketCheck.Step(
                0, 64, 0, 0, 0.42, 0, false, 1_000)));
        assertFalse(CriticalPacketCheck.isMiniJumpContinuation(takeoff, new CriticalPacketCheck.Step(
                0, 64.1, 0, 0, 0.1, 0, true, 1_050)));
    }
}

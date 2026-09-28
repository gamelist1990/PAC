package org.pexserver.pac.check.core;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ContinuousViolationScoreTest {
    private static final long HALF_LIFE = 30_000;
    private static final long COMBO_WINDOW = 2_000;

    @Test void nearbyViolationsIncreaseTheScoreByAnIncreasingMultiplier() {
        ContinuousViolationScore score = new ContinuousViolationScore();
        UUID player = UUID.randomUUID();

        var first = add(score, player, 1_000);
        var second = add(score, player, 1_500);
        var third = add(score, player, 2_000);

        assertEquals(1.0, first.multiplier());
        assertEquals(1.5, second.multiplier(), 1e-9);
        assertEquals(2.25, third.multiplier(), 1e-9);
        assertTrue(third.score() > second.score());
    }

    @Test void aQuietGapResetsComboAndTheActiveScoreDecays() {
        ContinuousViolationScore score = new ContinuousViolationScore();
        UUID player = UUID.randomUUID();
        add(score, player, 1_000);
        add(score, player, 1_500);

        var afterGap = add(score, player, 8_000);
        assertEquals(1, afterGap.streak());
        assertEquals(1.0, afterGap.multiplier());
        assertTrue(afterGap.score() < 3.5, "old evidence should decay during an idle gap");
    }

    @Test void aThresholdCanBeClaimedAgainAfterDecayBelowIt() {
        ContinuousViolationScore score = new ContinuousViolationScore();
        UUID player = UUID.randomUUID();
        add(score, player, 1_000);
        add(score, player, 1_500);

        assertTrue(score.claimThreshold(player, 1.0));
        assertFalse(score.claimThreshold(player, 1.0));
        assertTrue(score.score(player, 200_000, HALF_LIFE) < 1.0);
        assertFalse(score.claimThreshold(player, 1.0));
        add(score, player, 200_001);
        assertTrue(score.claimThreshold(player, 1.0));
    }

    @Test void decayWorksWhenTheFirstMonotonicTimestampIsZero() {
        ContinuousViolationScore score = new ContinuousViolationScore();
        UUID player = UUID.randomUUID();

        score.add(player, 0, 2, HALF_LIFE, COMBO_WINDOW, 1.5, 8, 1000);

        assertEquals(1.0, score.score(player, HALF_LIFE, HALF_LIFE), 1e-9);
    }

    private static ContinuousViolationScore.Update add(ContinuousViolationScore score,
                                                        UUID player, long now) {
        return score.add(player, now, 1, HALF_LIFE, COMBO_WINDOW, 1.5, 8, 1000);
    }
}

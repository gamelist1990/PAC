package org.pexserver.pac.check.java.action;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FastBreakCheckTest {
    @Test void earlyFinishOnlyMatchesItsBlockWorldAndExpiry() {
        UUID world = UUID.randomUUID();
        var pending = new FastBreakCheck.PendingFinish(world, 4, 70, -2,
                1_500, "early finish");

        assertTrue(pending.matches(world, 4, 70, -2, 1_500));
        assertFalse(pending.matches(UUID.randomUUID(), 4, 70, -2, 1_000));
        assertFalse(pending.matches(world, 5, 70, -2, 1_000));
        assertFalse(pending.matches(world, 4, 70, -2, 1_501));
        assertTrue(pending.expired(1_501));
    }

    @Test void earlyStopEvidenceSurvivesTheVanillaDelayedDestroyWindow() {
        long finishAt = 2_000_000_000L;
        int minimumTicks = FastBreakCheck.minimumVanillaDestroyTicks(0.01111f);
        assertEquals(90, minimumTicks);

        long expiresAt = FastBreakCheck.pendingExpiry(finishAt, minimumTicks, 20);

        assertTrue(expiresAt - finishAt > 3_500_000_000L,
                "delayed destruction waits for full 1.0 progress, not the 0.7 STOP threshold");
        assertTrue(expiresAt - finishAt <= 5_000_000_000L,
                "pending evidence remains bounded for stale or abandoned mining attempts");
    }

    @Test void rebreakingCooldownDetectsWurstsImmediateNextTargetButAllowsVanillaDelay() {
        long previousBreak = 1_000_000_000L;

        assertTrue(FastBreakCheck.tooSoonAfterPreviousBreak(previousBreak + 199_999_999L, previousBreak));
        assertFalse(FastBreakCheck.tooSoonAfterPreviousBreak(previousBreak + 200_000_000L, previousBreak));
        assertFalse(FastBreakCheck.tooSoonAfterPreviousBreak(previousBreak - 1, previousBreak));
        assertFalse(FastBreakCheck.tooSoonAfterPreviousBreak(previousBreak + 250_000_000L, previousBreak));
    }

    @Test void instantHasteBreakStillRequiresTheVanillaNextTargetDelay() {
        float haste255Progress = 2.0f;
        long acceptedAt = 1_000_000_000L;
        assertEquals(0, FastBreakCheck.minimumVanillaDestroyTicks(haste255Progress));
        assertFalse(FastBreakCheck.tooEarly(0, haste255Progress),
                "a single Haste 255 block may legally break instantly");
        assertTrue(FastBreakCheck.tooSoonAfterPreviousBreak(
                acceptedAt + 50_000_000L, acceptedAt),
                "Wurst's destroyDelay=0 exposes itself on the next block even when each block is instant");
        assertFalse(FastBreakCheck.tooSoonAfterPreviousBreak(
                acceptedAt + 250_000_000L, acceptedAt));
    }

    @Test void matchesVanillaStopDestroyBlockProgressThreshold() {
        assertEquals(0, FastBreakCheck.minimumVanillaElapsedTicks(0.7f));
        assertEquals(1, FastBreakCheck.minimumVanillaElapsedTicks(0.35f));
        assertEquals(3, FastBreakCheck.minimumVanillaElapsedTicks(0.2f));
        assertEquals(6, FastBreakCheck.minimumVanillaElapsedTicks(0.1f));
    }

    @Test void fastBreakIsFlaggedWhenBothServerAndClientTimeAreTooShort() {
        assertTrue(FastBreakCheck.tooEarly(0.2222, 0.01111f));
    }

    @Test void serverSevenTenthStopGraceDoesNotPassAsVanillaClientCompletion() {
        assertTrue(FastBreakCheck.tooEarly(0.6, 0.2f),
                "server progress can reach 0.7 one tick before the vanilla client finishes at 1.0");
        assertTrue(FastBreakCheck.reachedVanillaFinish(0.8, 0.2f));
    }

    @Test void delayedDestroyThatWaitsForFullProgressIsNotFlagged() {
        assertTrue(FastBreakCheck.reachedVanillaFinish(0.99, 0.01111f));
    }

    @Test void clientTimeMustReachVanillaFinishEvenWhenServerLagAdvancesItsGraceThreshold() {
        var progress = new BreakProgressEstimator(0.01111f, 1_000_000_000L);
        var estimate = progress.estimate(4_150_000_000L);
        assertTrue(FastBreakCheck.tooEarly(estimate.progress(), estimate.latestSpeed()));

        var completed = progress.estimate(6_000_000_000L);
        assertFalse(FastBreakCheck.tooEarly(completed.progress(), completed.latestSpeed()));
    }

    @Test void repeatedEarlyStopsKeepAccumulatingUntilVanillaProgressIsReached() {
        float speed = 0.01111f;
        int minimumTicks = FastBreakCheck.minimumVanillaDestroyTicks(speed);
        long start = 1_000_000_000L;
        var progress = new BreakProgressEstimator(speed, start);

        for (int tick = 1; tick <= 20; tick++) {
            long now = start + tick * 50_000_000L;
            progress.sample(speed, now);
            var estimate = progress.estimate(now);
            assertTrue(FastBreakCheck.tooEarly(estimate.progress(), speed),
                    "repeated Wurst stop packets remain premature before vanilla client completion");
        }

        long legalStopAt = start + (minimumTicks - 1L) * 50_000_000L;
        var legalEstimate = progress.estimate(legalStopAt);
        assertFalse(FastBreakCheck.tooEarly(legalEstimate.progress(), speed));
    }

    @Test void oneProgressTickOfNumericalVarianceDoesNotFlagVanillaFinish() {
        assertFalse(FastBreakCheck.tooEarly(0.96, 0.02f));
    }

    @Test void breakProgressResamplesSpeedChangesDuringMining() {
        var progress = new BreakProgressEstimator(0.01f, 1_000_000_000L);
        progress.sample(0.1f, 2_000_000_000L);
        var estimate = progress.estimate(2_500_000_000L);
        assertEquals(1.2, estimate.progress(), 1.0e-6);
        assertEquals(0.1f, estimate.latestSpeed());
    }
}

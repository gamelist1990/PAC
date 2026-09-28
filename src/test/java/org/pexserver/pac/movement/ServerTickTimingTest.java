package org.pexserver.pac.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ServerTickTimingTest {
    @Test void estimates20And10And5TpsWithoutDisablingPrediction() {
        for (long interval : new long[] {50, 100, 200}) {
            var timing = new ServerTickTiming();
            for (int tick = 0; tick <= 30; tick++) timing.tick(tick * interval * 1_000_000);
            var snapshot = timing.snapshot(30 * interval * 1_000_000);
            assertEquals(1_000.0 / interval, snapshot.tps(), .01);
            assertFalse(snapshot.recovering());
            assertEquals(1, snapshot.physicsFrames(interval, 0));
        }
    }

    @Test void detectsStallBeforeMainThreadCanReportItAndRecovers() {
        var timing = new ServerTickTiming();
        timing.tick(0);
        assertTrue(timing.snapshot(500_000_000).recovering());
        timing.tick(10_000_000_000L);
        assertTrue(timing.snapshot(10_000_000_000L).recovering());
        for (int tick = 1; tick <= 250; tick++) timing.tick(10_000_000_000L + tick * 50_000_000L);
        var recovered = timing.snapshot(22_500_000_000L);
        assertFalse(recovered.recovering());
        assertFalse(recovered.delayed());
        assertEquals(0, recovered.timerAllowanceMillis());
    }

    @Test void delayedFramesUseObservedPacketsWithBoundedReplayWork() {
        var timing = new ServerTickTiming.Snapshot(100, 0, false, 0);
        assertEquals(1, timing.physicsFrames(200, 0));
        assertEquals(4, timing.physicsFrames(0, 3));
        assertEquals(40, timing.physicsFrames(10_000, 500));
        assertEquals(4, ServerTickTiming.Snapshot.NORMAL.physicsFrames(200, 0));
    }

    @Test void queuedFreeFallRetainsClientGravityAtLowTps() {
        for (int serverMillis : new int[] {50, 100, 200}) {
            var timing = new ServerTickTiming();
            for (int tick = 0; tick <= 30; tick++) timing.tick(tick * serverMillis * 1_000_000L);
            var snapshot = timing.snapshot(30L * serverMillis * 1_000_000);
            var sequence = new AirMotionSequence();
            sequence.timing(snapshot);
            double y = 100, dy = 0;
            int evaluated = 0;
            for (int clientTick = 0; clientTick < 20; clientTick++) {
                long arrival = 10_000 + (clientTick * 50L / serverMillis) * serverMillis;
                y += dy;
                var environment = new MotionEnvironment.Snapshot(false, true, false, false,
                        0, .1, 0, y, 0, clientTick * 50 / serverMillis, arrival);
                var sample = sequence.accept(true, true, 0, y, 0, 0, environment, arrival);
                assertFalse(sample.abrupt());
                if (sample.evaluated()) {
                    evaluated++;
                    assertEquals(0, sample.offset(), 1e-8, "TPS=" + snapshot.tps());
                }
                dy = AirPredictor.nextDisplacement(dy);
            }
            assertTrue(evaluated >= 15, "low TPS must not simply skip the entire simulation");
        }
    }

    @Test void lowTpsDoesNotMultiplyTheLegalSpeedCap() {
        var envelope = new SustainedSpeedEnvelope();
        envelope.timing(new ServerTickTiming.Snapshot(200, 0, false, 0));
        boolean flagged = false;
        for (int packet = 0; packet < 20; packet++) {
            double x = packet * .8;
            var environment = new MotionEnvironment.Snapshot(true, false, false, false,
                    0, .1, x, 64, 0, packet / 4, System.currentTimeMillis());
            flagged |= envelope.accept(x, 64, 0, environment, null,
                    1_000_000_000L + packet / 4 * 200_000_000L, false).flagged();
        }
        assertTrue(flagged);
    }

    @Test void tenSecondServerBacklogIsAllowedButSustainedTimerStillFlags() {
        var timing = new ServerTickTiming();
        timing.tick(0);
        timing.tick(10_000_000_000L);
        long allowance = timing.snapshot(10_000_000_000L).timerAllowanceMillis();
        var timer = new MovementPacketTimer();
        for (int packet = 0; packet < 200; packet++)
            assertFalse(timer.accept(10_000_000_000L, allowance));
        boolean flagged = false;
        for (int packet = 1; packet < 1_000; packet++) {
            long now = 10_000_000_000L + packet * 25_000_000L;
            if (packet % 2 == 0) timing.tick(now);
            flagged |= timer.accept(now, timing.snapshot(now).timerAllowanceMillis());
        }
        assertTrue(flagged);
    }

    @Test void legalClockDoesNotFlagWhenServerAllowanceExpires() {
        var timer = new MovementPacketTimer();
        for (int packet = 0; packet < 200; packet++)
            assertFalse(timer.accept(10_000_000_000L, 10_000));
        for (int packet = 1; packet <= 500; packet++)
            assertFalse(timer.accept(10_000_000_000L + packet * 50_000_000L,
                    packet < 230 ? 10_000 : 0), "allowance expiry at packet " + packet);
    }
}

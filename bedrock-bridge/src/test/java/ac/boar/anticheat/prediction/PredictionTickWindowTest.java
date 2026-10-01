package ac.boar.anticheat.prediction;

import org.junit.jupiter.api.Test;
import static ac.boar.anticheat.prediction.PredictionTickWindow.Step.*;
import static org.junit.jupiter.api.Assertions.*;

class PredictionTickWindowTest {
    @Test void continuousTicksPredictIdenticallyAcrossLatencyProfiles() {
        // The scheduler may deliver these ticks at 50ms intervals, after a
        // long server stall, or in one burst. Arrival time is not physics time.
        for (int profile = 0; profile < 3; profile++) {
            PredictionTickWindow window = new PredictionTickWindow();
            for (int tick = 0; tick < 400; tick++) {
                assertEquals(tick < 3 ? RESYNC : PREDICT, window.observe(tick));
            }
        }
    }

    @Test void missingInputsResyncForBoundedNumberOfTicks() {
        PredictionTickWindow window = warmedUp();
        assertEquals(RESYNC, window.observe(100));
        assertEquals(RESYNC, window.observe(101));
        assertEquals(RESYNC, window.observe(102));
        assertEquals(PREDICT, window.observe(103));
        assertEquals(PREDICT, window.observe(104));
    }

    @Test void stalePacketsCannotMoveAnchorOrExtendGrace() {
        PredictionTickWindow window = warmedUp();
        assertEquals(STALE, window.observe(9));
        assertEquals(STALE, window.observe(3));
        assertEquals(STALE, window.observe(-1));
        assertEquals(PREDICT, window.observe(10));
    }

    @Test void veryLargeGapNeverRequestsUnboundedPhysicsReplay() {
        PredictionTickWindow window = warmedUp();
        assertEquals(RESYNC, window.observe(Long.MAX_VALUE - 3));
        assertEquals(RESYNC, window.observe(Long.MAX_VALUE - 2));
        assertEquals(RESYNC, window.observe(Long.MAX_VALUE - 1));
        assertEquals(PREDICT, window.observe(Long.MAX_VALUE));
        assertEquals(STALE, window.observe(0));
    }

    private PredictionTickWindow warmedUp() {
        PredictionTickWindow window = new PredictionTickWindow();
        for (int i = 0; i < 10; i++) window.observe(i);
        return window;
    }
}

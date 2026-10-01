package ac.boar.anticheat.check.impl.timer;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InputTimingWindowTest {
    @Test void joinAndLagBurstDoNotFlag() {
        InputTimingWindow window = new InputTimingWindow();
        long now = 1_000_000_000L;
        long tick = 0;
        for (; tick < 120; tick++) {
            now += 50_000_000L;
            assertFalse(window.observe(tick, now, false));
        }
        now += 1_000_000_000L;
        assertFalse(window.observe(tick++, now, false));
        for (int i = 0; i < 19; i++) {
            now += 1_000_000L;
            assertFalse(window.observe(tick++, now, false));
        }
        for (int i = 0; i < 100; i++) {
            now += 50_000_000L;
            assertFalse(window.observe(tick++, now, false));
        }
    }

    @Test void sustainedFastClockStillFlags() {
        InputTimingWindow window = new InputTimingWindow();
        long now = 1_000_000_000L;
        boolean detected = false;
        for (int tick = 0; tick < 400; tick++) {
            now += 25_000_000L;
            detected |= window.observe(tick, now, false);
        }
        assertTrue(detected);
    }

    @Test void exemptAndDiscontinuousTicksResetEvidence() {
        InputTimingWindow window = new InputTimingWindow();
        assertFalse(window.observe(39, 1_000_000_000L, true));
        assertFalse(window.observe(113, 8_000_000_000L, true));
        assertFalse(window.observe(113, 8_050_000_000L, false));
        assertFalse(window.observe(10_000, 8_100_000_000L, false));
        for (int i = 1; i <= 100; i++) {
            assertFalse(window.observe(10_000 + i, 8_100_000_000L + i * 50_000_000L, false));
        }
    }
}

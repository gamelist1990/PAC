package org.pexserver.pac;

import org.junit.jupiter.api.Test;
import org.pexserver.pac.movement.BlockPushSuppressionWindow;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockPushSuppressionWindowTest {
    @Test void sixStableEmbeddedFramesBecomeEvidence() {
        var window = new BlockPushSuppressionWindow();
        for (int tick = 1; tick < 6; tick++) {
            assertFalse(window.sample(tick, BlockPushSuppressionWindow.EAST,
                    0.0, 0.0, true, false).suspicious());
        }
        assertTrue(window.sample(6, BlockPushSuppressionWindow.EAST,
                0.0, 0.0, true, false).suspicious());
    }

    @Test void vanillaOutwardProgressPreventsEvidence() {
        var window = new BlockPushSuppressionWindow();
        for (int tick = 1; tick <= 12; tick++) {
            assertFalse(window.sample(tick, BlockPushSuppressionWindow.EAST,
                    0.10, 0.0, true, false).suspicious());
        }
    }

    @Test void anyAvailableOutwardDirectionIsAccepted() {
        var window = new BlockPushSuppressionWindow();
        int directions = BlockPushSuppressionWindow.WEST | BlockPushSuppressionWindow.SOUTH;
        for (int tick = 1; tick <= 10; tick++) {
            assertFalse(window.sample(tick, directions,
                    0.0, 0.05, true, false).suspicious());
        }
    }

    @Test void escapingBlockAndServerMotionResetEvidence() {
        var window = new BlockPushSuppressionWindow();
        for (int tick = 1; tick <= 5; tick++)
            window.sample(tick, BlockPushSuppressionWindow.NORTH, 0, 0, true, false);

        assertFalse(window.sample(6, BlockPushSuppressionWindow.NORTH,
                0, 0, false, false).suspicious());

        for (int tick = 7; tick <= 11; tick++)
            window.sample(tick, BlockPushSuppressionWindow.NORTH, 0, 0, true, false);
        assertFalse(window.sample(12, BlockPushSuppressionWindow.NORTH,
                0, 0, true, true).suspicious());
    }

    @Test void oppositeAndTangentialMotionDoNotCountAsVanillaPushOut() {
        var window = new BlockPushSuppressionWindow();
        for (int tick = 1; tick < 6; tick++) {
            assertFalse(window.sample(tick, BlockPushSuppressionWindow.WEST,
                    0.03, 0.02, true, false).suspicious());
        }
        assertTrue(window.sample(6, BlockPushSuppressionWindow.WEST,
                0.03, 0.02, true, false).suspicious());
    }
}

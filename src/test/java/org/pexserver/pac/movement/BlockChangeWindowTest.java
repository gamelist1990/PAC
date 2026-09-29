package org.pexserver.pac.movement;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockChangeWindowTest {
    @Test void changedFloorOnlyUncertainsIntersectingSweptPlayerAabbs() {
        UUID world = UUID.randomUUID();
        BlockChangeWindow window = new BlockChangeWindow();
        window.record(world, 0, 63, 0, 1_000, 1_200);

        assertTrue(window.affects(world, 0.2, 64, 0.2, 0.35, 64, 0.2, 1_050),
                "a changed support block below the player's feet is relevant to step physics");
        assertFalse(window.affects(world, 3, 64, 3, 3.2, 64, 3, 1_050),
                "a distant block must not suppress collision checks");
        assertFalse(window.affects(UUID.randomUUID(), 0.2, 64, 0.2, 0.35, 64, 0.2, 1_050),
                "block changes from another world must not apply");
        assertFalse(window.affects(world, 0.2, 64, 0.2, 0.35, 64, 0.2, 1_200),
                "expired changes must not keep movement uncertain");
    }

    @Test void sweptPathCatchesChangedBlocksBetweenPositionPackets() {
        UUID world = UUID.randomUUID();
        BlockChangeWindow window = new BlockChangeWindow();
        window.record(world, 2, 64, 0, 1_000, 1_200);

        assertTrue(window.affects(world, 0, 64, 0, 3, 64, 0, 1_050));
        assertFalse(window.affects(world, 0, 64, 0, 1, 64, 0, 1_050));
    }

    @Test void bodyBlockChangeCoversVanillaPushOutMovement() {
        UUID world = UUID.randomUUID();
        BlockChangeWindow window = new BlockChangeWindow();
        // A falling sand/gravel block can become solid inside the player's body
        // (feet at Y=64, body extends to about Y=65.8) before the client pushes out.
        window.record(world, 0, 65, 0, 1_000, 1_200);

        assertTrue(window.affects(world, 0.5, 64, 0.5, 0.595, 64, 0.5, 1_050),
                "horizontal pushOutOfBlocks displacement must be tied to the changed body cell");
        assertFalse(window.affects(world, 3, 64, 3, 3.095, 64, 3, 1_050),
                "the falling-block grace must stay spatially local");
    }

    @Test void eventHistoryHasAFixedBound() {
        UUID world = UUID.randomUUID();
        BlockChangeWindow window = new BlockChangeWindow();
        for (int x = 0; x <= 32; x++) window.record(world, x, 64, 0, 1_000, 1_200);

        assertFalse(window.affects(world, 0.5, 64, 0.5, 0.5, 64, 0.5, 1_050));
        assertTrue(window.affects(world, 32.5, 64, 0.5, 32.5, 64, 0.5, 1_050));
    }
}

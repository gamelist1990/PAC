package org.pexserver.pac.check.java.action;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class InventoryMoveTrackerTest {
    @Test void openingOrClosingContainerCannotImmediatelyProveAnImpossibleAction() {
        var tracker = new InventoryMoveTracker();
        UUID player = UUID.randomUUID();
        tracker.setPlayerInventoryOpen(player, true);
        assertFalse(tracker.establishedContainerOpen(player, 100));
        tracker.setContainerOpen(player, true, 100);
        assertFalse(tracker.establishedContainerOpen(player, 100));
        assertFalse(tracker.establishedContainerOpen(player, 101));
        assertTrue(tracker.establishedContainerOpen(player, 102));
        tracker.setContainerOpen(player, false, 103);
        assertFalse(tracker.establishedContainerOpen(player, 104));
    }
    @Test void localInventoryInteractionEnablesMovementDetectionUntilClose() {
        var tracker = new InventoryMoveTracker();
        UUID player = UUID.randomUUID();
        var start = new InventoryMoveTracker.Position(0, 64, 0);
        tracker.seedPosition(player, start, 0);
        tracker.setPlayerInventoryOpen(player, true);
        assertTrue(tracker.inventoryOpen(player));

        var input = tracker.input(player,
                new InventoryMoveTracker.Input(true, false, false, false,
                        false, false, true), 1_000, true);
        assertFalse(input.hasDirectionalInput());
        var moved = tracker.movement(player,
                new InventoryMoveTracker.Position(0.15, 64, 0), 0, false, true, 1_010);
        assertTrue(moved.violation());
        assertTrue(moved.blocked());
        assertEquals(start, moved.rollback());

        tracker.setPlayerInventoryOpen(player, false);
        assertFalse(tracker.inventoryOpen(player));
        assertFalse(tracker.movement(player,
                new InventoryMoveTracker.Position(0.2, 64, 0), 0, false, true, 1_020).violation());
    }
}

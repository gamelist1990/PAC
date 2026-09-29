package org.pexserver.pac.check.java.action;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryMoveCheckStateTest {
    @Test void withheldCloseKeepsServerInventoryStateOpen() {
        var check = new InventoryMoveCheck(null);
        UUID player = UUID.randomUUID();

        assertFalse(check.inventoryOpen(player));
        check.playerInventoryOpened(player);
        assertTrue(check.inventoryOpen(player),
                "MoreCarry suppressing ContainerClose must leave the server-owned inventory state open");
        check.clientWindowClosed(player);
        assertFalse(check.inventoryOpen(player));
    }
}

package org.pexserver.pac.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JavaRollbackWindowTest {
    @Test void everyRejectedMoveUsesItsImmediatePreCancelPosition() {
        var first = JavaRollbackWindow.select(new JavaRollbackWindow.Position(1, 64, 2),
                3, null, 1_000_000_000L);

        var repeated = JavaRollbackWindow.select(new JavaRollbackWindow.Position(8, 70, 9),
                3, null, 5_999_999_999L);
        var afterCorrection = JavaRollbackWindow.select(new JavaRollbackWindow.Position(9, 71, 10),
                4, null, 5_999_999_999L);

        assertEquals(new JavaRollbackWindow.Position(1, 64, 2), first.position());
        assertEquals(new JavaRollbackWindow.Position(8, 70, 9), repeated.position());
        assertEquals(new JavaRollbackWindow.Position(9, 71, 10), afterCorrection.position());
    }

    @Test void aNewTeleportEpochDoesNotReuseAnOlderRejectedPosition() {
        JavaRollbackWindow.select(new JavaRollbackWindow.Position(1, 64, 2), 3,
                null, 1_000_000_000L);

        var latest = JavaRollbackWindow.select(new JavaRollbackWindow.Position(8, 70, 9), 3,
                null, 6_000_000_000L);
        var afterTeleport = JavaRollbackWindow.select(new JavaRollbackWindow.Position(4, 65, 5), 4,
                null, 6_100_000_000L);

        assertEquals(new JavaRollbackWindow.Position(8, 70, 9), latest.position());
        assertEquals(new JavaRollbackWindow.Position(4, 65, 5), afterTeleport.position());
    }
}

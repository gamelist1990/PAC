package org.pexserver.pac.movement;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EntityPushContactWindowTest {
    @Test void serverConfirmedContactsAreDeduplicatedAndRetainedForThreeSamples() {
        EntityPushContactWindow window = new EntityPushContactWindow();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        window.recordContact(first);
        window.recordContact(first);
        window.recordContact(second);
        assertEquals(2, window.sample());
        assertEquals(2, window.sample());
        assertEquals(2, window.sample());
        assertEquals(0, window.sample());
        assertEquals(0, window.maximum());
    }

    @Test void recordedContactCountIsBounded() {
        EntityPushContactWindow window = new EntityPushContactWindow();
        for (int i = 0; i < 100; i++) window.recordContact(UUID.randomUUID());
        assertEquals(8, window.sample());
        window.clear();
        assertEquals(0, window.maximum());
    }
}

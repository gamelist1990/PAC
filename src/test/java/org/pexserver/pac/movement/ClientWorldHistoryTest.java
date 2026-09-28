package org.pexserver.pac.movement;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientWorldHistoryTest {
    @Test void selectsTheNewestFrameAtOrBeforeTheCompensatedTime() {
        var history = new ClientWorldHistory();
        UUID uuid = UUID.randomUUID();
        history.add(uuid, new ClientWorldHistory.Frame(1, 1_000, null, null));
        history.add(uuid, new ClientWorldHistory.Frame(2, 1_050, null, null));
        history.add(uuid, new ClientWorldHistory.Frame(3, 1_100, null, null));

        assertEquals(2, history.atOrBefore(uuid, 1_075).tick());
        assertEquals(1, history.atOrBefore(uuid, 900).tick());
        assertEquals(3, history.atOrBefore(uuid, 1_500).tick());
    }
}
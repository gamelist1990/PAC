package org.pexserver.pac.movement;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MotionEnvironmentTeleportGenerationTest {
    @Test void pluginTeleportAndDirectPositionPacketInvalidatePendingCorrection() {
        MotionEnvironment environment = new MotionEnvironment(null);
        UUID player = UUID.randomUUID();
        UUID other = UUID.randomUUID();

        assertEquals(0, environment.teleportGeneration(player));
        environment.markTeleport(player);
        assertEquals(1, environment.teleportGeneration(player));
        environment.teleportSent(player, 7);
        assertEquals(2, environment.teleportGeneration(player));
        assertEquals(0, environment.teleportGeneration(other));

        environment.forget(player);
        assertEquals(0, environment.teleportGeneration(player));
    }
}

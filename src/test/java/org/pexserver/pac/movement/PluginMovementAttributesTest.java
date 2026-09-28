package org.pexserver.pac.movement;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PluginMovementAttributesTest {
    @Test void rewrittenBukkitMoveDestinationIsRecognizedAsExternalMovement() {
        Location original = new Location(null, 10, 64, -3);
        assertFalse(MotionEnvironment.positionChanged(original, original.clone()));
        assertFalse(MotionEnvironment.positionChanged(original, null));
        assertTrue(MotionEnvironment.positionChanged(original, new Location(null, 10.01, 64, -3)));
        assertTrue(MotionEnvironment.positionChanged(original, new Location(null, 10, 65, -3)));
    }

    @Test void changesToClientPhysicsAttributesRequireResynchronization() {
        var normal = new MotionEnvironment.Snapshot(true, false, false, false,
                0, 0.1, 0, 64, 0, 1, 1000);
        var faster = new MotionEnvironment.Snapshot(true, false, false, false,
                0, 0.5, 0, 64, 0, 2, 1050);
        var stationary = new MotionEnvironment.Snapshot(true, false, false, false,
                0, 0.1, 0, 64, 0, 2, 1050);
        assertTrue(MotionEnvironment.movementAttributesChanged(normal, faster));
        assertFalse(MotionEnvironment.movementAttributesChanged(normal, stationary));
        var slowFalling = new MotionEnvironment.Snapshot(true, false, false, false, false,
                0, 0.1, 0, 64, 0, 2, 1050,
                false, false, 0.6f, 0.08, 0.91f, 0.98f, 0.42f, true);
        assertTrue(MotionEnvironment.movementAttributesChanged(normal, slowFalling));
        var levitation = new MotionEnvironment.Snapshot(true, false, false, false, false,
                0, 0.1, 0, 64, 0, 2, 1050,
                false, false, 0.6f, 0.08, 0.91f, 0.98f, 0.42f, false, 0);
        assertTrue(MotionEnvironment.movementAttributesChanged(normal, levitation));
    }

}

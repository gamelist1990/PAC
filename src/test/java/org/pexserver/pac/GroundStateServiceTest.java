package org.pexserver.pac;

import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.Test;
import org.pexserver.pac.movement.ground.GroundStateService;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroundStateServiceTest {
    private static final BoundingBox FEET = new BoundingBox(0.21, 63.994, 0.21, 0.79, 64.002, 0.79);

    @Test void fullBlockImmediatelyBelowSupportsPlayer() {
        assertTrue(GroundStateService.supports(FEET, new BoundingBox(0, 63, 0, 1, 64, 1), 64));
    }

    @Test void pathBlockFifteenSixteenthsHighSupportsAtItsExactTopFace() {
        var pathFeet = new BoundingBox(0.21, 63.9315, 0.21, 0.79, 63.9395, 0.79);
        assertTrue(GroundStateService.supports(pathFeet,
                new BoundingBox(0, 63, 0, 1, 63.9375, 1), 63.9375));
    }

    @Test void distantFloorDoesNotCreateGroundState() {
        assertFalse(GroundStateService.supports(FEET, new BoundingBox(0, 62, 0, 1, 63, 1), 64));
    }

    @Test void nearbyWallWithoutHorizontalOverlapDoesNotSupport() {
        assertFalse(GroundStateService.supports(FEET, new BoundingBox(1, 63, 0, 2, 64, 1), 64));
    }

    @Test void hoveringAboveTheFloorDoesNotCountAsGrounded() {
        var hoveringFeet = new BoundingBox(0.21, 64.024, 0.21, 0.79, 64.032, 0.79);
        assertFalse(GroundStateService.supports(hoveringFeet, new BoundingBox(0, 63, 0, 1, 64, 1), 64.03));
    }
}

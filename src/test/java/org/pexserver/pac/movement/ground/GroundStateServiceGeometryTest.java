package org.pexserver.pac.movement.ground;

import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroundStateServiceGeometryTest {
    @Test void translatedPathBlockSupportMatchesWorldSpaceAabb() {
        double footY = 63.9375;
        var feet = new BoundingBox(0.1, footY - 0.006, 0.1,
                0.7, footY + 0.002, 0.7);
        var pathBlockShape = new BoundingBox(0, 0, 0, 1, 0.9375, 1);
        var worldShape = pathBlockShape.clone().shift(0, 63, 0);

        assertTrue(GroundStateService.supportsTranslated(feet, pathBlockShape,
                0, 63, 0, footY));
        assertTrue(GroundStateService.supports(feet, worldShape, footY));
    }

    @Test void translatedSupportRejectsTouchingEdgesAndDifferentHeight() {
        double footY = 64;
        var feet = new BoundingBox(0.1, footY - 0.006, 0.1,
                0.7, footY + 0.002, 0.7);
        var fullBlock = new BoundingBox(0, 0, 0, 1, 1, 1);
        var higherStep = new BoundingBox(0, 0, 0, 1, 0.5, 1);

        assertFalse(GroundStateService.supportsTranslated(feet, fullBlock,
                1, 63, 0, footY));
        assertFalse(GroundStateService.supportsTranslated(feet, higherStep,
                0, 63, 0, footY));
    }
}

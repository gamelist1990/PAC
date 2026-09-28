package org.pexserver.pac.movement.ground;

import net.minecraft.world.phys.AABB;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroundEntitySupportTest {
    private static final BoundingBox FEET = new BoundingBox(
            -0.29, 63.994, -0.29, 0.29, 64.002, 0.29);

    @Test void boatTopAabbCanSupportFeetWithinEntityInterpolationTolerance() {
        assertTrue(GroundStateService.supportsEntity(FEET,
                new AABB(-0.7, 63.4, -0.7, 0.7, 64.04, 0.7), 64));
        assertFalse(GroundStateService.supportsEntity(FEET,
                new AABB(0.5, 63.4, 0.5, 1.5, 64.04, 1.5), 64));
        assertFalse(GroundStateService.supportsEntity(FEET,
                new AABB(-0.7, 62.8, -0.7, 0.7, 63.7, 0.7), 64));
    }
}

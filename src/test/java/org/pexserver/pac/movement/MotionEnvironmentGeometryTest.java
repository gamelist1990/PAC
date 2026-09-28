package org.pexserver.pac.movement;

import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MotionEnvironmentGeometryTest {
    @Test void translatedOverlapMatchesBukkitAtContactsAndIntersections() {
        BoundingBox shape = new BoundingBox(0, 0, 0, 1, 0.9375, 1);
        BoundingBox[] probes = {
                new BoundingBox(10.2, 63.8, -3.8, 10.8, 64.2, -3.2),
                new BoundingBox(10.2, 64.9375, -3.8, 10.8, 65.1, -3.2),
                new BoundingBox(11, 64, -3.8, 11.6, 64.8, -3.2),
                new BoundingBox(10.2, 64.2, -3.8, 10.8, 64.8, -3.2),
                new BoundingBox(10.2, 64.2, -3, 10.8, 64.8, -2.5)
        };
        for (BoundingBox probe : probes) {
            assertEquals(probe.overlaps(shape.clone().shift(10, 64, -4)),
                    MotionEnvironment.overlapsTranslated(probe, shape, 10, 64, -4));
        }
    }
}

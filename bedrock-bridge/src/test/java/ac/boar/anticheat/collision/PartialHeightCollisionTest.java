package ac.boar.anticheat.collision;

import ac.boar.anticheat.util.math.Box;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PartialHeightCollisionTest {
    @Test void stairShapesAreDetectedAwayFromWorldOriginAndAtNegativeCoordinates() {
        for (int x : new int[]{0, 1250, -1250}) {
            var area = new Box(x + 0.1F, 63.9F, -321.9F, x + 0.9F, 65.1F, -321.1F);
            var lowerStair = new Box(x, 64, -322, x + 1, 64.5F, -321);
            var upperStair = new Box(x + 0.5F, 64.5F, -322, x + 1, 65, -321);
            assertTrue(PartialHeightCollision.near(lowerStair, area));
            assertTrue(PartialHeightCollision.near(upperStair, area));
            assertFalse(PartialHeightCollision.near(lowerStair.offset(0, 64, 0), area));
        }
    }

    @Test void fullBlocksAndDistantStairsDoNotGrantPartialHeightGrace() {
        var area = new Box(10, 64, 10, 11, 65, 11);
        assertFalse(PartialHeightCollision.near(new Box(10, 64, 10, 11, 65, 11), area));
        assertFalse(PartialHeightCollision.near(new Box(15, 64, 10, 16, 64.5F, 11), area));
    }
}

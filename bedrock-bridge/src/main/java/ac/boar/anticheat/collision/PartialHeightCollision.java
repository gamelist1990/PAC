package ac.boar.anticheat.collision;

import ac.boar.anticheat.util.math.Box;

/** findCollision returns world coordinates; do not offset them a second time. */
public final class PartialHeightCollision {
    private PartialHeightCollision() {}

    public static boolean near(Box worldCollision, Box stepArea) {
        float height = worldCollision.maxY - worldCollision.minY;
        return height > Box.EPSILON && height < 1.0F - Box.EPSILON
                && worldCollision.expand(0.05F).intersects(stepArea);
    }
}

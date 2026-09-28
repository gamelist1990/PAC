package org.pexserver.pac.movement;

import org.bukkit.util.BoundingBox;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MotionEnvironmentBlockChangeTest {
    @Test void blockChangeGraceTracksLatencyButHasHardBounds() {
        assertEquals(100, MotionEnvironment.blockChangeGraceMillis(-1));
        assertEquals(100, MotionEnvironment.blockChangeGraceMillis(0));
        assertEquals(170, MotionEnvironment.blockChangeGraceMillis(120));
        assertEquals(500, MotionEnvironment.blockChangeGraceMillis(900));
    }

    @Test void pistonMotionWindowCoversInterpolationAndPacketDelay() {
        assertEquals(300, MotionEnvironment.pistonMotionGraceMillis(0));
        assertEquals(350, MotionEnvironment.pistonMotionGraceMillis(100));
        assertEquals(750, MotionEnvironment.pistonMotionGraceMillis(1_000));
    }

    @Test void onlyPlayersWhoseBoundingBoxesCanReachTheChangedBlockAreTracked() {
        BoundingBox affectedArea = MotionEnvironment.blockChangeRegion(0, 64, 0);
        assertTrue(affectedArea.overlaps(new BoundingBox(0.2, 64, 0.2,
                0.8, 65.8, 0.8)));
        assertFalse(affectedArea.overlaps(new BoundingBox(3, 64, 0,
                3.6, 65.8, 0.6)));
    }

    @Test void clipsInfiniteWorldBorderCollisionBoxesToFinitePredictionBounds() {
        MotionCollisionSnapshot.Box clipped = MotionEnvironment.clipWorldBorderBox(
                Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY,
                10, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                8, 60, -2, 12, 66, 2);

        assertEquals(new MotionCollisionSnapshot.Box(8, 60, -2, 10, 66, 2), clipped);
        assertNull(MotionEnvironment.clipWorldBorderBox(
                20, 0, 0, 21, 1, 1, 8, 60, -2, 12, 66, 2));
    }

    @Test void usesVanillasWorldBorderCollisionShapeNearTheEastEdge() {
        WorldBorder border = new WorldBorder();
        border.setCenter(0, 0);
        border.setSize(10);
        List<MotionCollisionSnapshot.Box> clipped = new ArrayList<>();
        for (AABB source : border.getCollisionShape().toAabbs()) {
            MotionCollisionSnapshot.Box box = MotionEnvironment.clipWorldBorderBox(
                    source.minX, source.minY, source.minZ, source.maxX, source.maxY, source.maxZ,
                    4, 60, -1, 6, 66, 1);
            if (box != null) clipped.add(box);
        }

        assertTrue(clipped.stream().anyMatch(box -> box.minX() <= 5 && box.maxX() > 5
                && box.minZ() <= 0 && box.maxZ() >= 0));
        assertTrue(clipped.stream().allMatch(box -> Double.isFinite(box.minX())
                && Double.isFinite(box.minY()) && Double.isFinite(box.minZ())
                && Double.isFinite(box.maxX()) && Double.isFinite(box.maxY())
                && Double.isFinite(box.maxZ())));

        MotionCollisionSnapshot snapshot = new MotionCollisionSnapshot(
                3, 60, -1, 8, 66, 1, 0.6, 1.8, 0.6, 0.6,
                clipped, true, 0L);
        assertEquals(0.1, snapshot.resolve(4.6, 60, 0, 1, 0, 0, false).getFirst().x(), 1.0e-9);
    }

    @Test void blockScanBoundsFollowTheRoundedVanillaBorderCollisionWalls() {
        assertEquals(-6, MotionEnvironment.firstBlockWithinWorldBorder(-7, -5.25));
        assertEquals(5, MotionEnvironment.lastBlockWithinWorldBorder(7, 5.25));
        assertEquals(-3, MotionEnvironment.firstBlockWithinWorldBorder(-2.2, -5.25));
        assertEquals(1, MotionEnvironment.lastBlockWithinWorldBorder(1.8, 5.25));
    }
}

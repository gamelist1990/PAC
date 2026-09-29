package org.pexserver.pac.check.shared;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NoClipGeometryTest {
    private static final NoClipGeometry.Aabb PLAYER =
            new NoClipGeometry.Aabb(-0.3, 0, -0.3, 0.3, 1.8, 0.3);
    private static final NoClipGeometry.Aabb WALL =
            new NoClipGeometry.Aabb(1, 0, -1, 2, 2, 1);

    @Test void detectsMovementThatPassesThroughAFullBlock() {
        NoClipGeometry.Hit hit = NoClipGeometry.unavoidableHit(PLAYER, 2, 0, 0,
                List.of(WALL), 0.10, false);

        assertNotNull(hit);
        assertTrue(hit.time() > 0 && hit.time() < 1);
    }

    @Test void doesNotFlagWhenThereIsNoCollisionShape() {
        assertNull(NoClipGeometry.unavoidableHit(PLAYER, 2, 0, 0,
                List.of(), 0.10, false));
    }

    @Test void ignoresAPlayerAlreadyIntersectingTheShape() {
        NoClipGeometry.Aabb overlappingBody = new NoClipGeometry.Aabb(
                0.8, 0, -0.3, 1.4, 1.8, 0.3);

        assertNull(NoClipGeometry.unavoidableHit(overlappingBody, 0.5, 0, 0,
                List.of(WALL), 0.10, false));
        assertNotNull(NoClipGeometry.unavoidableHit(overlappingBody, 0.5, 0, 0,
                List.of(WALL), 0.10, true));
    }

    @Test void acceptsAPathThatCanSlideAroundACorner() {
        NoClipGeometry.Aabb body = new NoClipGeometry.Aabb(-0.3, 0, 1.2, 0.3, 1.8, 1.8);
        NoClipGeometry.Aabb cornerWall = new NoClipGeometry.Aabb(1, 0, 0, 2, 2, 1);

        assertNull(NoClipGeometry.unavoidableHit(body, 2.5, 0, -1.5,
                List.of(cornerWall), 0.10, false));
    }

    @Test void collisionUsesTheBlockShapeHeightRatherThanAFullCube() {
        NoClipGeometry.Aabb slab = new NoClipGeometry.Aabb(1, 0, -1, 2, 0.5, 1);
        NoClipGeometry.Aabb bodyStandingOnSlab =
                new NoClipGeometry.Aabb(-0.3, 0.5, -0.3, 0.3, 2.3, 0.3);

        assertNull(NoClipGeometry.unavoidableHit(bodyStandingOnSlab, 2, 0, 0,
                List.of(slab), 0.10, false));
        assertNotNull(NoClipGeometry.unavoidableHit(PLAYER, 2, 0, 0,
                List.of(slab), 0.10, false));
    }
    @Test void detectsSmallDownwardPhaseBeyondCollisionTolerance() {
        NoClipGeometry.Aabb body =
                new NoClipGeometry.Aabb(-0.3, 1.0, -0.3, 0.3, 2.8, 0.3);
        NoClipGeometry.Aabb floor =
                new NoClipGeometry.Aabb(-1, 0, -1, 1, 1, 1);

        assertNotNull(NoClipGeometry.unavoidableHit(body, 0, -0.07840000152, 0,
                List.of(floor), 0.03, false));
    }

    @Test void tinyContactJitterInsideToleranceIsIgnored() {
        NoClipGeometry.Aabb body =
                new NoClipGeometry.Aabb(-0.3, 1.0, -0.3, 0.3, 2.8, 0.3);
        NoClipGeometry.Aabb floor =
                new NoClipGeometry.Aabb(-1, 0, -1, 1, 1, 1);

        assertNull(NoClipGeometry.unavoidableHit(body, 0, -0.02, 0,
                List.of(floor), 0.03, false));
    }


}

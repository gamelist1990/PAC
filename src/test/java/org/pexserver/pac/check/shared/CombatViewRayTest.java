package org.pexserver.pac.check.shared;

import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatViewRayTest {
    @Test void packetPitchSetsTheExactTargetAabbRayDistance() {
        Vector eye = new Vector(0, 1.6, 0);
        BoundingBox target = new BoundingBox(-0.3, 0, 3, 0.3, 1.8, 3.6);

        assertEquals(3.0, CombatViewRay.intersectionDistance(eye, 0, 0, target, 0, 6), 1.0e-9);
        assertTrue(CombatViewRay.intersectionDistance(eye, 0, 20, target, 0, 6) > 3.0,
                "pitch must be included so the occlusion ray matches the packet view");
    }

    @Test void wallMustBlockClearlyBeforeTheTargetHitbox() {
        assertTrue(CombatViewRay.blockedBeforeTarget(1.5, 3.0, 0.12));
        assertFalse(CombatViewRay.blockedBeforeTarget(2.92, 3.0, 0.12),
                "a block almost touching the hitbox is covered by collision and latency tolerance");
        assertFalse(CombatViewRay.blockedBeforeTarget(Double.NaN, 3.0, 0.12));
    }

    @Test void rayMustCrossTheTargetHitbox() {
        Vector eye = new Vector(0, 1.6, 0);
        assertTrue(CombatViewRay.intersects(eye, 0, 0,
                new BoundingBox(-0.3, 0, 2, 0.3, 1.8, 2.6), 0.18, 12));
        assertFalse(CombatViewRay.intersects(eye, 0, 0,
                new BoundingBox(-0.3, 0, -1, 0.3, 1.8, -0.4), 0.18, 12));
    }

    @Test void expandedHitboxAllowsSmallAimAndLatencyOffsets() {
        Vector eye = new Vector(0, 1.6, 0);
        BoundingBox edgeTarget = new BoundingBox(0.1, 0, 2, 0.9, 1.8, 2.6);
        assertTrue(CombatViewRay.intersects(eye, 0, 0, edgeTarget, 0.18, 12));
        assertFalse(CombatViewRay.intersects(eye, 0, 0, edgeTarget, 0, 12));
    }

    @Test void frontHemisphereAllowsMobileScreenAttacksOutsideTheExactCameraRay() {
        Vector eye = new Vector(0, 1.6, 0);
        BoundingBox frontSideTarget = new BoundingBox(2, 0, 2, 2.6, 1.8, 2.6);

        assertFalse(CombatViewRay.intersects(eye, 0, 0, frontSideTarget, 0, 12));
        assertFalse(CombatViewRay.entirelyBehind(eye, 0, frontSideTarget, 0.03));
    }

    @Test void onlyTheCompleteTargetAabbBehindTheFacingPlaneIsImpossible() {
        Vector eye = new Vector(0, 1.6, 0);
        BoundingBox behind = new BoundingBox(-0.3, 0, -2, 0.3, 1.8, -1.5);
        BoundingBox touchingFacingPlane = new BoundingBox(-0.3, 0, -0.5, 0.3, 1.8, 0.1);

        assertTrue(CombatViewRay.entirelyBehind(eye, 0, behind, 0.03));
        assertFalse(CombatViewRay.entirelyBehind(eye, 0, touchingFacingPlane, 0.03));
        assertTrue(CombatViewRay.entirelyBehind(eye, 90,
                new BoundingBox(1.5, 0, -0.3, 2, 1.8, 0.3), 0.03));
        assertFalse(CombatViewRay.entirelyBehind(eye, 90,
                new BoundingBox(-2, 0, -0.3, -1.5, 1.8, 0.3), 0.03));
    }
}

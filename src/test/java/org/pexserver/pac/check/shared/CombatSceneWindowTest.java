package org.pexserver.pac.check.shared;

import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CombatSceneWindowTest {
    private static final UUID TARGET = UUID.randomUUID();
    private static CombatSceneWindow.Position pos(double x, double y, double z) {
        return new CombatSceneWindow.Position(x, y, z);
    }
    private CombatSceneWindow scene() {
        var s = new CombatSceneWindow();
        s.spawn(1, TARGET, pos(0, 0, 3), 1000);
        s.barrier(11, 1000); assertTrue(s.acknowledge(11)); return s;
    }
    @Test void movingEnemyIsEvaluatedWithoutStaticBoxOrReportedPingGate() {
        var s = scene();
        s.move(1, pos(1, 0, 3), 1050, false);
        s.move(1, pos(2, 0, 3), 1100, false);
        var view = s.view(1, TARGET, 1400);
        assertTrue(view.ready());
        var boxes = view.boxes(new BoundingBox(-0.3, 0, -0.3, 0.3, 1.8, 0.3));
        var eyes = List.of(new Vector(0, 1.6, 0));
        assertFalse(CombatAttackGeometry.evaluate(eyes, 0, 0, boxes, 0.1, 64).viewMiss());
        var wrong = CombatAttackGeometry.evaluate(eyes, 180, 0, boxes, 0.1, 64);
        assertTrue(wrong.viewMiss()); assertTrue(wrong.behind());
    }
    @Test void interpolationBetweenPacketsCanExplainAValidMovingAttack() {
        var from = new BoundingBox(-1.3, 0, 2.7, -0.7, 1.8, 3.3);
        var to = from.clone().shift(2, 0, 0);
        var eye = new Vector(0, 1.6, 0);
        assertFalse(CombatViewRay.intersects(eye, 0, 0, from, 0, 10));
        assertFalse(CombatViewRay.intersects(eye, 0, 0, to, 0, 10));
        assertEquals(2.7, CombatViewRay.sweptIntersectionDistance(eye, 0, 0, from, to, 0, 10), 1e-9);
    }
    @Test void diagonalSweepDoesNotInventHitSpaceInAnEnclosingUnion() {
        var from = new BoundingBox(0, 0, 0, .1, 1, .1);
        var to = from.clone().shift(3, 0, 3);
        var eye = new Vector(-1, .5, 2.5);
        assertTrue(CombatViewRay.intersects(eye, -90, 0, from.clone().union(to), 0, 3));
        assertTrue(Double.isNaN(CombatViewRay.sweptIntersectionDistance(eye, -90, 0, from, to, 0, 3)));
    }
    @Test void sweptRayAgreesWithManyVanillaInterpolationFractions() {
        var random = new Random(817);
        for (int run = 0; run < 300; run++) {
            var from = new BoundingBox(random.nextDouble()*4-2, 0, 2, random.nextDouble()*4+3, 1.8, 2.6);
            var to = from.clone().shift(random.nextDouble()*6-3, random.nextDouble()*2-1, random.nextDouble()*3);
            double fraction = random.nextDouble();
            var box = from.clone().shift((to.getMinX()-from.getMinX())*fraction,
                    (to.getMinY()-from.getMinY())*fraction, (to.getMinZ()-from.getMinZ())*fraction);
            var eye = new Vector(0, 1.6, -2);
            var aim = box.getCenter().subtract(eye);
            float yaw = (float)Math.toDegrees(Math.atan2(-aim.getX(), aim.getZ()));
            float pitch = (float)-Math.toDegrees(Math.atan2(aim.getY(), Math.hypot(aim.getX(), aim.getZ())));
            assertTrue(Double.isFinite(CombatViewRay.sweptIntersectionDistance(eye, yaw, pitch, from, to, .01, 20)));
        }
    }
    @Test void exactMissAlsoMissesEverySampledInterpolationAndJumpHeight() {
        var random = new Random(900);
        var eye = new Vector(0, 1.6, 0);
        for (int run=0; run<250; run++) {
            var from = new BoundingBox(random.nextDouble()*6-3, random.nextDouble()*2,
                    random.nextDouble()*5+1, random.nextDouble()*6+4, 5, 8);
            var to = from.clone().shift(random.nextDouble()*6-3, random.nextDouble()*3-1.5,
                    random.nextDouble()*4-2);
            float yaw=(float)(random.nextDouble()*360), pitch=(float)(random.nextDouble()*160-80);
            if (Double.isFinite(CombatViewRay.sweptIntersectionDistance(eye,yaw,pitch,from,to,.1,20))) continue;
            for (int step=0;step<=100;step++) {
                double t=step/100.0;
                var box=from.clone().shift((to.getMinX()-from.getMinX())*t,
                        (to.getMinY()-from.getMinY())*t,(to.getMinZ()-from.getMinZ())*t);
                assertFalse(CombatViewRay.intersects(eye,yaw,pitch,box,.1,20));
            }
        }
    }

    @Test void teleportHasNoHittableCorridorBetweenUnconnectedPositions() {
        var s = scene(); s.move(1, pos(10, 0, 3), 1050, true);
        assertFalse(s.view(1, TARGET, 1100).ready());
        s.barrier(12, 1100); s.acknowledge(12);
        for (int i=0;i<6;i++) s.movement();
        var view = s.view(1, TARGET, 1400);
        assertTrue(view.ready());
        assertTrue(view.segments().stream().allMatch(p -> p.from().equals(p.to())));
        assertEquals(10, view.segments().getFirst().from().x());
    }
    @Test void unknownReplayAndWrongTargetCannotCreateAcknowledgedGeometry() {
        var s = new CombatSceneWindow(); s.spawn(1, TARGET, pos(0,0,3), 1000);
        assertFalse(s.acknowledge(123)); assertFalse(s.view(1, TARGET, 1010).ready());
        s.barrier(42, 1050); assertTrue(s.acknowledge(42)); assertFalse(s.acknowledge(42));
        assertFalse(s.view(1, UUID.randomUUID(), 1100).ready());
        s.destroy(1); s.spawn(1, UUID.randomUUID(), pos(0,0,3), 1150);
        assertFalse(s.view(1, TARGET, 1200).ready());
    }
    @Test void withheldReplyExpiresCombatInsteadOfKeepingAnOldSceneForever() {
        var s = scene(); s.move(1, pos(5, 0, 3), 1050, false); s.barrier(12, 1050);
        assertTrue(s.view(1, TARGET, 5000).ready());
        assertTrue(s.view(1, TARGET, 6051).expired());
        assertFalse(s.view(1, TARGET, 6051).ready());
        assertTrue(s.acknowledge(12)); assertTrue(s.view(1, TARGET, 6100).ready());
    }
    @Test void queuedNetworkBurstCannotPruneTheClientsPreviousInterpolationOriginImmediately() {
        var s = scene(); s.move(1, pos(5,0,3), 2000, false);
        s.barrier(12, 2000); s.acknowledge(12);
        for(int i=0;i<5;i++) s.movement();
        assertEquals(0, s.view(1, TARGET, 2010).segments().getFirst().from().x());
    }
    @Test void overflowNeverUsesIncompleteHistoryAsEvidenceAndRecoversAfterSettling() {
        var s = scene();
        for(int i=0;i<300;i++) s.move(1, pos(i*.01,0,3), 1050+i, false);
        assertFalse(s.view(1, TARGET, 1400).ready());
        s.barrier(12, 2000); s.acknowledge(12);
        for(int i=0;i<6;i++) s.movement();
        assertTrue(s.view(1, TARGET, 2100).ready());
    }
}

package org.pexserver.pac.movement;

import org.bukkit.entity.Pose;
import org.junit.jupiter.api.Test;
import org.pexserver.pac.packet.JavaInputCapture;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CrawlMotionTest {
    @Test void drySwimmingPoseUsesSlowInputWithoutShiftKey() {
        assertTrue(PoseMotionPolicy.slowInput(false, Pose.SWIMMING, false, false));
        assertTrue(PoseMotionPolicy.slowInput(false, Pose.FALL_FLYING, false, false));
        assertFalse(PoseMotionPolicy.slowInput(false, Pose.SWIMMING, false, true));
        assertFalse(PoseMotionPolicy.slowInput(false, Pose.FALL_FLYING, true, false));
        assertFalse(PoseMotionPolicy.slowInput(false, Pose.STANDING, false, false));
    }

    @Test void bodyHeightChangeHasBoundedSynchronizationRatherThanPermanentExemption() {
        var transition = new PoseMotionPolicy.Transition();
        transition.sample(.6, 1.8, 1_000, 100);
        assertFalse(transition.uncertain(1_000));
        transition.sample(.6, .6, 1_050, 100);
        assertTrue(transition.uncertain(1_100));
        transition.sample(.6, .6, 1_200, 100);
        assertFalse(transition.uncertain(1_250));
        transition.sample(.6, 1.8, 1_300, 100);
        assertTrue(transition.uncertain(1_350));
    }

    private MotionEnvironment.Snapshot crawl(double z, long now) {
        return new MotionEnvironment.Snapshot(true, false, false, true,
                0, .1, 0, 64, z, (int) (now / 50), now);
    }

    private MotionCollisionSnapshot tunnel(long now) {
        return new MotionCollisionSnapshot(-3, 62, -3, 3, 68, 6,
                .6, .6, .6, .6,
                List.of(new MotionCollisionSnapshot.Box(-3, 63, -3, 3, 64, 6),
                        new MotionCollisionSnapshot.Box(-3, 65, -3, 3, 66, 6)), true, now);
    }

    @Test void crawlingThroughOneBlockTunnelMatchesCollisionResolvedGroundPhysics() {
        var sequence = new GroundMotionSequence();
        var input = new MotionPredictor.Input(true, false, false, false, false, false, false);
        var velocity = new MotionPredictor.Motion(0, 0, 0);
        sequence.rebase(0, 64, 0, 0, 0, 0, crawl(0, 1_000), 1_000);
        double z = 0;
        for (int step = 1; step <= 20; step++) {
            long now = 1_000 + step * 50L;
            velocity = MotionPredictor.predictGroundInputClient(velocity, velocity, 0,
                    .1, .6f, .91f, .3f, 1, input).closest();
            z += velocity.dz();
            var sample = sequence.accept(true, true, 0, 64, z, 0, crawl(z, now), now,
                    new JavaInputCapture.Window(input, null), null, tunnel(now));
            assertTrue(sample.evaluated());
            assertFalse(sample.abrupt());
            assertFalse(sample.impossibleTakeoff());
            assertEquals(0, sample.offset(), 1e-8);
            assertEquals(0, sample.speedExcess(), 1e-8);
        }
    }

    @Test void crawlPoseDoesNotAuthorizeNormalRunningSpeed() {
        var sequence = new GroundMotionSequence();
        sequence.rebase(0, 64, 0, 0, 0, .065, crawl(0, 1_000), 1_000);
        var sample = sequence.accept(true, true, 0, 64, .28, 0, crawl(.28, 1_050), 1_050,
                null, null, tunnel(1_050));
        assertTrue(sample.evaluated());
        assertTrue(sample.speedExcess() > .15);
    }
}

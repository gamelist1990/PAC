package org.pexserver.pac.movement;

import org.junit.jupiter.api.Test;
import org.pexserver.pac.packet.JavaInputCapture;

import static org.junit.jupiter.api.Assertions.*;

class CombatAndGlideTransitionTest {
    private MotionEnvironment.Snapshot ground(boolean sprint, double speed, double z, long now) {
        return new MotionEnvironment.Snapshot(true, false, sprint, false,
                0, speed, 0, 64, z, (int) (now / 50), now);
    }

    @Test void attackWindowCoversOnlyTheNextPositionSample() {
        var window = new CombatSprintWindow();
        window.attack(1_010);
        assertTrue(window.active(1_050));

        window.position();
        assertFalse(window.active(1_100));
    }

    @Test void attackWindowExpiresWithoutAPositionSample() {
        var window = new CombatSprintWindow();
        window.attack(1_010);
        assertFalse(window.active(1_500));
    }

    @Test void attackDoesNotChangeTheOfficialGroundPhysicsCandidate() {
        var input = new MotionPredictor.Input(true, false, false, false, false, false, true);
        var previous = new MotionPredictor.Motion(0, 0, .28);
        var actual = MotionPredictor.predictGroundInputClient(
                previous, previous, 0,
                .13, .6f, .91f, 1, 1, input).closest();
        var sequence = new GroundMotionSequence();
        sequence.rebase(0, 64, 0, 0, 0, .28, ground(true, .13, 0, 1_000), 1_000);
        var sample = sequence.accept(true, true, actual.dx(), 64, actual.dz(), 0,
                ground(true, .13, actual.dz(), 1_050), 1_050,
                new JavaInputCapture.Window(input, null), null, null);
        assertTrue(sample.evaluated());
        assertEquals(0, sample.offset(), 1e-8);
        assertEquals(0, sample.speedExcess(), 1e-8);
    }

    @Test void attackDoesNotUseAStaleMovementAttribute() {
        var stale = ground(false, .1, 0, 1_025);
        var input = new MotionPredictor.Input(true, false, false, false, false, false, true);
        var previous = new MotionPredictor.Motion(0, 0, .28);
        var actual = MotionPredictor.predictGroundInputClient(previous, previous, 0,
                .13, .6f, .91f, 1, 1, input).closest();
        var sequence = new GroundMotionSequence();
        sequence.rebase(0, 64, 0, 0, 0, .28, stale, 1_025);
        var current = ground(false, .1, actual.dz(), 1_050);
        var sample = sequence.accept(true, true, 0, 64, actual.dz(), 0,
            current, 1_050, new JavaInputCapture.Window(input, null), null, null);
        assertTrue(sample.evaluated());
        assertTrue(sample.speedExcess() > .02 && sample.speedExcess() < .04);
    }

    @Test void normalAttackMovementRemainsLegal() {
        var input = new MotionPredictor.Input(true, false, false, false, false, false, false);
        var previous = new MotionPredictor.Motion(0, 0, .1);
        var actual = MotionPredictor.predictGroundInputClient(
            previous, previous, 0, .1, .6f, .91f, 1, 1, input).closest();
        var sequence = new GroundMotionSequence();
        sequence.rebase(0, 64, 0, 0, 0, .1, ground(false, .1, 0, 1_000), 1_000);
        var sample = sequence.accept(true, true, 0, 64, actual.dz(), 0,
            ground(false, .1, actual.dz(), 1_050), 1_050,
            new JavaInputCapture.Window(input, null), null, null);
        assertTrue(sample.evaluated());
        assertEquals(0, sample.offset(), 1e-8);
        assertEquals(0, sample.speedExcess(), 1e-8);
    }

        @Test void attackDoesNotHideAnActualSpeedExcess() {
        var input = new MotionPredictor.Input(true, false, false, false,
            false, false, true);
        var previous = new MotionPredictor.Motion(0, 0, .28);
        var walkingEnvironment = ground(false, .1, 0, 1_000);
        var actual = MotionPredictor.predictGroundInputClient(previous, previous, 0,
            .13, .6f, .91f, 1, 1, input).closest();

        var sequence = new GroundMotionSequence();
        sequence.rebase(0, 64, 0, previous.dx(), previous.dy(), previous.dz(),
            walkingEnvironment, 1_000);
        // The movement packet can still contain sprint acceleration even when
        // the server snapshot already exposes the post-hit walking attribute.
        var sample = sequence.accept(true, true, actual.dx(), 64, actual.dz(), 0,
            ground(false, .1, actual.dz(), 1_050), 1_050,
            new JavaInputCapture.Window(input, null), null, null);

        assertTrue(sample.evaluated());
        assertTrue(sample.speedExcess() > .02);
        assertTrue(sample.offset() > .02);
        }

    @Test void attackTransitionDoesNotPermitArbitraryAcceleration() {
        var sequence = new GroundMotionSequence();
        sequence.rebase(0, 64, 0, 0, 0, .28, ground(false, .13, 0, 1_000), 1_000);
        var sample = sequence.accept(true, true, 0, 64, .8, 0,
                ground(false, .13, .8, 1_050), 1_050, null, null, null);
        assertTrue(sample.evaluated());
        assertTrue(sample.speedExcess() > .4);
        assertTrue(sample.offset() > .4);
    }

    @Test void attackMovementSampleDoesNotSeedTheSustainedSpeedEnvelope() {
        var envelope = new SustainedSpeedEnvelope();
        envelope.accept(0, 64, 0, ground(false, .1, 0, 1_000), null,
                1_000_000_000L, false);

        envelope.suspend(.6, 64, 0, 1_050_000_000L);
        assertFalse(envelope.accept(.88, 64, 0, ground(false, .1, .88, 1_100), null,
                1_100_000_000L, false).flagged());
    }

    @Test void glideEndRetainsMomentumWithoutExtendingItsTransitionEverySample() {
        var window = new GlideTransitionWindow();
        window.observe(true, .478, 1_000, 100);
        window.observe(false, .25, 1_050, 100);
        var end = window.get();
        assertEquals(.478, end.horizontalSpeed(), 1e-9);
        for (long now = 1_100; now <= 1_500; now += 50) window.observe(false, .2, now, 100);
        assertEquals(end, window.get());
        assertFalse(window.get().settling(1_500));
        window.observe(true, .5, 1_550, 100);
        assertTrue(window.get().sequence() > end.sequence());
    }

    @Test void repeatedGlideLandingTailIsLegalButConstantExcessAfterwardIsNot() {
        var envelope = new SustainedSpeedEnvelope();
        double z = 0;
        long now = 1_000;
        for (int hop = 0; hop < 8; hop++) {
            envelope.serverVelocity(.478, 0, 64, z, now * 1_000_000);
            double speed = .478;
            for (int frame = 0; frame < 8; frame++) {
                now += 50;
                z += speed;
                assertFalse(envelope.accept(0, 64, z,
                        ground(false, .1, z, System.currentTimeMillis()), null,
                        now * 1_000_000, false).flagged());
                speed = speed * .546 + .098;
            }
        }
        boolean flagged = false;
        for (int frame = 0; frame < 50; frame++) {
            now += 50;
            z += .8;
            flagged |= envelope.accept(0, 64, z, ground(false, .1, z, System.currentTimeMillis()),
                    null, now * 1_000_000, false).flagged();
        }
        assertTrue(flagged);
    }
}

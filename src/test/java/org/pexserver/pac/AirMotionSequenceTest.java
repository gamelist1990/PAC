package org.pexserver.pac;

import org.junit.jupiter.api.Test;
import org.pexserver.pac.movement.AirMotionSequence;
import org.pexserver.pac.movement.AirPredictor;
import org.pexserver.pac.movement.MotionEnvironment;
import org.pexserver.pac.movement.MotionCollisionSnapshot;
import org.pexserver.pac.movement.MotionPredictor;
import org.pexserver.pac.packet.ExternalMotionTracker;
import org.pexserver.pac.packet.JavaInputCapture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AirMotionSequenceTest {
    private MotionEnvironment.Snapshot air(int tick, double x, double y) {
        return new MotionEnvironment.Snapshot(false, true, false, false,
                0, 0.1, x, y, 0, tick, 1000 + tick * 50L);
    }

    private MotionEnvironment.Snapshot verticalAir(int tick, double x, double y) {
        return new MotionEnvironment.Snapshot(false, false, false, false, false,
                0, 0.1, x, y, 0, tick, 1000 + tick * 50L,
                false, false, 0.6f, 0.08, 0.91f, 0.98f, 0.42f,
                false, -1, true, 0.3f, 1.0f, 1.0f, 1.0f);
    }

    private MotionEnvironment.Snapshot webAir(int tick, double x, double y, double z) {
        return new MotionEnvironment.Snapshot(false, true, false, false, false,
                0, 0.1, x, y, z, tick, 1000 + tick * 50L,
                false, false, 0.6f, 0.08, 0.91f, 0.98f, 0.42f,
                false, -1, true, 0.3f, 1.0f, 0.25f, 0.05f);
    }

    @Test void rollbackRebasesAirPredictionToTheSetbackAnchor() {
        AirMotionSequence sequence = new AirMotionSequence();
        sequence.accept(true, true, 20, 70, 30, 0, air(1, 20, 70), 1_000);

        sequence.rebase(2, 64, 3, 0.02, -0.1, 0.03,
                air(2, 2, 64), 1_050);

        assertEquals(new AirMotionSequence.Position(2, 64, 3), sequence.lastPosition());
    }

    @Test void cobwebAirModelAcceptsStickyMotionAndExposesNoSlowExcess() {
        var legitimate = new AirMotionSequence();
        double y = 70, dy = 0;
        legitimate.accept(true, true, 0, y, 0, 0, webAir(1, 0, y, 0), 1000);
        dy = AirPredictor.nextDisplacement(dy) * 0.05;
        y += dy;
        legitimate.accept(true, false, 0, y, 0, 0, webAir(2, 0, y, 0), 1050);
        dy = AirPredictor.nextDisplacement(dy) * 0.05;
        y += dy;
        var legal = legitimate.accept(true, false, 0, y, 0, 0,
                webAir(3, 0, y, 0), 1100);
        assertTrue(legal.evaluated());
        assertEquals(0, legal.offset(), 1.0e-9);

        var noSlow = new AirMotionSequence();
        noSlow.accept(true, true, 0, 70, 0, 0, webAir(1, 0, 70, 0), 1000);
        noSlow.accept(true, false, 0, 70, 0.1, 0, webAir(2, 0, 70, 0.1), 1050);
        var fast = noSlow.accept(true, false, 0, 70, 0.2, 0,
                webAir(3, 0, 70, 0.2), 1100);
        assertTrue(fast.horizontalEvaluated());
        assertTrue(fast.horizontalOffset() > 0.055);

        var spider = new AirMotionSequence();
        spider.accept(true, true, 0, 70, 0, 0, webAir(1, 0, 70, 0), 1000);
        spider.accept(true, false, 0, 70.1, 0, 0, webAir(2, 0, 70.1, 0), 1050);
        var climbing = spider.accept(true, false, 0, 70.2, 0, 0,
                webAir(3, 0, 70.2, 0), 1100);
        assertTrue(climbing.evaluated());
        assertTrue(climbing.offset() > 0.04);
    }

    @Test void lookOnlyPacketsDoNotFabricateVerticalDisplacementSamples() {
        var sequence = new AirMotionSequence();
        assertFalse(sequence.accept(true, 0, 70, 0, air(1, 0, 70), 1050).evaluated());
        assertFalse(sequence.accept(false, 0, 0, 0, air(2, 0, 70), 1100).evaluated());
        assertFalse(sequence.accept(false, 0, 0, 0, air(2, 0, 70), 1101).evaluated());
        assertFalse(sequence.accept(false, 0, 0, 0, air(3, 0, 70), 1150).evaluated());
    }

    @Test void repeatedSameTickLookOnlyPacketsDoNotBecomePhysicsFrames() {
        var sequence = new AirMotionSequence();
        var snapshot = air(1, 0, 70);
        sequence.accept(true, 0, 70, 0, snapshot, 1050);
        sequence.accept(false, 0, 0, 0, snapshot, 1051);
        var third = sequence.accept(false, 0, 0, 0, snapshot, 1052);
        assertFalse(third.evaluated());
    }

    @Test void ordinaryFallingMatchesAfterBaseline() {
        var sequence = new AirMotionSequence();
        sequence.accept(true, 0, 70, 0, air(1, 0, 70), 1050);
        sequence.accept(true, 0, 69.92, 0, air(2, 0, 69.92), 1100);
        double nextY = 69.92 + AirPredictor.nextDisplacement(-0.08);
        var third = sequence.accept(true, 0, nextY, 0, air(3, 0, nextY), 1150);
        assertTrue(third.evaluated());
        assertEquals(0, third.offset(), 1e-12);
    }

    @Test void glideFallSpeedCapIsStillPredictedWhenOnlyVerticalAirIsTrusted() {
        var sequence = new AirMotionSequence();
        sequence.accept(true, true, 0, 70, 0, 0, air(1, 0, 70), 1050);
        sequence.accept(true, false, 0, 69.92, 0, 0,
                verticalAir(2, 0, 69.92), 1100);

        double y = 69.92;
        AirMotionSequence.Sample result = null;
        for (int tick = 3; tick <= 8; tick++) {
            // Wurst Glide's default fall-speed setting clamps descent to -0.125.
            y -= 0.125;
            result = sequence.accept(true, false, 0, y, 0, 0,
                    verticalAir(tick, 0, y), 1050 + (tick - 1) * 50L);
        }

        assertTrue(result != null && result.evaluated());
        assertTrue(result.offset() > 0.06,
                "persistent capped fall speed should diverge from vanilla gravity");
        assertFalse(result.horizontalEvaluated(),
                "vertical-only samples must not invent horizontal evidence");
    }

    @Test void collisionResolvedAirModelChecksGlideAccelerationOverTerrain() {
        var sequence = new AirMotionSequence();
        sequence.accept(true, true, 0, 70, 0, 0, air(1, 0, 70), 1050,
                null, null, clearCollision(1050));
        sequence.accept(true, false, 0, 69.92, 0, 0,
                verticalAir(2, 0, 69.92), 1100, null, null, clearCollision(1100));

        double vanillaY = 69.92 + AirPredictor.nextDisplacement(-0.08);
        var accelerated = sequence.accept(true, false, 0.13, vanillaY, 0, 0,
                verticalAir(3, 0.13, vanillaY), 1150,
                null, null, clearCollision(1150));

        assertTrue(accelerated.horizontalEvaluated());
        assertTrue(accelerated.horizontalOffset() > 0.015,
                "modified air acceleration should exceed the vanilla candidate envelope");
        assertEquals(0, accelerated.offset(), 1.0e-9,
                "horizontal Glide evidence must not contaminate vertical prediction");
    }

    @Test void abruptAirMoveCannotHideBehindBaselineReset() {
        var sequence = new AirMotionSequence();
        sequence.accept(true, 0, 70, 0, air(1, 0, 70), 1050);
        var second = sequence.accept(true, 1.2, 70, 0, air(2, 0, 70), 1100);
        assertTrue(second.abrupt());
    }

    @Test void sustainedHorizontalFlyExceedsAirCandidates() {
        var sequence = new AirMotionSequence();
        sequence.accept(true, true, 0, 70, 0, 0, air(1, 0, 70), 1050);
        sequence.accept(true, false, 0.4, 70, 0, 0, air(2, 0.4, 70), 1100);
        var third = sequence.accept(true, false, 0.8, 70, 0, 0,
                air(3, 0.8, 70), 1150);
        assertTrue(third.horizontalEvaluated());
        assertTrue(third.horizontalOffset() > 0.015);
    }

        @Test void attackDoesNotAddAClientAirSelfSlowdown() {
        var input = new MotionPredictor.Input(true, false, false, false,
                false, false, false);
        var window = new JavaInputCapture.Window(input, null);
        var initial = new MotionPredictor.Motion(0.18, -0.08, 0.02);
        var expected = MotionPredictor.predictAirInputClient(initial,
                new MotionPredictor.Motion(0, 0, 0), 0, false, 0.91f,
                1.0f, 1.0f, input).closest();
        double nextY = 70 + AirPredictor.nextDisplacement(initial.dy());

        var normal = new AirMotionSequence();
        normal.rebase(0, 70, 0, initial.dx(), initial.dy(), initial.dz(),
                air(1, 0, 70), 1_050);
        var ordinary = normal.accept(true, false, expected.dx(), nextY, expected.dz(), 0,
                air(2, expected.dx(), nextY), 1_100, window, null, null);
        assertEquals(0, ordinary.horizontalOffset(), 1.0e-6);

        var combat = new AirMotionSequence();
        combat.rebase(0, 70, 0, initial.dx(), initial.dy(), initial.dz(),
                air(1, 0, 70), 1_050);
        var matched = combat.accept(true, false, expected.dx(), nextY, expected.dz(), 0,
                air(2, expected.dx(), nextY), 1_100, window, null, null, true);
        assertTrue(matched.horizontalEvaluated());
        assertEquals(0, matched.horizontalOffset(), 1.0e-6);
    }

    @Test void combinedMoveAndLookCanUseThePreviousHeading() {
        var input = new MotionPredictor.Input(true, false, false, false,
                false, false, false);
        var window = new JavaInputCapture.Window(input, null);
        var expected = MotionPredictor.predictAirInputClient(
                new MotionPredictor.Motion(0, -0.08, 0),
                new MotionPredictor.Motion(0, 0, 0), 0, false, 0.91f,
                1.0f, 1.0f, input).closest();
        double nextY = 70 + AirPredictor.nextDisplacement(-0.08);
        var sequence = new AirMotionSequence();
        sequence.rebase(0, 70, 0, 0, -0.08, 0, air(1, 0, 70), 1_050);

        var sample = sequence.accept(true, true, expected.dx(), nextY, expected.dz(), 120,
                air(2, expected.dx(), nextY), 1_100, window, null, null);
        assertTrue(sample.horizontalEvaluated());
        assertEquals(0, sample.horizontalOffset(), 1.0e-6);
    }

    @Test void rotationOnlyPacketKeepsBatchedAirMotionConservative() {
        var input = new MotionPredictor.Input(true, false, false, false,
                false, false, false);
        var window = new JavaInputCapture.Window(input, null);
        var first = MotionPredictor.predictAirInputClient(
                new MotionPredictor.Motion(0.1, -0.08, 0),
                new MotionPredictor.Motion(0, 0, 0), 0, false, 0.91f,
                1.0f, 1.0f, input).closest();
        var second = MotionPredictor.predictAirInputClient(first,
                new MotionPredictor.Motion(0, 0, 0), 120, false, 0.91f,
                1.0f, 1.0f, input).closest();
        double x = first.dx() + second.dx(), z = first.dz() + second.dz();
        double firstDy = AirPredictor.nextDisplacement(-0.08);
        double y = 70 + firstDy + AirPredictor.nextDisplacement(firstDy);
        var sequence = new AirMotionSequence();
        sequence.rebase(0, 70, 0, 0.1, -0.08, 0, air(1, 0, 70), 1_050);
        sequence.accept(false, true, 0, 70, 0, 120,
                air(2, 0, 70), 1_100, window, null, null);

        var sample = sequence.accept(true, false, x, y, z, 120,
                air(3, x, y), 1_150, window, null, null);
        assertTrue(sample.horizontalEvaluated());
        assertEquals(0, sample.horizontalOffset(), 1.0e-6);
    }

    @Test void changedGravityAndDragArePredictedAfterBaseline() {
        var sequence = new AirMotionSequence();
        double gravity = 0.04;
        float horizontalDrag = 0.955f;
        float verticalDrag = 0.96f;
        var first = modifiedAir(1, 70, gravity, horizontalDrag, verticalDrag);
        var second = modifiedAir(2, 69.96, gravity, horizontalDrag, verticalDrag);
        double thirdY = 69.96 + AirPredictor.nextDisplacement(-0.04, gravity, verticalDrag);
        var third = modifiedAir(3, thirdY, gravity, horizontalDrag, verticalDrag);
        sequence.accept(true, 0, 70, 0, first, 1050);
        sequence.accept(true, 0, 69.96, 0, second, 1100);
        var result = sequence.accept(true, 0, thirdY, 0, third, 1150);
        assertTrue(result.evaluated());
        assertEquals(0, result.offset(), 1e-12);
    }

    @Test void slowFallingDescentUsesReducedGravityInPacketSequence() {
        var sequence = new AirMotionSequence();
        double firstY = 70, secondY = 69.9;
        double thirdY = secondY + AirPredictor.nextDisplacement(-0.1, 0.08, 0.98f, true);
        sequence.accept(true, 0, firstY, 0, slowFallingAir(1, firstY), 1050);
        sequence.accept(true, 0, secondY, 0, slowFallingAir(2, secondY), 1100);
        var result = sequence.accept(true, 0, thirdY, 0,
                slowFallingAir(3, thirdY), 1150);
        assertTrue(result.evaluated());
        assertEquals(0, result.offset(), 1e-12);
    }

    @Test void levitationMotionIsPredictedAfterBaseline() {
        var sequence = new AirMotionSequence();
        double firstY = 70, secondY = 69.9;
        double thirdY = secondY + AirPredictor.nextDisplacement(-0.1, 0.08, 0.98f,
                false, 0);
        sequence.accept(true, 0, firstY, 0, levitationAir(1, firstY), 1050);
        sequence.accept(true, 0, secondY, 0, levitationAir(2, secondY), 1100);
        var result = sequence.accept(true, 0, thirdY, 0,
                levitationAir(3, thirdY), 1150);
        assertTrue(result.evaluated());
        assertEquals(0, result.offset(), 1e-12);
    }

    @Test void wallAdjacentVerticalPhysicsIsCheckedWithoutHorizontalCollisionAssumptions() {
        var spider = new AirMotionSequence();
        spider.accept(true, 0, 70, 0, wallAir(1, 70), 1050);
        spider.accept(true, 0, 70.2, 0, wallAir(2, 70.2), 1100);
        var rise = spider.accept(true, 0, 70.4, 0, wallAir(3, 70.4), 1150);
        assertTrue(rise.evaluated());
        assertTrue(rise.offset() > 0.06);
        assertFalse(rise.horizontalEvaluated());

        var jump = new AirMotionSequence();
        jump.accept(true, 0, 70, 0, wallAir(1, 70), 1050);
        jump.accept(true, 0, 70.42, 0, wallAir(2, 70.42), 1100);
        var legal = jump.accept(true, 0, 70.42 + AirPredictor.nextDisplacement(0.42), 0,
                wallAir(3, 70.42 + AirPredictor.nextDisplacement(0.42)), 1150);
        assertTrue(legal.evaluated());
        assertEquals(0, legal.offset(), 1e-12);
    }

    @Test void largePluginImpulseContinuesThroughAirPhysics() {
        var sequence = new AirMotionSequence();
        sequence.accept(true, true, 0, 100, 0, 0, air(1, 0, 100), 1050);
        var impulse = new ExternalMotionTracker.Impulse(1, 1.6, 2.0, 0, 1100, false);
        double x = 0, y = 100, dx = 1.6, dy = 2.0;
        AirMotionSequence.Sample result = null;
        for (int i = 0; i < 6; i++) {
            x += dx; y += dy;
            result = sequence.accept(true, false, x, y, 0, 0,
                    air(2 + i, x, y), 1100 + i * 50L, null, impulse);
            assertFalse(result.abrupt(), "legitimate impulse frame " + i);
            dx *= 0.91f;
            dy = AirPredictor.nextDisplacement(dy);
        }
        assertTrue(result.evaluated());
        assertTrue(result.horizontalEvaluated());
        assertEquals(0, result.offset(), 1e-9);
        assertEquals(0, result.horizontalOffset(), 1e-9);
    }

    @Test void ignoredCombatImpulseIsComparedAgainstVanillaAirResponse() {
        var sequence = new AirMotionSequence();
        sequence.accept(true, true, 0, 70, 0, 0, air(1, 0, 70), 1_050,
                null, null, clearCollision(1_050));

        var knockback = new ExternalMotionTracker.Impulse(1, 0.6, 0.3, 0,
                1_099, false, true);
        var ignored = sequence.accept(true, false, 0, 70, 0, 0,
                air(2, 0, 70), 1_100, null, knockback, clearCollision(1_100));

        assertTrue(ignored.externalImpulseMismatch(), "a damage impulse with no matching movement must be checked immediately");
    }

    @Test void fullObservedKnockbackIsNotFlaggedWhenFirstStepModelHasResidual() {
        var sequence = new AirMotionSequence();
        sequence.accept(true, true, 0, 70, 0, 0, air(1, 0, 70), 1_050,
                null, null, clearCollision(1_050));

        double impulseX = -0.076, impulseY = 0.275, impulseZ = 0.205;
        double observedHorizontal = 0.223;
        double scale = observedHorizontal / Math.hypot(impulseX, impulseZ);
        double dx = impulseX * scale, dz = impulseZ * scale;
        var impulse = new ExternalMotionTracker.Impulse(1, impulseX, impulseY, impulseZ,
                1_099, false, true);
        var response = sequence.accept(true, false, dx, 70 + impulseY, dz, 0,
                air(2, dx, 70 + impulseY), 1_100, null, impulse, clearCollision(1_100));

        assertFalse(response.externalImpulseMismatch(),
                "a full observed velocity response must not be labeled AntiKB when the first-frame model differs");
    }

    private MotionCollisionSnapshot clearCollision(long at) {
        return new MotionCollisionSnapshot(-8, 60, -8, 8, 100, 8,
                0.6, 1.8, 0.6, 0.6, java.util.List.of(), true, at);
    }

    private MotionEnvironment.Snapshot modifiedAir(int tick, double y, double gravity,
                                                   float horizontalDrag, float verticalDrag) {
        return new MotionEnvironment.Snapshot(false, true, false, false, false,
                0, 0.1, 0, y, 0, tick, 1000 + tick * 50L,
                false, false, 0.6f, gravity, horizontalDrag, verticalDrag);
    }

    private MotionEnvironment.Snapshot slowFallingAir(int tick, double y) {
        return new MotionEnvironment.Snapshot(false, true, false, false, false,
                0, 0.1, 0, y, 0, tick, 1000 + tick * 50L,
                false, false, 0.6f, 0.08, 0.91f, 0.98f, 0.42f, true);
    }

    private MotionEnvironment.Snapshot levitationAir(int tick, double y) {
        return new MotionEnvironment.Snapshot(false, true, false, false, false,
                0, 0.1, 0, y, 0, tick, 1000 + tick * 50L,
                false, false, 0.6f, 0.08, 0.91f, 0.98f, 0.42f, false, 0);
    }

    private MotionEnvironment.Snapshot wallAir(int tick, double y) {
        return new MotionEnvironment.Snapshot(false, false, false, false, false,
                0, 0.1, 0, y, 0, tick, 1000 + tick * 50L,
                true, false, 0.6f, 0.08, 0.91f, 0.98f, 0.42f, false, -1, true);
    }
}

package org.pexserver.pac;

import org.junit.jupiter.api.Test;
import org.pexserver.pac.movement.ElytraMotionPredictor;
import org.pexserver.pac.movement.ElytraMotionSequence;
import org.pexserver.pac.movement.MotionCollisionSnapshot;
import org.pexserver.pac.movement.MotionEnvironment;
import org.pexserver.pac.movement.MotionPredictor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElytraMotionPredictionTest {
    private static MotionEnvironment.ElytraSnapshot gliding(double x, double y, double z, long at) {
        return gliding(x, y, z, false, at);
    }

    private static MotionEnvironment.ElytraSnapshot gliding(
            double x, double y, double z, boolean firework, long at) {
        return new MotionEnvironment.ElytraSnapshot(true, x, y, z,
                0, 0, 0, 0.08, false, firework, at);
    }

    private static MotionCollisionSnapshot clearCollision(long at) {
        return new MotionCollisionSnapshot(-16, 0, -16, 16, 120, 16,
                0.6, 1.8, 0.6, 0.6, List.of(), true, true, false, at);
    }

    @Test void vanilla26FallFlyingRecurrencePreservesForwardSpeedAndAppliesDrag() {
        var next = ElytraMotionPredictor.next(new MotionPredictor.Motion(0, 0, 1), 0, 0, 0.08);

        assertEquals(0, next.dx(), 1.0e-12);
        assertEquals(-0.01764, next.dy(), 1.0e-9);
        assertEquals(0.991782, next.dz(), 1.0e-9);
    }

    @Test void vanillaFireworkFormulaMatchesMinecraftAcceleration() {
        var boosted = ElytraMotionPredictor.fireworkBoost(
                new MotionPredictor.Motion(0, 0, 0.5), 0, 0);

        assertEquals(0, boosted.dx(), 1.0e-12);
        assertEquals(0, boosted.dy(), 1.0e-12);
        assertEquals(1.1, boosted.dz(), 1.0e-12);
    }

    @Test void vanillaFireworkBoostRemainsInsideElytraReplay() {
        var sequence = new ElytraMotionSequence();
        sequence.accept(true, true, 0, 70, 0, 0, 0,
                gliding(0, 70, 0, true, 1000), clearCollision(1000), null, 1000);
        sequence.accept(true, true, 0, 70, 0.5, 0, 0,
                gliding(0, 70, 0.5, true, 1050), clearCollision(1050), null, 1050);

        var ordinary = ElytraMotionPredictor.next(
                new MotionPredictor.Motion(0, 0, 0.5), 0, 0, 0.08);
        var boosted = ElytraMotionPredictor.fireworkBoost(ordinary, 0, 0);
        var sample = sequence.accept(true, true, boosted.dx(), 70 + boosted.dy(),
                0.5 + boosted.dz(), 0, 0,
                gliding(boosted.dx(), 70 + boosted.dy(), 0.5 + boosted.dz(), true, 1100),
                clearCollision(1100), null, 1100);

        assertTrue(sample.evaluated());
        assertEquals(0, sample.offset(), 1.0e-9);
    }

    @Test void extendedFireworkMultiplierLeavesPredictionResidual() {
        var sequence = new ElytraMotionSequence();
        sequence.accept(true, true, 0, 70, 0, 0, 0,
                gliding(0, 70, 0, true, 1000), clearCollision(1000), null, 1000);
        sequence.accept(true, true, 0, 70, 0.5, 0, 0,
                gliding(0, 70, 0.5, true, 1050), clearCollision(1050), null, 1050);

        var ordinary = ElytraMotionPredictor.next(
                new MotionPredictor.Motion(0, 0, 0.5), 0, 0, 0.08);
        // LiquidBounce ExtendedFirework Normal uses 1.65 instead of vanilla 1.5:
        // v += look*0.1 + (look*1.65-v)*0.5.
        var extended = new MotionPredictor.Motion(
                ordinary.dx() * 0.5,
                ordinary.dy() * 0.5,
                ordinary.dz() + 0.1 + (1.65 - ordinary.dz()) * 0.5);
        var sample = sequence.accept(true, true, extended.dx(), 70 + extended.dy(),
                0.5 + extended.dz(), 0, 0,
                gliding(extended.dx(), 70 + extended.dy(), 0.5 + extended.dz(), true, 1100),
                clearCollision(1100), null, 1100);

        assertTrue(sample.evaluated());
        assertTrue(sample.horizontalOffset() > 0.06,
                "the 1.65 firework target speed must not fit any vanilla boost ordering");
    }

    @Test void vanillaElytraMovementMatchesPacketSequence() {
        var sequence = new ElytraMotionSequence();
        sequence.accept(true, true, 0, 70, 0, 0, 0,
                gliding(0, 70, 0, 1000), clearCollision(1000), null, 1000);
        sequence.accept(true, true, 0, 70, 0.5, 0, 0,
                gliding(0, 70, 0.5, 1050), clearCollision(1050), null, 1050);

        var expected = ElytraMotionPredictor.next(new MotionPredictor.Motion(0, 0, 0.5),
                0, 0, 0.08);
        var sample = sequence.accept(true, true, expected.dx(), 70 + expected.dy(),
                0.5 + expected.dz(), 0, 0,
                gliding(expected.dx(), 70 + expected.dy(), 0.5 + expected.dz(), 1100),
                clearCollision(1100), null, 1100);

        assertTrue(sample.evaluated());
        assertEquals(0, sample.offset(), 1.0e-9);
    }

    @Test void extraElytraSpeedControlCreatesRepeatedHorizontalPredictionResidual() {
        var sequence = primedSequence();
        var previousVelocity = new MotionPredictor.Motion(0, 0, 0.5);
        var boosted = ElytraMotionPredictor.next(
                new MotionPredictor.Motion(0, 0, 0.55), 0, 0, 0.08);
        var vanilla = ElytraMotionPredictor.next(previousVelocity, 0, 0, 0.08);
        double nextZ = 0.5 + boosted.dz();
        double nextY = 70 + boosted.dy();

        var sample = sequence.accept(true, true, boosted.dx(), nextY, nextZ, 0, 0,
                gliding(boosted.dx(), nextY, nextZ, 1100), clearCollision(1100), null, 1100);

        assertTrue(sample.horizontalOffset() > 0.04);
        assertTrue(Math.abs(boosted.dz() - vanilla.dz()) > 0.04);
    }

    @Test void extraElytraHeightControlCreatesVerticalResidual() {
        var sequence = primedSequence();
        var vanilla = ElytraMotionPredictor.next(new MotionPredictor.Motion(0, 0, 0.5),
                0, 0, 0.08);
        var raised = ElytraMotionPredictor.next(new MotionPredictor.Motion(0, 0.08, 0.5),
                0, 0, 0.08);
        double nextY = 70 + raised.dy();
        double nextZ = 0.5 + raised.dz();

        var sample = sequence.accept(true, true, raised.dx(), nextY, nextZ, 0, 0,
                gliding(raised.dx(), nextY, nextZ, 1100), clearCollision(1100), null, 1100);

        assertTrue(sample.verticalOffset() > 0.06);
        assertTrue(Math.abs(raised.dy() - vanilla.dy()) > 0.06);
    }

    private static ElytraMotionSequence primedSequence() {
        var sequence = new ElytraMotionSequence();
        sequence.accept(true, true, 0, 70, 0, 0, 0,
                gliding(0, 70, 0, 1000), clearCollision(1000), null, 1000);
        sequence.accept(true, true, 0, 70, 0.5, 0, 0,
                gliding(0, 70, 0.5, 1050), clearCollision(1050), null, 1050);
        return sequence;
    }
}

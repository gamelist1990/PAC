package org.pexserver.pac.movement;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec2;
import org.pexserver.pac.packet.JavaInputCapture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientInputPhysicsTest {
    private static final MotionPredictor.Motion STILL = new MotionPredictor.Motion(0, 0, 0);
    private static final MotionPredictor.Motion ANY = new MotionPredictor.Motion(1, 0, 1);

    @Test void cardinalInputUsesClientPointNineEightScale() {
        var result = ground(1.0f, 1.0f, forward());
        assertEquals(0.1 * 0.98f, result.closest().dz(), 1e-8);
    }

    @Test void squareCorrectionChangesDiagonalSneakingAndCustomItemUse() {
        var diagonal = new MotionPredictor.Input(true, false, true, false,
                false, true, false);
        var result = ground(0.5f, 1.0f, diagonal);
        assertEquals(0.1 * 0.49f, result.closest().dx(), 1e-7);
        assertEquals(0.1 * 0.49f, result.closest().dz(), 1e-7);

        var defaultUse = ground(1.0f, 0.2f, forward());
        var customUse = ground(1.0f, 1.0f, forward());
        assertEquals(0.1 * 0.98f * 0.2f, defaultUse.closest().dz(), 1e-8);
        assertEquals(0.1 * 0.98f, customUse.closest().dz(), 1e-8);
    }

    @Test void leftAndRightKeysHaveOppositeWorldDirections() {
        var left = ground(1.0f, 1.0f,
                new MotionPredictor.Input(false, false, true, false,
                        false, false, false));
        var right = ground(1.0f, 1.0f,
                new MotionPredictor.Input(false, false, false, true,
                        false, false, false));
        assertTrue(left.closest().dx() > 0);
        assertTrue(right.closest().dx() < 0);
    }

    @Test void speedEnvelopeUsesTheActualItemAndSneakScales() {
        double normal = MotionPredictor.maximumGroundStepClient(STILL, 0.1,
                0.6f, 0.91f, 1.0f, 1.0f);
        double usingItem = MotionPredictor.maximumGroundStepClient(STILL, 0.1,
                0.6f, 0.91f, 1.0f, 0.2f);
        double sneaking = MotionPredictor.maximumGroundStepClient(STILL, 0.1,
                0.6f, 0.91f, 0.3f, 1.0f);
        assertEquals(0.1, normal, 1e-7);
        assertTrue(usingItem < 0.03);
        assertTrue(sneaking < 0.05);
        assertTrue(usingItem > 0.0196);
    }

    @Test void diagonalShortcutMatchesAllKeyboardInputMagnitudes() {
        float[] multipliers = {0.0f, 0.2f, 0.3f, 0.5f, 1.0f, 1.4f, 4.0f};
        for (float sneak : multipliers) {
            for (float item : multipliers) {
                double exhaustive = 0;
                for (int forward = -1; forward <= 1; forward++) {
                    for (int strafe = -1; strafe <= 1; strafe++) {
                        var axes = MotionPredictor.clientAxes(forward, strafe, sneak, item);
                        exhaustive = Math.max(exhaustive,
                                Math.min(1, Math.hypot(axes.forward(), axes.strafe())));
                    }
                }
                assertEquals(exhaustive, MotionPredictor.maximumClientInputLength(sneak, item),
                        0.0, "sneak=" + sneak + " item=" + item);
            }
        }
    }

    @Test void inputAxesMatchMojangVec2FloatOperations() {
        float[] speeds = {0.3f, 1.0f, 1.4f};
        float[] itemMultipliers = {0.2f, 1.0f, 1.3f};
        for (int forward = -1; forward <= 1; forward++) {
            for (int strafe = -1; strafe <= 1; strafe++) {
                for (float sneak : speeds) {
                    for (float item : itemMultipliers) {
                        Vec2 expected = mojangInput(forward, strafe, sneak, item);
                        MotionPredictor.ClientAxes actual = MotionPredictor.clientAxes(
                                forward, strafe, sneak, item);
                        assertEquals(expected.y, actual.forward(), 0.0f);
                        assertEquals(expected.x, actual.strafe(), 0.0f);
                    }
                }
            }
        }
    }

    @Test void unobservedClientPredictionMatchesTheBestExactInputCandidate() {
        MotionPredictor.Motion previous = new MotionPredictor.Motion(0.23, 0, -0.17);
        MotionPredictor.Motion actual = new MotionPredictor.Motion(-1.7, 0, 2.4);
        for (float yaw : new float[]{0, 37, 90, 213}) {
            for (float sneak : new float[]{0.3f, 1.0f}) {
                for (float item : new float[]{0.2f, 1.0f}) {
                    double expected = Double.POSITIVE_INFINITY;
                    for (MotionPredictor.Input input : MultiStepMotionPredictor.inputCandidates(null)) {
                        expected = Math.min(expected,
                                MotionPredictor.predictGroundInputClient(previous, actual, yaw,
                                        0.1, 0.6f, 0.91f, sneak, item, input).offset());
                    }
                    double predicted = MotionPredictor.predictGroundClient(previous, actual, yaw,
                            0.1, 0.6f, 0.91f, sneak, item).offset();
                    assertEquals(expected, predicted, 1.0e-12);
                }
            }
        }
    }

    @Test void unobservedClientPredictionStopsAtAnEffectivelyExactCandidate() {
        MotionPredictor.Motion previous = new MotionPredictor.Motion(0.23, 0, -0.17);
        MotionPredictor.Input forward = new MotionPredictor.Input(true, false, false, false,
                false, false, false);
        MotionPredictor.Motion actual = MotionPredictor.predictGroundInputClient(previous,
                STILL, 37, 0.1, 0.6f, 0.91f, 1.0f, 1.0f, forward).closest();

        var predicted = MotionPredictor.predictGroundClient(previous, actual, 37,
                0.1, 0.6f, 0.91f, 1.0f, 1.0f);
        assertTrue(predicted.offset() <= MotionPredictor.EARLY_EXIT_OFFSET);
    }

    @Test void unobservedCandidateOrderingStillMatchesAllNineVanillaInputs() {
        MotionPredictor.Motion previous = new MotionPredictor.Motion(0.23, 0, -0.17);
        for (float yaw : new float[]{0, 37, 90, 213}) {
            for (MotionPredictor.Input input : MultiStepMotionPredictor.inputCandidates(null)) {
                MotionPredictor.Motion actual = MotionPredictor.predictGroundInputClient(previous,
                        STILL, yaw, 0.1, 0.6f, 0.91f, 0.3f, 0.2f, input).closest();
                var predicted = MotionPredictor.predictGroundClient(previous, actual, yaw,
                        0.1, 0.6f, 0.91f, 0.3f, 0.2f);
                assertEquals(0, predicted.offset(), 1.0e-5);
                assertEquals(actual.dx(), predicted.closest().dx(), 1.0e-5);
                assertEquals(actual.dz(), predicted.closest().dz(), 1.0e-5);
            }
        }
    }

    @Test void missingInputUsesSharedImmutableCandidatesForAllNineDirections() {
        var first = MultiStepMotionPredictor.frame(0, null, null);
        var second = MultiStepMotionPredictor.frame(90, null, null);

        assertSame(first.inputs(), second.inputs());
        assertSame(first.inputs(), MultiStepMotionPredictor.inputCandidates(null));
        assertEquals(9, first.inputs().size());
        assertEquals(new MotionPredictor.Input(false, true, false, true,
                false, false, false), first.inputs().getFirst());
        assertEquals(new MotionPredictor.Input(true, false, true, false,
                false, false, false), first.inputs().getLast());
        assertTrue(first.jumpPossible());
    }

    @Test void observedSingleInputUsesSharedCandidateListAcrossFrames() {
        var input = new MotionPredictor.Input(true, false, false, true,
                true, false, true);
        var window = new JavaInputCapture.Window(input, null);
        var first = MultiStepMotionPredictor.frame(0, window, null);
        var second = MultiStepMotionPredictor.frame(90, window, null);

        assertSame(first.inputs(), second.inputs());
        assertSame(first.inputs(), MultiStepMotionPredictor.inputCandidates(window));
        assertEquals(1, first.inputs().size());
        assertEquals(input, first.inputs().getFirst());
        assertTrue(first.jumpPossible());
    }

    private Vec2 mojangInput(int forward, int strafe, float sneak, float itemMultiplier) {
        Vec2 input = new Vec2(strafe, forward).normalized();
        if (input.lengthSquared() == 0) return input;
        input = input.scale(0.98f).scale(itemMultiplier).scale(sneak);
        float length = input.length();
        if (length <= 0) return input;
        Vec2 unit = input.scale(1.0f / length);
        float absX = Math.abs(unit.x);
        float absY = Math.abs(unit.y);
        float ratio = absY > absX ? absX / absY : absY / absX;
        float distance = Mth.sqrt(1.0f + Mth.square(ratio));
        float magnitude = Math.min(length * distance, 1.0f);
        return unit.scale(magnitude);
    }

    private MotionPredictor.Result ground(float sneak, float use,
                                          MotionPredictor.Input input) {
        return MotionPredictor.predictGroundInputClient(STILL, ANY, 0,
                0.1, 0.6f, 0.91f, sneak, use, input);
    }

    private MotionPredictor.Input forward() {
        return new MotionPredictor.Input(true, false, false, false,
                false, false, false);
    }
}

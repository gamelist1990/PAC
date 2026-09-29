package org.pexserver.pac;

import net.minecraft.util.Mth;
import org.junit.jupiter.api.Test;
import org.pexserver.pac.movement.MotionPredictor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MotionPredictorTest {
    @Test void choosesLegalForwardCandidate() {
        var previous = new MotionPredictor.Motion(0, 0, 0);
        var expected = MotionPredictor.predict(previous, new MotionPredictor.Motion(0, 0, 0.13), 0, false, false).closest();
        var result = MotionPredictor.predict(previous, expected, 0, false, false);
        assertEquals(0, result.offset(), 1e-12);
    }

    @Test void ordinaryGroundUsesMovementSpeedWithoutFrictionBoost() {
        var still = new MotionPredictor.Motion(0, 0, 0);
        var first = MotionPredictor.predict(still, new MotionPredictor.Motion(0, 0, 1),
                0, false, false).closest();
        var second = MotionPredictor.predict(first, new MotionPredictor.Motion(0, 0, 1),
                0, false, false).closest();
        assertEquals(0.1, first.dz(), 1e-12);
        assertEquals(0.1 + 0.1 * (double) (0.6f * 0.91f),
                second.dz(), 1e-12);
    }

    @Test void sneakingDiagonalInputIsScaledBeforeNormalization() {
        var result = MotionPredictor.predict(new MotionPredictor.Motion(0, 0, 0),
                new MotionPredictor.Motion(0.03, 0, 0.03), 0, false, true);
        assertEquals(0, result.offset(), 1e-12);
    }

    @Test void effectiveMovementAttributeChangesLegalAcceleration() {
        var result = MotionPredictor.predict(new MotionPredictor.Motion(0, 0, 0),
                new MotionPredictor.Motion(0, 0, 0.2), 0, 0.2, false);
        assertEquals(0, result.offset(), 1e-12);
    }

    @Test void airborneCandidateUsesAirControlAndDrag() {
        var previous = new MotionPredictor.Motion(0.02, 0, 0);
        var actual = new MotionPredictor.Motion(0.02 * 0.91f + 0.02f, 0, 0);
        assertEquals(0, MotionPredictor.predictAir(previous, actual, 0, false, false).offset(), 1e-12);
    }

    @Test void sprintUsesVanillaPlayerAirAccelerationAndClientInputScale() {
        var previous = new MotionPredictor.Motion(0, 0, 0);
        var forward = new MotionPredictor.Input(true, false, false, false, false, false, false);
        double walkingExpected = (double) 0.02f * (double) 0.98f;
        double sprintingExpected = (double) 0.026f * (double) 0.98f;

        var walking = MotionPredictor.predictAirInputClient(previous,
                new MotionPredictor.Motion(0, 0, walkingExpected), 0,
                false, 0.91f, 1, 1, forward);
        var sprinting = MotionPredictor.predictAirInputClient(previous,
                new MotionPredictor.Motion(0, 0, sprintingExpected), 0,
                true, 0.91f, 1, 1, forward);

        assertEquals(0, walking.offset(), 1e-12);
        assertEquals(0, sprinting.offset(), 1e-12);
        assertEquals(walkingExpected, walking.closest().dz(), 1e-12);
        assertEquals(sprintingExpected, sprinting.closest().dz(), 1e-12);
        assertTrue(sprinting.closest().dz() > walking.closest().dz());
        assertEquals((double) 0.02f,
                MotionPredictor.maximumAirTravelClient(previous, false, 0.91f, 1, 1, 1), 1e-8);
        assertEquals((double) 0.026f,
                MotionPredictor.maximumAirTravelClient(previous, true, 0.91f, 1, 1, 1), 1e-8);
    }

    @Test void clientAirMovementUsesMojangYawLookupAndFloatAngle() {
        float yaw = 37.25f;
        float radians = yaw * 0.017453292f;
        double scaledForward = (double) 0.98f * (double) 0.02f;
        var actual = new MotionPredictor.Motion(
                -scaledForward * Mth.sin((double) radians), 0,
                scaledForward * Mth.cos((double) radians));
        var forward = new MotionPredictor.Input(true, false, false, false, false, false, false);

        var result = MotionPredictor.predictAirInputClient(new MotionPredictor.Motion(0, 0, 0),
                actual, yaw, false, 0.91f, 1, 1, forward);

        assertEquals(0, result.offset(), 1e-12);
    }

    @Test void unexplainedHorizontalDisplacementHasLargeOffset() {
        var result = MotionPredictor.predict(new MotionPredictor.Motion(0, 0, 0),
                new MotionPredictor.Motion(1.0, 0, 0), 0, false, false);
        assertTrue(result.offset() > 0.35);
    }

    @Test void sprintCandidateCoversMoreDistanceThanSneakCandidate() {
        var previous = new MotionPredictor.Motion(0, 0, 0);
        var sprint = MotionPredictor.predict(previous, new MotionPredictor.Motion(0, 0, 1), 0, true, false).closest();
        var sneak = MotionPredictor.predict(previous, new MotionPredictor.Motion(0, 0, 1), 0, false, true).closest();
        assertTrue(sprint.dz() > sneak.dz());
    }

    @Test void doubledGroundSpeedExceedsDefaultOffsetThreshold() {
        var previous = new MotionPredictor.Motion(0, 0, 0);
        var legal = MotionPredictor.predict(previous, new MotionPredictor.Motion(0, 0, 1),
                0, false, false).closest();
        var doubled = new MotionPredictor.Motion(legal.dx() * 2, 0, legal.dz() * 2);
        var result = MotionPredictor.predict(previous, doubled, 0, false, false);
        assertTrue(result.offset() > 0.04);
    }

    @Test void iceUsesItsOwnFrictionForAccelerationAndCarry() {
        float ice = 0.98f;
        var forward = new MotionPredictor.Input(true, false, false, false, false, false, false);
        float acceleration = 0.1f * (0.21600002f / (ice * ice * ice));
        var first = MotionPredictor.predictGroundInput(new MotionPredictor.Motion(0, 0, 0),
                new MotionPredictor.Motion(0, 0, acceleration), 0, 0.1, false, false, ice, forward);
        assertEquals(0, first.offset(), 1e-12);
        double secondStep = (double) acceleration * (double) (ice * 0.91f) + (double) acceleration;
        var second = MotionPredictor.predictGroundInput(new MotionPredictor.Motion(0, 0, acceleration),
                new MotionPredictor.Motion(0, 0, secondStep),
                0, 0.1, false, false, ice, forward);
        assertEquals(0, second.offset(), 1e-12);
    }

    @Test void changedAirDragChangesHorizontalCarry() {
        var forward = new MotionPredictor.Input(true, false, false, false, false, false, false);
        var previous = new MotionPredictor.Motion(0, 0, 0.2);
        float drag = 0.955f;
        double expected = 0.2 * drag + 0.02f;
        var result = MotionPredictor.predictAirInput(previous,
                new MotionPredictor.Motion(0, 0, expected), 0,
                false, false, false, drag, forward);
        assertEquals(0, result.offset(), 1e-12);
    }

    @Test void usingItemRequiresSlowedInput() {
        var still = new MotionPredictor.Motion(0, 0, 0);
        var legal = new MotionPredictor.Motion(0, 0, 0.02);
        assertEquals(0, MotionPredictor.predict(still, legal, 0, 0.1, false, true).offset(), 1e-12);
        var noSlowdown = new MotionPredictor.Motion(0, 0, 0.1);
        assertTrue(MotionPredictor.predict(still, noSlowdown, 0, 0.1, false, true).offset() > 0.04);
    }
    @Test void terrainVelocityMultiplierReducesCarryButNotCurrentInputAcceleration() {
        var forward = new MotionPredictor.Input(true, false, false, false, false, false, false);
        var previous = new MotionPredictor.Motion(0, 0, 0.4);
        float terrainMultiplier = 0.4f;
        double expected = 0.4 * (double) (0.6f * 0.91f * terrainMultiplier) + 0.1;

        var vanillaTerrain = MotionPredictor.predictGroundInput(previous,
                new MotionPredictor.Motion(0, 0, expected), 0, 0.1,
                false, false, 0.6f, 0.91f, terrainMultiplier, forward);
        assertEquals(0, vanillaTerrain.offset(), 1e-12);

        double bypass = 0.4 * (double) (0.6f * 0.91f) + 0.1;
        var noSlow = MotionPredictor.predictGroundInput(previous,
                new MotionPredictor.Motion(0, 0, bypass), 0, 0.1,
                false, false, 0.6f, 0.91f, terrainMultiplier, forward);
        assertTrue(noSlow.offset() > 0.1,
                "ignoring soul-sand/honey carry slowdown must leave a large residual");
    }


}

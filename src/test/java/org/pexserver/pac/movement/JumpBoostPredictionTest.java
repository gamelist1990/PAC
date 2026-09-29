package org.pexserver.pac.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JumpBoostPredictionTest {
    @Test void effectLevelChangesTheAcceptedTakeoffVelocity() {
        float levelOne = MotionEnvironment.effectiveJumpStrength(0.42, 0);
        float levelThree = MotionEnvironment.effectiveJumpStrength(0.42, 2);
        assertEquals(0.52f, levelOne, 1e-6);
        assertEquals(0.72f, levelThree, 1e-6);

        var sequence = new GroundMotionSequence();
        var ground = new MotionEnvironment.Snapshot(true, false, false, false, false,
                0, 0.1, 0, 64, 0, 1, 1_000, false, false,
                0.6f, 0.08, 0.91f, 0.98f, levelThree);
        sequence.accept(true, true, 0, 64, 0, 0, ground, 1_000);
        assertFalse(sequence.accept(true, false, 0, 64 + levelThree, 0.2, 0,
                ground, 1_050).impossibleTakeoff());

        var second = new GroundMotionSequence();
        second.accept(true, true, 0, 64, 0, 0, ground, 1_000);
        assertTrue(second.accept(true, false, 0, 64.1, 0.2, 0,
                ground, 1_050).impossibleTakeoff());
    }

    @Test void levitationIsNotJudgedAsAnOrdinaryJump() {
        var sequence = new GroundMotionSequence();
        var grounded = new MotionEnvironment.Snapshot(true, false, false, false, false,
                0, 0.1, 0, 64, 0, 1, 1_000, false, false,
                0.6f, 0.08, 0.91f, 0.98f, 0.42f, false, 4);
        sequence.accept(true, true, 0, 64, 0, 0, grounded, 1_000);
        assertFalse(sequence.accept(true, false, 0, 64.05, 0, 0,
                grounded, 1_050).impossibleTakeoff());
    }
    @Test void terrainJumpFactorScalesBaseJumpBeforeJumpBoost() {
        assertEquals(0.252f,
                MotionEnvironment.effectiveJumpStrength(0.42, -1, 0.6f), 1e-6);
        assertEquals(0.352f,
                MotionEnvironment.effectiveJumpStrength(0.42, 0, 0.6f), 1e-6);
    }


}

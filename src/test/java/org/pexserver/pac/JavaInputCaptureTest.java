package org.pexserver.pac;

import org.junit.jupiter.api.Test;
import org.pexserver.pac.movement.MotionPredictor;
import org.pexserver.pac.packet.JavaInputCapture;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class JavaInputCaptureTest {
    @Test void previousInputOnlyAllowedForTwoMovementFrames() {
        var capture = new JavaInputCapture();
        UUID uuid = UUID.randomUUID();
        var forward = new MotionPredictor.Input(true, false, false, false, false, false, false);
        var right = new MotionPredictor.Input(false, false, false, true, false, false, false);
        capture.update(uuid, forward);
        assertNull(capture.nextMovement(uuid).previous());
        capture.update(uuid, right);
        assertEquals(forward, capture.nextMovement(uuid).previous());
        assertEquals(forward, capture.nextMovement(uuid).previous());
        var cancelled = capture.nextMovement(uuid);
        assertEquals(forward, capture.nextMovement(uuid).previous(), "rejected position must not consume the allowance");
        var accepted = capture.nextMovement(uuid);
        capture.acceptedPosition(uuid, accepted);
        assertEquals(forward, capture.nextMovement(uuid).previous());
        capture.acceptedPosition(uuid, capture.nextMovement(uuid));
        assertNull(capture.nextMovement(uuid).previous());
        capture.acceptedPosition(uuid, cancelled);
        capture.forget(uuid);
        assertNull(capture.nextMovement(uuid));
    }

    @Test void oldPositionCannotConsumeANewInputTransition() {
        var capture = new JavaInputCapture();
        UUID uuid = UUID.randomUUID();
        var forward = new MotionPredictor.Input(true, false, false, false, false, false, false);
        var right = new MotionPredictor.Input(false, false, false, true, false, false, false);
        var left = new MotionPredictor.Input(false, false, true, false, false, false, false);
        capture.update(uuid, forward);
        capture.update(uuid, right);
        var oldPacket = capture.nextMovement(uuid);
        capture.update(uuid, left);
        capture.acceptedPosition(uuid, oldPacket);
        capture.acceptedPosition(uuid, capture.nextMovement(uuid));
        assertEquals(right, capture.nextMovement(uuid).previous());
    }

    @Test void exactInputRejectsMovementInAnotherDirection() {
        var still = new MotionPredictor.Motion(0, 0, 0);
        var sideways = new MotionPredictor.Motion(0.1, 0, 0);
        var forward = new MotionPredictor.Input(true, false, false, false, false, false, false);
        assertEquals(0, MotionPredictor.predict(still, sideways, 0, 0.1, false).offset(), 1e-12);
        var result = MotionPredictor.predictInput(still, sideways, 0, 0.1, false, false, forward);
        org.junit.jupiter.api.Assertions.assertTrue(result.offset() > 0.1);
    }
}

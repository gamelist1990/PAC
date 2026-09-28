package org.pexserver.pac.movement;

import org.pexserver.pac.packet.ExternalMotionTracker;

/** Re-bases the predictor after server-authoritative motion sent by the server. */
final class ExternalMotionWindow {
    private long seenSequence;
    private int settlingFrames;

    void reset() {
        seenSequence = 0;
        settlingFrames = 0;
    }

    boolean rebase(ExternalMotionTracker.Impulse latest) {
        if (latest != null && latest.sequence() != seenSequence) {
            seenSequence = latest.sequence();
            // The server packet is authoritative, but its first client physics step
            // can include input, friction, and collision. Accept that step as the new
            // velocity baseline, then resume the ordinary recurrence immediately.
            settlingFrames = 1;
        }
        if (settlingFrames == 0) return false;
        settlingFrames--;
        return true;
    }

    MotionPredictor.Motion seed(MotionPredictor.Motion previous,
                                ExternalMotionTracker.Impulse impulse) {
        if (impulse == null) return previous;
        if (!impulse.additive()) return new MotionPredictor.Motion(impulse.x(), impulse.y(), impulse.z());
        return new MotionPredictor.Motion(previous.dx() + impulse.x(),
                previous.dy() + impulse.y(), previous.dz() + impulse.z());
    }
}

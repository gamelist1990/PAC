package org.pexserver.pac.movement;

/** Bounded uncertainty in packet arrival time, not extra client physics ticks. */
public final class MovementLatencyWindow {
    private long lastArrival = -1;
    private long recoveryUntil;
    private long nextRecoveryAt;

    public synchronized boolean uncertain(long now) {
        // Do not suppress local movement prediction while an impulse is in
        // flight. The movement packets arrive in client order and the motion
        // sequence consumes the server vector as its initial velocity. Holding
        // checks for an RTT after every velocity packet creates a blind window
        // and throws away exactly the samples needed to predict plugin motion.
        if (lastArrival >= 0 && now - lastArrival >= 100 && now >= nextRecoveryAt) {
            recoveryUntil = now + 150;
            // Burst packets cannot slide the deadline indefinitely. Repeated
            // manufactured gaps also cannot keep prediction permanently off.
            nextRecoveryAt = now + 2_000;
        }
        lastArrival = now;
        return now < recoveryUntil;
    }
}

package org.pexserver.pac.movement;

import org.pexserver.pac.packet.ExternalMotionTracker;

/** Bounded uncertainty in packet arrival time, not extra client physics ticks. */
public final class MovementLatencyWindow {
    private long lastArrival = -1;
    private long recoveryUntil;
    private long impulseUntil;
    private long impulseSequence;
    private long nextRecoveryAt;

    public synchronized boolean uncertain(long now, int pingMillis,
                                           ExternalMotionTracker.Impulse impulse) {
        if (impulse != null && impulse.sequence() != impulseSequence) {
            impulseSequence = impulse.sequence();
            // A response travels server -> client -> server. Half the RTT is
            // insufficient; retain two ticks for scheduling and modest jitter.
            impulseUntil = Math.max(impulseUntil,
                    impulse.sentAt() + Math.max(100L, Math.min(1_500L, (long) pingMillis + 100L)));
        }
        if (lastArrival >= 0 && now - lastArrival >= 100 && now >= nextRecoveryAt) {
            recoveryUntil = now + 150;
            // Burst packets cannot slide the deadline indefinitely. Repeated
            // manufactured gaps also cannot keep prediction permanently off.
            nextRecoveryAt = now + 2_000;
        }
        lastArrival = now;
        return now < impulseUntil || now < recoveryUntil;
    }
}

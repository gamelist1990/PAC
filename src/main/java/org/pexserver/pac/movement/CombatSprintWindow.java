package org.pexserver.pac.movement;

/** Tracks the first client movement step after an attack packet. */
public final class CombatSprintWindow {
    private long attackUntil;
    private boolean pendingMovement;

    public void attack(long now) {
        attackUntil = now + 400;
        pendingMovement = true;
    }

    public boolean active(long now) {
        return pendingMovement && now >= 0 && now < attackUntil;
    }

    public void position() {
        pendingMovement = false;
    }
}

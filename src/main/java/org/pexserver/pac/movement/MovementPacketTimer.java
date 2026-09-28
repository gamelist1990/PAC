package org.pexserver.pac.movement;

/** Bounds client physics steps against elapsed wall time while allowing lag bursts. */
public final class MovementPacketTimer {
    private static final long STEP_NANOS = 50_000_000L;
    private static final long BURST_BUDGET_NANOS = 1_500_000_000L;
    private long lastAt = Long.MIN_VALUE;
    private long ahead;
    private int excessivePackets;
    private boolean rejecting;
    private long previousServerAllowance;

    public boolean accept(long now) { return accept(now, 0); }

    public boolean accept(long now, long serverDelayMillis) {
        long serverAllowance = Math.max(0, Math.min(15_000, serverDelayMillis)) * 1_000_000L;
        // The server-owned backlog must not become a violation when its
        // allowance expires: a normal 20 Hz stream never repays that lead.
        if (serverAllowance < previousServerAllowance)
            ahead = Math.max(0, ahead - (previousServerAllowance - serverAllowance));
        previousServerAllowance = serverAllowance;
        long burstBudget = BURST_BUDGET_NANOS + serverAllowance;
        if (lastAt == Long.MIN_VALUE || now < lastAt || now - lastAt > 5_000_000_000L) {
            lastAt = now;
            ahead = STEP_NANOS;
            excessivePackets = 0;
            rejecting = false;
            return false;
        }
        long elapsed = now - lastAt;
        lastAt = now;

        // Once a burst is confirmed, canceled packets do not add another
        // simulated step. Their elapsed wall time instead pays back the
        // accumulated lead, so a fast client cannot keep moving between the
        // occasional timer flags.
        long paidAhead = Math.max(0, ahead - elapsed);
        if (rejecting) {
            ahead = paidAhead;
            if (ahead == 0) {
                rejecting = false;
                excessivePackets = 0;
            }
            return true;
        }

        long proposedAhead = paidAhead + STEP_NANOS;
        if (proposedAhead <= burstBudget) {
            ahead = proposedAhead;
            excessivePackets = 0;
            return false;
        }
        ahead = proposedAhead;
        if (++excessivePackets < 6) return false;

        // The six-step confirmation grace tolerates minor packet jitter. The
        // confirming packet is canceled, so only already accepted steps stay
        // on the backlog and must be paid back before movement resumes.
        ahead = paidAhead;
        rejecting = true;
        return true;
    }

    public long aheadMillis() { return ahead / 1_000_000L; }
}

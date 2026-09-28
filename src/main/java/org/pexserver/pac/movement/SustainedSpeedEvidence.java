package org.pexserver.pac.movement;

/** Accumulates distance beyond the maximum legal ground step across stable frames. */
public final class SustainedSpeedEvidence {
    private static final double ROUNDING_ALLOWANCE = 0.003;
    // Let severe per-tick excess (such as Wurst's 1.8x/0.66 cap) cross the
    // threshold immediately. Mild repeated excess still accumulates over time.
    private static final double FRAME_CAP = 0.12;
    private static final double LIMIT = 0.12;
    private double excess;

    public boolean accept(double stepExcess) {
        if (!Double.isFinite(stepExcess) || stepExcess < 0) {
            reset();
            return false;
        }
        excess = Math.max(0, excess - ROUNDING_ALLOWANCE)
                + Math.min(FRAME_CAP, Math.max(0, stepExcess - ROUNDING_ALLOWANCE));
        if (excess < LIMIT) return false;
        excess = LIMIT / 2;
        return true;
    }

    public void reset() { excess = 0; }
}

package org.pexserver.pac.movement;

/** Server-owned monotonic tick clock; packet threads read immutable timing estimates. */
public final class ServerTickTiming {
    public record Snapshot(double millisPerTick, long tickAgeMillis, boolean recovering,
                           long timerAllowanceMillis) {
        public static final Snapshot NORMAL = new Snapshot(50, 0, false, 0);
        public boolean delayed() { return millisPerTick >= 55 || tickAgeMillis >= 75; }
        public double tps() { return Math.min(20, 1_000 / millisPerTick); }
        public int physicsFrames(long elapsedMillis, int positionlessPackets) {
            // TCP preserves individual movement packets. A slow server does not
            // turn each queued packet into elapsedMillis / 50 client steps.
            return delayed() ? (int) Math.max(1, Math.min(40, (long) positionlessPackets + 1))
                    : MultiStepMotionPredictor.estimateFrames(elapsedMillis, positionlessPackets);
        }
    }

    private long lastTick = Long.MIN_VALUE;
    private double millisPerTick = 50;
    private long recoveryUntil;
    private long allowanceUntil;
    private long timerAllowanceMillis;

    public synchronized void tick(long nowNanos) {
        if (lastTick != Long.MIN_VALUE && nowNanos >= lastTick) {
            long elapsed = (nowNanos - lastTick) / 1_000_000;
            // Fast attack, gradual recovery; exclude catch-up ticks from the
            // physical speed model and bound the moving estimate after a stall.
            millisPerTick += (Math.max(50, Math.min(1_000, elapsed)) - millisPerTick)
                    * (elapsed > millisPerTick ? .5 : .1);
            if (elapsed >= 250) {
                recoveryUntil = nowNanos + Math.min(1_500, elapsed) * 1_000_000L;
                timerAllowanceMillis = Math.min(15_000, Math.max(timerAllowanceMillis, elapsed));
                allowanceUntil = nowNanos + (timerAllowanceMillis + 1_500) * 1_000_000L;
            }
        }
        lastTick = nowNanos;
    }

    public synchronized Snapshot snapshot(long nowNanos) {
        if (lastTick == Long.MIN_VALUE) return Snapshot.NORMAL;
        long age = Math.max(0, (nowNanos - lastTick) / 1_000_000);
        long allowance = nowNanos < allowanceUntil ? timerAllowanceMillis : 0;
        if (allowance == 0) timerAllowanceMillis = 0;
        return new Snapshot(millisPerTick, age, age >= 250 || nowNanos < recoveryUntil,
                Math.max(allowance, age >= 250 ? Math.min(15_000, age) : 0));
    }
}

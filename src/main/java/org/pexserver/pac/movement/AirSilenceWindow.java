package org.pexserver.pac.movement;

/** Time window for a player in clear air who sends no movement packets. */
public final class AirSilenceWindow {
    private long lastPacketAt, firstAirAt, lastFlagAt;

    public void packet(long now) {
        lastPacketAt = now;
        firstAirAt = 0;
    }

    public boolean sample(boolean ordinaryAir, long now) {
        if (!ordinaryAir) {
            firstAirAt = 0;
            return false;
        }
        if (firstAirAt == 0) firstAirAt = now;
        if (silenceMillis(now) < 3000 || now - firstAirAt < 3000
                || (lastFlagAt != 0 && now - lastFlagAt < 5000)) return false;
        lastFlagAt = now;
        return true;
    }

    public long silenceMillis(long now) {
        return now - (lastPacketAt == 0 ? firstAirAt : lastPacketAt);
    }
}

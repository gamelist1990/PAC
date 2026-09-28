package org.pexserver.pac.movement;

/** Packet-order teleport acknowledgment state for one Java player. */
final class TeleportSyncWindow {
    private static final long SETTLE_MILLIS = 200;
    private static final long MAX_SUPPRESSION_MILLIS = 5_000;
    private int pendingId;
    private long pendingSince;
    private long confirmedAt;
    private boolean pending;

    synchronized void sent(int id, long now) {
        // Repeated corrections cannot extend one continuous prediction grace period.
        // A completed acknowledgement may start a fresh window even if no
        // movement packet arrived to call suppressed() and retire the old one.
        if (!pending || confirmedAt != 0 && now >= confirmedAt
                && now - confirmedAt >= SETTLE_MILLIS) pendingSince = now;
        pendingId = id;
        confirmedAt = 0;
        pending = true;
    }

    synchronized boolean confirm(int id, long now) {
        if (!pending || pendingId != id || confirmedAt != 0) return false;
        confirmedAt = now;
        return true;
    }

    synchronized boolean suppressed(long now) {
        if (!pending) return false;
        if (now >= pendingSince && now - pendingSince >= MAX_SUPPRESSION_MILLIS) {
            // Keep an unacknowledged window pending so more correction packets
            // cannot restart the grace period. An acknowledged one may close.
            if (confirmedAt != 0) pending = false;
            return false;
        }
        if (confirmedAt == 0 || now - confirmedAt < SETTLE_MILLIS) return true;
        pending = false;
        return false;
    }

    synchronized boolean pending() { return pending; }
}

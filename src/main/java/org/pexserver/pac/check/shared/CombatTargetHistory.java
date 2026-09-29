package org.pexserver.pac.check.shared;

import org.bukkit.util.BoundingBox;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short server-side history for player combat boxes. Reach uses this to compensate
 * target motion instead of granting extra geometric reach from a reported ping value.
 */
final class CombatTargetHistory {
    private static final long RETENTION_MILLIS = 750;
    private static final int MAX_FRAMES = 24;

    record Frame(UUID world, BoundingBox box, long at) { }

    private final Map<UUID, ArrayDeque<Frame>> frames = new ConcurrentHashMap<>();

    void sample(UUID uuid, UUID world, BoundingBox box, long at) {
        if (uuid == null || world == null || box == null || at < 0) return;
        BoundingBox copy = new BoundingBox(box.getMinX(), box.getMinY(), box.getMinZ(),
                box.getMaxX(), box.getMaxY(), box.getMaxZ());
        ArrayDeque<Frame> history = frames.computeIfAbsent(uuid, ignored -> new ArrayDeque<>());
        synchronized (history) {
            history.addLast(new Frame(world, copy, at));
            long cutoff = at - RETENTION_MILLIS;
            while (!history.isEmpty()
                    && (history.size() > MAX_FRAMES || history.peekFirst().at() < cutoff))
                history.removeFirst();
        }
    }

    Frame atOrBefore(UUID uuid, UUID world, long targetAt) {
        ArrayDeque<Frame> history = frames.get(uuid);
        if (history == null) return null;
        synchronized (history) {
            Frame best = null;
            for (Frame frame : history) {
                if (!frame.world().equals(world)) continue;
                if (frame.at() <= targetAt) best = frame;
                else break;
            }
            if (best != null) return best;
            for (Frame frame : history)
                if (frame.world().equals(world)) return frame;
            return null;
        }
    }

    void forget(UUID uuid) {
        if (uuid != null) frames.remove(uuid);
    }

    static long trustedRewindMillis(int reportedPingMillis) {
        // Keepalive/ping RTT is client-influenceable and is therefore not a
        // trusted estimate of ATTACK packet age. Reach uses the server receive
        // timestamp and bounded tick history instead.
        return 0;
    }
}

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

    private final Map<UUID, ArrayDeque<Frame>> shapes = new ConcurrentHashMap<>();
    private final Map<UUID, ArrayDeque<Frame>> frames = new ConcurrentHashMap<>();

    void sample(UUID uuid, UUID world, BoundingBox box, long at) {
        if (uuid == null || world == null || box == null || at < 0) return;
        BoundingBox copy = new BoundingBox(box.getMinX(), box.getMinY(), box.getMinZ(),
                box.getMaxX(), box.getMaxY(), box.getMaxZ());
        ArrayDeque<Frame> dimensionHistory = shapes.computeIfAbsent(uuid, ignored -> new ArrayDeque<>());
        var relative = copy.clone().shift(-(copy.getMinX() + copy.getMaxX()) * 0.5,
                -copy.getMinY(), -(copy.getMinZ() + copy.getMaxZ()) * 0.5);
        synchronized (dimensionHistory) {
            var last = dimensionHistory.peekLast();
            if (last == null || !last.world().equals(world) || !sameBox(last.box(), relative)
                    || at - last.at() >= 50) dimensionHistory.addLast(new Frame(world, relative, at));
            while (dimensionHistory.size() > 128 || !dimensionHistory.isEmpty()
                    && dimensionHistory.peekFirst().at() < at - 5_500) dimensionHistory.removeFirst();
        }
        ArrayDeque<Frame> history = frames.computeIfAbsent(uuid, ignored -> new ArrayDeque<>());
        synchronized (history) {
            Frame last = history.peekLast();
            if (last != null && last.world().equals(world) && at >= last.at()
                    && at - last.at() < 25 && sameBox(last.box(), copy)) return;
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
        if (uuid != null) { frames.remove(uuid); shapes.remove(uuid); }
    }

    /** Strict geometry is only safe once the target has settled across client interpolation. */
    BoundingBox stableBox(UUID uuid, UUID world, BoundingBox current, long now) {
        ArrayDeque<Frame> history = frames.get(uuid);
        if (history == null) return null;
        synchronized (history) {
            boolean covered = false;
            long newest = -1;
            for (Frame frame : history) {
                if (frame.at() < now - 350 || frame.at() > now) continue;
                if (!frame.world().equals(world) || !sameBox(frame.box(), current)) return null;
                if (frame.at() <= now - 250) covered = true;
                newest = Math.max(newest, frame.at());
            }
            return covered && newest >= now - 100 ? current.clone() : null;
        }
    }

    /** Pose/scale changes use the envelope of recent dimensions, without adding travel as padding. */
    BoundingBox relativeShape(UUID uuid, UUID world, BoundingBox current, VectorOffset offset, long now) {
        BoundingBox shape = current.clone().shift(-offset.x, -offset.y, -offset.z);
        ArrayDeque<Frame> history = shapes.get(uuid);
        if (history == null) return shape;
        synchronized (history) {
            for (Frame frame : history) {
                if (!frame.world.equals(world) || frame.at < now - 5_500 || frame.at > now) continue;
                shape.union(frame.box);
            }
        }
        return shape;
    }
    record VectorOffset(double x, double y, double z) { }

    void prune(long now) {
        shapes.entrySet().removeIf(entry -> {
            synchronized (entry.getValue()) { return entry.getValue().isEmpty()
                    || entry.getValue().peekLast().at() < now - 5_500; }
        });
        frames.entrySet().removeIf(entry -> {
            synchronized (entry.getValue()) {
                return entry.getValue().isEmpty()
                        || entry.getValue().peekLast().at() < now - RETENTION_MILLIS;
            }
        });
    }

    private static boolean sameBox(BoundingBox a, BoundingBox b) {
        return Math.abs(a.getMinX() - b.getMinX()) < 0.001
                && Math.abs(a.getMinY() - b.getMinY()) < 0.001
                && Math.abs(a.getMinZ() - b.getMinZ()) < 0.001
                && Math.abs(a.getMaxX() - b.getMaxX()) < 0.001
                && Math.abs(a.getMaxY() - b.getMaxY()) < 0.001
                && Math.abs(a.getMaxZ() - b.getMaxZ()) < 0.001;
    }

    static long trustedRewindMillis(int reportedPingMillis) {
        // Keepalive/ping RTT is client-influenceable and is therefore not a
        // trusted estimate of ATTACK packet age. Reach uses the server receive
        // timestamp and bounded tick history instead.
        return 0;
    }
}

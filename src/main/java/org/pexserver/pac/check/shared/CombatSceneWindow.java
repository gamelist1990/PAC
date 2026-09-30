package org.pexserver.pac.check.shared;

import org.bukkit.util.BoundingBox;
import java.util.*;

/** Ordered outbound positions bounded by replies on the same client connection. */
public final class CombatSceneWindow {
    private static final int MAX_TARGETS = 1024, MAX_FRAMES = 160, MAX_BARRIERS = 128;
    private static final long INTERPOLATION_MILLIS = 250, ACK_TIMEOUT_MILLIS = 5_000;
    public record Position(double x, double y, double z) {
        boolean finite() { return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z); }
    }
    private record Frame(Position position, long sequence, long at, boolean discontinuity) { }
    private record Barrier(long sequence, long at) { }
    private record Settling(Barrier barrier, long eligibleFrame) { }
    private static final class Target {
        final UUID uuid;
        final ArrayDeque<Frame> frames = new ArrayDeque<>();
        long lostThrough, lostAt, teleportSequence;
        Target(UUID uuid) { this.uuid = uuid; }
    }
    public record Segment(Position from, Position to) { }
    public record View(boolean ready, boolean expired, List<Segment> segments) {
        public static View unknown() { return new View(false, false, List.of()); }
        List<BoundingBox[]> boxes(BoundingBox relativeShape) {
            return segments.stream().map(s -> new BoundingBox[] {
                relativeShape.clone().shift(s.from.x, s.from.y, s.from.z),
                relativeShape.clone().shift(s.to.x, s.to.y, s.to.z) }).toList();
        }
    }
    private final Map<Integer, Target> targets = new HashMap<>();
    private final LinkedHashMap<Integer, Barrier> barriers = new LinkedHashMap<>();
    private final ArrayDeque<Settling> settling = new ArrayDeque<>();
    private long clientFrames, pruneSequence, pruneSentAt;
    private long sequence, acknowledgedSequence, acknowledgedSentAt, nextBarrierAt;

    public synchronized void spawn(int id, UUID uuid, Position position, long now) {
        targets.remove(id);
        if (uuid == null || position == null || !position.finite() || targets.size() >= MAX_TARGETS) return;
        Target target = new Target(uuid);
        targets.put(id, target);
        append(target, position, now, true);
    }
    public synchronized UUID targetUuid(int id) {
        Target t = targets.get(id); return t == null ? null : t.uuid;
    }
    public synchronized Position position(int id) {
        Target t = targets.get(id);
        return t == null || t.frames.isEmpty() ? null : t.frames.peekLast().position;
    }
    public synchronized void move(int id, Position position, long now, boolean discontinuity) {
        Target target = targets.get(id);
        if (target == null || position == null || !position.finite()) return;
        append(target, position, now, discontinuity);
        if (discontinuity) target.teleportSequence = sequence;
    }
    private void append(Target target, Position position, long now, boolean discontinuity) {
        target.frames.addLast(new Frame(position, ++sequence, now, discontinuity));
        prune(target);
        while (target.frames.size() > MAX_FRAMES) {
            Frame lost = target.frames.removeFirst();
            target.lostThrough = lost.sequence;
            target.lostAt = Math.max(target.lostAt, lost.at);
        }
    }
    private void prune(Target target) {
        // Keep the position before the interpolation horizon and all unacknowledged updates.
        while (target.frames.size() > 1) {
            Iterator<Frame> it = target.frames.iterator();
            it.next();
            Frame second = it.next();
            boolean completedTeleport = target.teleportSequence > 0 && target.teleportSequence <= pruneSequence
                    && second.sequence <= target.teleportSequence;
            if (!completedTeleport && (second.sequence > pruneSequence
                    || second.at > pruneSentAt - INTERPOLATION_MILLIS)) break;
            target.frames.removeFirst();
        }
    }
    public synchronized boolean barrierDue(long now) {
        return sequence > acknowledgedSequence && now >= nextBarrierAt && barriers.size() < MAX_BARRIERS;
    }
    public synchronized void barrier(int id, long now) {
        barriers.put(id, new Barrier(sequence, now));
        nextBarrierAt = now + 50;
    }
    public synchronized boolean acknowledge(int id) {
        Barrier barrier = barriers.remove(id);
        if (barrier == null) return false;
        // TCP client replies must preserve challenge order. An old/replayed id cannot advance state.
        if (barrier.sequence <= acknowledgedSequence) return true;
        acknowledgedSequence = barrier.sequence;
        acknowledgedSentAt = barrier.at;
        barriers.entrySet().removeIf(e -> e.getValue().sequence <= acknowledgedSequence);
        settling.addLast(new Settling(barrier, clientFrames + 6));
        while (settling.size() > MAX_BARRIERS) settling.removeFirst();
        return true;
    }
    /** Reply confirms processing, not completed interpolation. Six subsequent
     * client physics packets delimit interpolation even after a queued network burst.
     */
    public synchronized void movement() {
        clientFrames++;
        while (!settling.isEmpty() && settling.peekFirst().eligibleFrame <= clientFrames) {
            Barrier barrier = settling.removeFirst().barrier;
            pruneSequence = barrier.sequence; pruneSentAt = barrier.at;
        }
        targets.values().forEach(this::prune);
    }
    public synchronized View view(int id, UUID uuid, long now) {
        Target target = targets.get(id);
        if (target == null || !target.uuid.equals(uuid)) return View.unknown();
        boolean expired = !barriers.isEmpty() && now - barriers.values().iterator().next().at > ACK_TIMEOUT_MILLIS;
        if (expired || target.frames.isEmpty() || acknowledgedSequence == 0
                || target.frames.peekFirst().sequence > acknowledgedSequence
                || target.teleportSequence > pruneSequence
                || target.lostThrough >= pruneSequence && target.lostThrough > 0
                || target.lostThrough > 0 && pruneSentAt - INTERPOLATION_MILLIS <= target.lostAt)
            return new View(false, expired, List.of());
        List<Segment> result = new ArrayList<>();
        Frame previous = null;
        for (Frame frame : target.frames) {
            if (previous == null || frame.discontinuity)
                result.add(new Segment(frame.position, frame.position));
            else result.add(new Segment(previous.position, frame.position));
            previous = frame;
        }
        return new View(true, false, List.copyOf(result));
    }
    public synchronized void destroy(int id) { targets.remove(id); }
    public synchronized void clear() {
        targets.clear(); barriers.clear(); settling.clear();
        sequence = acknowledgedSequence = acknowledgedSentAt = nextBarrierAt = 0;
        clientFrames = pruneSequence = pruneSentAt = 0;
    }
}

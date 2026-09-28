package org.pexserver.pac.movement;

import java.util.ArrayList;
import java.util.UUID;

/** Bounded, read-optimized history of nearby world changes during client latency. */
final class BlockChangeWindow {
    private static final int MAX_CHANGES = 32;
    private static final double PLAYER_HALF_WIDTH = 0.3;
    private static final double PLAYER_HEIGHT = 1.8;
    private static final double STEP_REACH = 0.6;

    private record Change(UUID world, int x, int y, int z, long expiresAt) { }

    // Main-thread block events publish a new immutable array; packet threads only read it.
    private volatile Change[] changes = new Change[0];

    synchronized void record(UUID world, int x, int y, int z, long now, long expiresAt) {
        if (world == null || expiresAt <= now) return;
        Change[] previous = changes;
        ArrayList<Change> next = new ArrayList<>(Math.min(MAX_CHANGES, previous.length + 1));
        for (Change change : previous) {
            if (change.expiresAt() > now
                    && !(change.world().equals(world) && change.x() == x
                    && change.y() == y && change.z() == z)) {
                if (next.size() == MAX_CHANGES) next.remove(0);
                next.add(change);
            }
        }
        if (next.size() == MAX_CHANGES) next.remove(0);
        next.add(new Change(world, x, y, z, expiresAt));
        changes = next.toArray(Change[]::new);
    }

    boolean affects(UUID world, double fromX, double fromY, double fromZ,
                    double toX, double toY, double toZ, long now) {
        if (world == null || !Double.isFinite(fromX) || !Double.isFinite(fromY)
                || !Double.isFinite(fromZ) || !Double.isFinite(toX)
                || !Double.isFinite(toY) || !Double.isFinite(toZ)) return false;
        double minX = Math.min(fromX, toX) - PLAYER_HALF_WIDTH;
        double maxX = Math.max(fromX, toX) + PLAYER_HALF_WIDTH;
        double minY = Math.min(fromY, toY) - STEP_REACH;
        double maxY = Math.max(fromY, toY) + PLAYER_HEIGHT;
        double minZ = Math.min(fromZ, toZ) - PLAYER_HALF_WIDTH;
        double maxZ = Math.max(fromZ, toZ) + PLAYER_HALF_WIDTH;

        for (Change change : changes) {
            if (change.expiresAt() <= now || !world.equals(change.world())) continue;
            if (change.x() + 1.0 > minX && change.x() < maxX
                    && change.y() + 1.0 > minY && change.y() < maxY
                    && change.z() + 1.0 > minZ && change.z() < maxZ) return true;
        }
        return false;
    }
}

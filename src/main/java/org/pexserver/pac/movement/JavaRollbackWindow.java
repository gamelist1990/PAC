package org.pexserver.pac.movement;

import java.util.UUID;

/** Creates a setback anchor from the immediately preceding accepted position. */
public final class JavaRollbackWindow {
    public record Position(double x, double y, double z) { }
    public record Anchor(Position position, long startedAtNanos,
                         long teleportGeneration, UUID worldId) { }

    public static Anchor select(Position requested, long teleportGeneration,
                                UUID worldId, long nowNanos) {
        return new Anchor(requested, nowNanos, teleportGeneration, worldId);
    }
}

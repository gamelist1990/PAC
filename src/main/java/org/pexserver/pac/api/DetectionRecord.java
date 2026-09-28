package org.pexserver.pac.api;

import java.util.UUID;
import java.util.Optional;

/** Immutable, database-backed PAC detection record. Times are Unix epoch milliseconds. */
public record DetectionRecord(long id, UUID playerId, String playerName, String detectorKey,
                              double score, String detail, long createdAt,
                              boolean falsePositive, boolean debugRecording,
                              String metricsJson) {
    /** Typed identifier when the stored key is part of the current PAC detector set. */
    public Optional<PacDetector> detector() { return PacDetector.fromKey(detectorKey); }
}

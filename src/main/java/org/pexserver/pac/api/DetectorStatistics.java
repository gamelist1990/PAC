package org.pexserver.pac.api;

import java.util.Optional;

/** Lifetime aggregate for one detector's stored records. Times are Unix epoch milliseconds. */
public record DetectorStatistics(String detectorKey, long detections,
                                 long falsePositives, long debugDetections,
                                 double averageScore, double maximumScore,
                                 long lastDetectedAt) {
    /** Typed identifier when the stored key is part of the current PAC detector set. */
    public Optional<PacDetector> detector() { return PacDetector.fromKey(detectorKey); }
}

package org.pexserver.pac.api;

import java.util.List;

/** A bounded page of recent detections, newest first. */
public record DetectionHistoryPage(List<DetectionRecord> records, long total,
                                   int page, int pages) {
    public DetectionHistoryPage { records = List.copyOf(records); }
}

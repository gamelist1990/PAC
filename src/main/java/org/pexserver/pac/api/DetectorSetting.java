package org.pexserver.pac.api;

/**
 * Named per-detector config fields. A field is only available for detectors
 * whose config defines it; PAC reports an error otherwise.
 */
public enum DetectorSetting {
    ENABLED("enabled"),
    BAN_ENABLED("ban-enabled"),
    CANCEL("cancel"),
    KICK_ENABLED("kick-enabled"),
    KICK_SCORE_THRESHOLD("kick-score-threshold"),
    ALERT_SCORE_THRESHOLD("alert-score-threshold"),
    MAX_PER_SECOND("max-per-second"),
    MAX_PACKETS_PER_SECOND("max-packets-per-second"),
    MAX_TARGETS_PER_SECOND("max-targets-per-second"),
    OFFSET_THRESHOLD("offset-threshold"),
    HORIZONTAL_OFFSET_THRESHOLD("horizontal-offset-threshold"),
    BUFFER_THRESHOLD("buffer-threshold"),
    ELYTRA_OFFSET_THRESHOLD("elytra-offset-threshold"),
    ELYTRA_BUFFER_THRESHOLD("elytra-buffer-threshold"),
    HOVER_TICKS("hover-ticks"),
    FAST_TICKS("fast-ticks"),
    WINDOW_SECONDS("window-seconds"),
    MINIMUM_BLOCKS("minimum-blocks"),
    RARE_HIGH_RATIO("rare-high-ratio"),
    RARE_MEDIUM_RATIO("rare-medium-ratio"),
    COMMON_HIGH_RATIO("common-high-ratio"),
    COMMON_MEDIUM_RATIO("common-medium-ratio"),
    SUSPICION_THRESHOLD("suspicion-threshold"),
    RARE_JUMP_DISTANCE("rare-jump-distance"),
    PHASE_FLAGS_REQUIRED("phase-flags-required"),
    COLLISION_TOLERANCE("collision-tolerance"),
    MAX_DISTANCE("max-distance"),
    CONTACT_TICKS("contact-ticks");

    private final String key;

    DetectorSetting(String key) { this.key = key; }

    /** Config key under {@code detectors.<detector>}. */
    public String key() { return key; }
}

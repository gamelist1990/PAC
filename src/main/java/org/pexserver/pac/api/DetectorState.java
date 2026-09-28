package org.pexserver.pac.api;

/** Effective detector state for a global or world-specific query. */
public record DetectorState(String key, boolean enabled, boolean globalEnabled,
                            boolean worldOverride, boolean cancelEnabled,
                            boolean cancelOverride, boolean automaticBanEligible,
                            boolean automaticBanEnabled, boolean automaticKickEligible,
                            boolean automaticKickEnabled) {
    /** Typed detector identifier for integrations that want enum-based handling. */
    public PacDetector detector() {
        return PacDetector.fromKey(key).orElseThrow(
                () -> new IllegalStateException("PAC API is missing detector enum for: " + key));
    }
}

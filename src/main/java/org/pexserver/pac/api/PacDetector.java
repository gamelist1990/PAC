package org.pexserver.pac.api;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** Stable, compile-time names for PAC detector modules. */
public enum PacDetector {
    INVALID_MOVEMENT("invalid-movement"),
    PACKET_FLOOD("packet-flood"),
    INVALID_PITCH("invalid-pitch"),
    MOTION_PREDICTION("motion-prediction"),
    AIR_PREDICTION("air-prediction"),
    TIMER_PREDICTION("timer-prediction"),
    SURFACE_PREDICTION("surface-prediction"),
    WATER_FLOW_PREDICTION("water-flow-prediction"),
    WATER_MOTION_PREDICTION("water-motion-prediction"),
    BOAT_FLIGHT("boat-flight"),
    FAST_PLACE("fast-place"),
    FAST_BREAK("fast-break"),
    NUKER("nuker"),
    ANTI_HUNGER("anti-hunger"),
    INVENTORY_MOVE("inventory-move"),
    BEDROCK_PREDICTION("bedrock-prediction"),
    CRASH_CHEST("crash-chest"),
    SCAFFOLD("scaffold"),
    REACH("reach"),
    KILL_AURA("kill-aura"),
    CRITICAL_PACKET("critical-packet"),
    XRAY("xray"),
    NOCLIP("noclip");

    private final String key;

    PacDetector(String key) { this.key = key; }

    /** Configuration and event key used by PAC, such as {@code motion-prediction}. */
    public String key() { return key; }

    /** Finds a detector from its config key, ignoring case. */
    public static Optional<PacDetector> fromKey(String key) {
        if (key == null) return Optional.empty();
        String normalized = key.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(detector -> detector.key.equals(normalized)).findFirst();
    }
}

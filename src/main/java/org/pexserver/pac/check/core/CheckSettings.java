package org.pexserver.pac.check.core;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Thread-safe detector switches and thresholds loaded from config.yml. */
public final class CheckSettings {
    private final Map<String, Boolean> enabled = new ConcurrentHashMap<>();
    private final Map<String, Boolean> cancel = new ConcurrentHashMap<>();
    private volatile boolean rollbackEnabled = true;
    private volatile int maxPacketsPerSecond;
    private volatile int maxNukerPacketsPerSecond;
    private volatile int maxNukerTargetsPerSecond;
    private volatile double predictionOffsetThreshold;
    private volatile double predictionBufferThreshold;
    private volatile double airOffsetThreshold;
    private volatile double airHorizontalOffsetThreshold;
    private volatile double airBufferThreshold;
    private volatile double elytraOffsetThreshold;
    private volatile double elytraBufferThreshold;
    private volatile double waterMotionOffsetThreshold;
    private volatile double waterMotionBufferThreshold;

    public void reload(CheckRegistry registry, FileConfiguration config) {
        rollbackEnabled = config.getBoolean("punishments.rollback-enabled", true);
        for (CheckModule module : registry.modules()) {
            String path = "detectors." + module.key();
            enabled.put(module.key(), config.getBoolean(path + ".enabled",
                    !module.key().equals("xray") && !module.key().equals("noclip")));
            boolean defaultCancel = switch (module.key()) {
                // Movement and packet-rate findings must reject the offending
                // client packet as well as report it. Otherwise the violation
                // can be logged while the movement itself is still accepted.
                case "crash-chest", "scaffold", "reach", "critical-packet", "inventory-move", "invalid-movement", "invalid-pitch",
                        "packet-flood", "nuker", "anti-hunger", "motion-prediction", "air-prediction", "timer-prediction",
                        "surface-prediction", "water-flow-prediction", "water-motion-prediction", "noclip",
                        "boat-flight", "vehicle-movement", "kill-aura" -> true;
                default -> false;
            };
            cancel.put(module.key(), config.getBoolean(path + ".cancel", defaultCancel));
        }
        maxPacketsPerSecond = Math.max(40,
                Math.min(8_191, config.getInt("detectors.packet-flood.max-per-second", 240)));
        maxNukerPacketsPerSecond = Math.max(40,
                Math.min(8_192, config.getInt("detectors.nuker.max-packets-per-second", 80)));
        maxNukerTargetsPerSecond = Math.max(8,
                Math.min(512, config.getInt("detectors.nuker.max-targets-per-second", 24)));
        predictionOffsetThreshold = Math.max(0.02, config.getDouble("detectors.motion-prediction.offset-threshold", 0.04));
        predictionBufferThreshold = Math.max(3, config.getDouble("detectors.motion-prediction.buffer-threshold", 8));
        airOffsetThreshold = Math.max(0.02, config.getDouble("detectors.air-prediction.offset-threshold", 0.06));
        airHorizontalOffsetThreshold = Math.max(0.005,
                config.getDouble("detectors.air-prediction.horizontal-offset-threshold", 0.015));
        airBufferThreshold = Math.max(3, config.getDouble("detectors.air-prediction.buffer-threshold", 6));
        elytraOffsetThreshold = Math.max(0.01,
                config.getDouble("detectors.air-prediction.elytra-offset-threshold", 0.035));
        elytraBufferThreshold = Math.max(3,
                config.getDouble("detectors.air-prediction.elytra-buffer-threshold", 6));
        waterMotionOffsetThreshold = Math.max(0.05,
                config.getDouble("detectors.water-motion-prediction.offset-threshold", 0.05));
        waterMotionBufferThreshold = Math.max(3,
                config.getDouble("detectors.water-motion-prediction.buffer-threshold", 6));
    }

    public boolean enabled(CheckModule module) { return Boolean.TRUE.equals(enabled.get(module.key())); }
    public void setEnabled(CheckModule module, boolean value) { enabled.put(module.key(), value); }
    public boolean cancel(CheckModule module) { return Boolean.TRUE.equals(cancel.get(module.key())); }
    public boolean rollbackEnabled() { return rollbackEnabled; }
    public int maxPacketsPerSecond() { return maxPacketsPerSecond; }
    public int maxNukerPacketsPerSecond() { return maxNukerPacketsPerSecond; }
    public int maxNukerTargetsPerSecond() { return maxNukerTargetsPerSecond; }
    public double predictionOffsetThreshold() { return predictionOffsetThreshold; }
    public double predictionBufferThreshold() { return predictionBufferThreshold; }
    public double airOffsetThreshold() { return airOffsetThreshold; }
    public double airHorizontalOffsetThreshold() { return airHorizontalOffsetThreshold; }
    public double airBufferThreshold() { return airBufferThreshold; }
    public double elytraOffsetThreshold() { return elytraOffsetThreshold; }
    public double elytraBufferThreshold() { return elytraBufferThreshold; }
    public double waterMotionOffsetThreshold() { return waterMotionOffsetThreshold; }
    public double waterMotionBufferThreshold() { return waterMotionBufferThreshold; }
}

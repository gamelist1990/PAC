package org.pexserver.pac.api;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.pexserver.pac.PacPlugin;
import org.pexserver.pac.check.core.CheckModule;
import org.pexserver.pac.storage.ViolationStore;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Internal implementation registered as the public PAC service. */
public final class PacApiImpl implements PacApi {
    private static final Set<String> DIRECT_SETTINGS = java.util.Arrays.stream(PacSetting.values())
            .map(PacSetting::path)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    private final PacPlugin plugin;

    public PacApiImpl(PacPlugin plugin) { this.plugin = plugin; }

    @Override public int apiVersion() { return API_VERSION; }
    @Override public boolean controlAuthorityEnabled() { return plugin.apiControlAuthority(); }

    @Override public List<DetectorState> detectorStates() {
        plugin.requireServerThread();
        return plugin.checks().modules().stream().map(module -> state(module, null)).toList();
    }

    @Override public List<DetectorState> detectorStates(UUID worldId) {
        plugin.requireServerThread();
        if (worldId == null) throw new IllegalArgumentException("World UUID is required.");
        return plugin.checks().modules().stream().map(module -> state(module, worldId)).toList();
    }

    private DetectorState state(CheckModule module, UUID worldId) {
        PacDetector detector = detectorFor(module);
        boolean global = plugin.enabled(module);
        Boolean enabledOverride = worldId == null ? null
                : plugin.worldDetectorEnabled(detector.key(), worldId);
        Boolean cancelOverride = worldId == null ? null
                : plugin.worldDetectorCancel(detector.key(), worldId);
        boolean enabled = enabledOverride == null || !plugin.apiControlAuthority()
                ? global : enabledOverride;
        boolean cancel = cancelOverride == null || !plugin.apiControlAuthority()
                ? plugin.cancel(module) : plugin.rollbackEnabled() && cancelOverride;
        String detectorPath = "detectors." + detector.key() + ".";
        boolean banEligible = module.automaticBanEligible();
        boolean kickEligible = module.automaticKickEligible();
        return new DetectorState(module.key(), enabled, global,
                plugin.apiControlAuthority() && enabledOverride != null,
                cancel, plugin.apiControlAuthority() && cancelOverride != null,
                banEligible,
                banEligible && plugin.getConfig().getBoolean(detectorPath + "ban-enabled", true)
                        && plugin.getConfig().getBoolean("punishments.probation-enabled", true),
                kickEligible,
                kickEligible && plugin.getConfig().getBoolean(detectorPath + "kick-enabled", false));
    }

    @Override public boolean isDetectorEnabled(PacDetector detector) {
        return plugin.enabled(requireModule(detector));
    }

    @Override public boolean isDetectorEnabled(PacDetector detector, UUID worldId) {
        if (worldId == null) throw new IllegalArgumentException("World UUID is required.");
        CheckModule module = requireModule(detector);
        Boolean override = plugin.worldDetectorEnabled(module.key(), worldId);
        return plugin.apiControlAuthority() && override != null ? override : plugin.enabled(module);
    }

    @Override public boolean isDetectorCancelEnabled(PacDetector detector, UUID worldId) {
        if (worldId == null) throw new IllegalArgumentException("World UUID is required.");
        CheckModule module = requireModule(detector);
        Boolean override = plugin.worldDetectorCancel(module.key(), worldId);
        return plugin.apiControlAuthority() && override != null
                ? plugin.rollbackEnabled() && override : plugin.cancel(module);
    }

    @Override public void setDetectorEnabled(PacDetector detector, boolean enabled) {
        requireAuthorityAndMainThread();
        plugin.setEnabled(requireModule(detector), enabled);
    }

    @Override public void setWorldDetectorEnabled(PacDetector detector, UUID worldId, boolean enabled) {
        requireAuthorityAndMainThread();
        plugin.setWorldDetectorEnabled(requireModule(detector).key(), worldId, enabled);
    }

    @Override public void setWorldDetectorCancel(PacDetector detector, UUID worldId, boolean enabled) {
        requireAuthorityAndMainThread();
        plugin.setWorldDetectorCancel(requireModule(detector).key(), worldId, enabled);
    }

    @Override public void clearWorldDetectorOverride(PacDetector detector, UUID worldId) {
        requireAuthorityAndMainThread();
        plugin.clearWorldDetectorOverride(requireModule(detector).key(), worldId);
    }

    @Override public void clearWorldDetectorEnabledOverride(PacDetector detector, UUID worldId) {
        requireAuthorityAndMainThread();
        plugin.clearWorldDetectorEnabledOverride(requireModule(detector).key(), worldId);
    }

    @Override public void clearWorldDetectorCancelOverride(PacDetector detector, UUID worldId) {
        requireAuthorityAndMainThread();
        plugin.clearWorldDetectorCancelOverride(requireModule(detector).key(), worldId);
    }

    @Override public Map<String, Object> settings() {
        plugin.requireServerThread();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("api.control-authority", plugin.apiControlAuthority());
        for (String root : List.of("alerts", "debug", "punishments", "scoring", "detectors"))
            flattenSettings(root, result);
        return Map.copyOf(result);
    }

    @Override public Object setting(String path) {
        plugin.requireServerThread();
        String normalized = normalizePath(path);
        return readSetting(normalized, path);
    }

    @Override public Object setting(PacSetting setting) {
        if (setting == null) throw new IllegalArgumentException("PAC setting is required.");
        plugin.requireServerThread();
        return readSetting(setting.path(), setting.name());
    }

    @Override public Object detectorSetting(PacDetector detector, DetectorSetting setting) {
        if (setting == null) throw new IllegalArgumentException("Detector setting is required.");
        plugin.requireServerThread();
        String path = detectorPath(detector, setting);
        return readSetting(path, path);
    }

    private Object readSetting(String normalized, String displayName) {
        if (normalized.equals("api.control-authority")) return plugin.apiControlAuthority();
        if (!isReadableSetting(normalized) || !plugin.getConfig().contains(normalized))
            throw new IllegalArgumentException("Unsupported PAC setting: " + displayName);
        return plugin.getConfig().get(normalized);
    }

    @Override public void setSetting(String path, Object value) {
        requireAuthorityAndMainThread();
        String normalized = normalizePath(path);
        writeSetting(normalized, value, path);
    }

    @Override public void setSetting(PacSetting setting, Object value) {
        if (setting == null) throw new IllegalArgumentException("PAC setting is required.");
        requireAuthorityAndMainThread();
        writeSetting(setting.path(), value, setting.name());
    }

    @Override public void setDetectorSetting(PacDetector detector, DetectorSetting setting, Object value) {
        if (setting == null) throw new IllegalArgumentException("Detector setting is required.");
        requireAuthorityAndMainThread();
        String path = detectorPath(detector, setting);
        writeSetting(path, value, path);
    }

    private void writeSetting(String normalized, Object value, String displayName) {
        if (!isWritableSetting(normalized) || !plugin.getConfig().contains(normalized))
            throw new IllegalArgumentException("Unsupported PAC setting: " + displayName);
        Object current = plugin.getConfig().get(normalized);
        if (current instanceof Boolean) {
            if (!(value instanceof Boolean)) throw new IllegalArgumentException("This setting requires a boolean.");
        } else if (current instanceof Number) {
            if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
                    || number.doubleValue() < 0 || number.doubleValue() > 1_000_000) {
                throw new IllegalArgumentException("This setting requires a finite number from 0 to 1000000.");
            }
            if (normalized.endsWith("threshold") && number.doubleValue() <= 0)
                throw new IllegalArgumentException("Thresholds must be greater than zero.");
            value = number.doubleValue();
        } else {
            throw new IllegalArgumentException("Only boolean and numeric PAC settings can be changed by API.");
        }

        if (normalized.equals("debug.recording-mode")) {
            plugin.setDebugRecording((Boolean) value);
            return;
        }

        String[] parts = normalized.split("\\.");
        if (parts.length == 3 && parts[0].equals("detectors") && parts[2].equals("enabled")) {
            PacDetector detector = PacDetector.fromKey(parts[1]).orElseThrow(
                    () -> new IllegalArgumentException("Unknown detector: " + parts[1]));
            plugin.setEnabled(requireModule(detector), (Boolean) value);
            return;
        }
        plugin.getConfig().set(normalized, value);
        plugin.saveConfig();
        plugin.refreshSettings();
    }

    @Override public List<BanInfo> activeBans() {
        return plugin.store().bans().stream().map(PacApiImpl::banInfo).toList();
    }

    @Override public Optional<BanInfo> activeBan(UUID playerId) {
        if (playerId == null) throw new IllegalArgumentException("Player UUID is required.");
        return Optional.ofNullable(plugin.store().banOf(playerId)).map(PacApiImpl::banInfo);
    }

    @Override public Optional<BanInfo> findActiveBan(String playerOrUuid) {
        if (playerOrUuid == null || playerOrUuid.isBlank()) return Optional.empty();
        return plugin.store().findBan(playerOrUuid.trim()).map(PacApiImpl::banInfo);
    }

    @Override public CompletableFuture<DetectionHistoryPage> detectionHistory(
            String playerOrUuid, int page, int pageSize) {
        CompletableFuture<DetectionHistoryPage> result = new CompletableFuture<>();
        String identity = playerOrUuid == null || playerOrUuid.isBlank() ? null : playerOrUuid.trim();
        runAsync(() -> {
            ViolationStore.HistoryPage stored = plugin.store().historyPage(identity, page, pageSize);
            List<DetectionRecord> records = stored.rows().stream()
                    .map(PacApiImpl::detectionInfo).toList();
            result.complete(new DetectionHistoryPage(records, stored.total(), stored.page(), stored.pages()));
        }, result);
        return result;
    }

    @Override public CompletableFuture<List<DetectorStatistics>> detectorStatistics() {
        CompletableFuture<List<DetectorStatistics>> result = new CompletableFuture<>();
        runAsync(() -> result.complete(plugin.store().detectorStatistics().stream()
                .map(PacApiImpl::statisticsInfo).toList()), result);
        return result;
    }

    @Override public CompletableFuture<Optional<DetectorStatistics>> detectorStatistics(PacDetector detector) {
        if (detector == null)
            return CompletableFuture.failedFuture(new IllegalArgumentException("Detector is required."));
        CompletableFuture<Optional<DetectorStatistics>> result = new CompletableFuture<>();
        runAsync(() -> result.complete(plugin.store().detectorStatistic(detector.key())
                .map(PacApiImpl::statisticsInfo)), result);
        return result;
    }

    @Override public CompletableFuture<Boolean> markFalsePositive(long detectionId, boolean falsePositive) {
        if (detectionId <= 0)
            return CompletableFuture.failedFuture(new IllegalArgumentException("Detection ID must be positive."));
        if (!plugin.apiControlAuthority())
            return CompletableFuture.failedFuture(new IllegalStateException("Enable api.control-authority before API writes."));
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        runAsync(() -> {
            plugin.requireApiControl();
            result.complete(plugin.store().markFalsePositive(detectionId, falsePositive));
        }, result);
        return result;
    }

    @Override public CompletableFuture<java.nio.file.Path> exportStatistics() {
        CompletableFuture<java.nio.file.Path> result = new CompletableFuture<>();
        runAsync(() -> result.complete(plugin.store().exportStatistics()), result);
        return result;
    }

    @Override public CompletableFuture<Optional<SupportCaseInfo>> supportCase(String supportId) {
        if (supportId == null || supportId.isBlank())
            return CompletableFuture.failedFuture(new IllegalArgumentException("Support ID is required."));
        CompletableFuture<Optional<SupportCaseInfo>> result = new CompletableFuture<>();
        runAsync(() -> result.complete(plugin.store().supportCase(supportId)
                .map(PacApiImpl::supportInfo)), result);
        return result;
    }

    @Override public CompletableFuture<BanInfo> ban(UUID playerId, String playerName,
                                                     String reason, Duration duration) {
        if (playerId == null || playerName == null || playerName.isBlank())
            return CompletableFuture.failedFuture(new IllegalArgumentException("Player ID and name are required."));
        if (duration != null && (duration.isNegative() || duration.isZero()))
            return CompletableFuture.failedFuture(new IllegalArgumentException("Ban duration must be positive."));
        if (!plugin.apiControlAuthority())
            return CompletableFuture.failedFuture(new IllegalStateException("Enable api.control-authority before API writes."));
        CompletableFuture<BanInfo> result = new CompletableFuture<>();
        runAsync(() -> {
            plugin.requireApiControl();
            long now = System.currentTimeMillis();
            long expiresAt;
            try {
                long durationMillis = duration == null ? 0 : Math.max(1, duration.toMillis());
                expiresAt = duration == null ? 0 : Math.addExact(now, durationMillis);
            }
            catch (ArithmeticException overflow) { throw new IllegalArgumentException("Ban duration is too large.", overflow); }
            boolean permanent = duration == null;
            ViolationStore.Ban saved = plugin.store().applyBan(playerId, playerName,
                    reason == null || reason.isBlank() ? "API ban" : reason,
                    expiresAt, permanent, 0);
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    if (plugin.store().isCurrentBan(saved)) {
                        plugin.publishBan(saved);
                        Player online = Bukkit.getPlayer(playerId);
                        if (online != null && online.isOnline())
                            online.kick(plugin.banMessage(saved.permanent(), saved.expiresAt(), saved.supportId()));
                    }
                    result.complete(banInfo(saved));
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            });
        }, result);
        return result;
    }

    @Override public CompletableFuture<Boolean> unban(UUID playerId) {
        if (playerId == null)
            return CompletableFuture.failedFuture(new IllegalArgumentException("Player UUID is required."));
        if (!plugin.apiControlAuthority())
            return CompletableFuture.failedFuture(new IllegalStateException("Enable api.control-authority before API writes."));
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        runAsync(() -> {
            plugin.requireApiControl();
            result.complete(plugin.store().unban(playerId));
        }, result);
        return result;
    }

    private void runAsync(AsyncTask task, CompletableFuture<?> future) {
        try {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try { task.run(); }
                catch (Throwable failure) { future.completeExceptionally(failure); }
            });
        } catch (RuntimeException unavailable) {
            future.completeExceptionally(unavailable);
        }
    }

    @FunctionalInterface private interface AsyncTask { void run() throws Exception; }

    private CheckModule requireModule(PacDetector detector) {
        if (detector == null) throw new IllegalArgumentException("Detector is required.");
        CheckModule module = plugin.checks().get(detector.key());
        if (module == null) throw new IllegalArgumentException("Detector is unavailable: " + detector.key());
        return module;
    }

    private static PacDetector detectorFor(CheckModule module) {
        return PacDetector.fromKey(module.key()).orElseThrow(
                () -> new IllegalStateException("PAC API is missing detector enum for: " + module.key()));
    }

    private static String detectorPath(PacDetector detector, DetectorSetting setting) {
        if (detector == null) throw new IllegalArgumentException("Detector is required.");
        return "detectors." + detector.key() + "." + setting.key();
    }

    private void flattenSettings(String path, Map<String, Object> output) {
        org.bukkit.configuration.ConfigurationSection section =
                plugin.getConfig().getConfigurationSection(path);
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            String childPath = path + "." + key;
            Object value = plugin.getConfig().get(childPath);
            if (value instanceof org.bukkit.configuration.ConfigurationSection)
                flattenSettings(childPath, output);
            else if (value instanceof Boolean || value instanceof Number || value instanceof String)
                output.put(childPath, value);
        }
    }

    private boolean isReadableSetting(String path) {
        if (path.equals("api.control-authority")) return true;
        String[] parts = path.split("\\.");
        if (parts.length < 2 || !Set.of("alerts", "debug", "punishments", "scoring", "detectors")
                .contains(parts[0])) return false;
        if (parts[0].equals("detectors")
                && (parts.length < 3 || plugin.checks().get(parts[1]) == null)) return false;
        Object value = plugin.getConfig().get(path);
        return value instanceof Boolean || value instanceof Number || value instanceof String;
    }

    private boolean isWritableSetting(String path) {
        if (DIRECT_SETTINGS.contains(path)) return true;
        String[] parts = path.split("\\.");
        if (parts.length != 3 || !parts[0].equals("detectors")
                || plugin.checks().get(parts[1]) == null) return false;
        Object value = plugin.getConfig().get(path);
        return value instanceof Boolean || value instanceof Number;
    }

    private static String normalizePath(String path) {
        if (path == null || path.isBlank()) throw new IllegalArgumentException("Setting path is required.");
        return path.trim().toLowerCase(Locale.ROOT);
    }

    private void requireAuthorityAndMainThread() {
        plugin.requireApiControl();
        plugin.requireServerThread();
    }

    private static BanInfo banInfo(ViolationStore.Ban ban) {
        return new BanInfo(ban.uuid(), ban.name(), ban.reason(), ban.createdAt(),
                ban.expiresAt(), ban.permanent(), ban.stage(), ban.supportId());
    }

    private static SupportCaseInfo supportInfo(ViolationStore.SupportCase support) {
        return new SupportCaseInfo(support.supportId(), support.uuid(), support.name(), support.reason(),
                support.createdAt(), support.expiresAt(), support.permanent(), support.stage(),
                support.active() && (support.permanent() || support.expiresAt() > System.currentTimeMillis()));
    }

    private static DetectionRecord detectionInfo(ViolationStore.Violation violation) {
        return new DetectionRecord(violation.id(), violation.uuid(), violation.name(), violation.detector(),
                violation.score(), violation.detail(), violation.createdAt(),
                violation.falsePositive(), violation.debugMode(), violation.metricsJson());
    }

    private static DetectorStatistics statisticsInfo(ViolationStore.DetectorStatistics statistics) {
        return new DetectorStatistics(statistics.detector(), statistics.detections(), statistics.falsePositives(),
                statistics.debugDetections(), statistics.averageScore(), statistics.maximumScore(),
                statistics.lastDetectedAt());
    }
}

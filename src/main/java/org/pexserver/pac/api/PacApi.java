package org.pexserver.pac.api;

import java.time.Duration;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.World;

/**
 * Public integration API for other Bukkit plugins. Obtain it through
 * Bukkit.getServicesManager().load(PacApi.class).
 */
public interface PacApi {
    int API_VERSION = 1;

    int apiVersion();

    /** True only when api.control-authority is enabled in PAC's config. */
    boolean controlAuthorityEnabled();

    List<DetectorState> detectorStates();
    List<DetectorState> detectorStates(UUID worldId);
    default List<DetectorState> detectorStates(World world) {
        return detectorStates(requireWorld(world).getUID());
    }
    boolean isDetectorEnabled(PacDetector detector);
    boolean isDetectorEnabled(PacDetector detector, UUID worldId);
    boolean isDetectorCancelEnabled(PacDetector detector, UUID worldId);
    default boolean isDetectorEnabled(PacDetector detector, World world) {
        return isDetectorEnabled(detector, requireWorld(world).getUID());
    }
    default boolean isDetectorCancelEnabled(PacDetector detector, World world) {
        return isDetectorCancelEnabled(detector, requireWorld(world).getUID());
    }

    /** @deprecated Use the {@link PacDetector} overload. */
    @Deprecated
    default boolean isDetectorEnabled(String detectorKey) {
        return isDetectorEnabled(requireDetectorKey(detectorKey));
    }
    /** @deprecated Use the {@link PacDetector} overload. */
    @Deprecated
    default boolean isDetectorEnabled(String detectorKey, UUID worldId) {
        return isDetectorEnabled(requireDetectorKey(detectorKey), worldId);
    }
    /** @deprecated Use the {@link PacDetector} overload. */
    @Deprecated
    default boolean isDetectorCancelEnabled(String detectorKey, UUID worldId) {
        return isDetectorCancelEnabled(requireDetectorKey(detectorKey), worldId);
    }

    /** Global detector state changes require API control authority and the server thread. */
    void setDetectorEnabled(PacDetector detector, boolean enabled);

    /** @deprecated Use the {@link PacDetector} overload. */
    @Deprecated
    default void setDetectorEnabled(String detectorKey, boolean enabled) {
        setDetectorEnabled(requireDetectorKey(detectorKey), enabled);
    }

    /** Adds or replaces a world override; null is not accepted. Requires authority and server thread. */
    void setWorldDetectorEnabled(PacDetector detector, UUID worldId, boolean enabled);
    default void setWorldDetectorEnabled(PacDetector detector, World world, boolean enabled) {
        setWorldDetectorEnabled(detector, requireWorld(world).getUID(), enabled);
    }

    /** @deprecated Use the {@link PacDetector} overload. */
    @Deprecated
    default void setWorldDetectorEnabled(String detectorKey, UUID worldId, boolean enabled) {
        setWorldDetectorEnabled(requireDetectorKey(detectorKey), worldId, enabled);
    }

    /** Adds or replaces a per-world packet cancellation / movement correction override. */
    void setWorldDetectorCancel(PacDetector detector, UUID worldId, boolean enabled);
    default void setWorldDetectorCancel(PacDetector detector, World world, boolean enabled) {
        setWorldDetectorCancel(detector, requireWorld(world).getUID(), enabled);
    }

    /** @deprecated Use the {@link PacDetector} overload. */
    @Deprecated
    default void setWorldDetectorCancel(String detectorKey, UUID worldId, boolean enabled) {
        setWorldDetectorCancel(requireDetectorKey(detectorKey), worldId, enabled);
    }

    /** Removes both world overrides so the detector falls back to PAC's global settings. */
    void clearWorldDetectorOverride(PacDetector detector, UUID worldId);
    default void clearWorldDetectorOverride(PacDetector detector, World world) {
        clearWorldDetectorOverride(detector, requireWorld(world).getUID());
    }

    /** Clear just the world enable override, preserving its cancellation override. */
    void clearWorldDetectorEnabledOverride(PacDetector detector, UUID worldId);
    default void clearWorldDetectorEnabledOverride(PacDetector detector, World world) {
        clearWorldDetectorEnabledOverride(detector, requireWorld(world).getUID());
    }

    /** Clear just the world cancellation override, preserving its enable override. */
    void clearWorldDetectorCancelOverride(PacDetector detector, UUID worldId);
    default void clearWorldDetectorCancelOverride(PacDetector detector, World world) {
        clearWorldDetectorCancelOverride(detector, requireWorld(world).getUID());
    }

    /** @deprecated Use the {@link PacDetector} overload. */
    @Deprecated
    default void clearWorldDetectorOverride(String detectorKey, UUID worldId) {
        clearWorldDetectorOverride(requireDetectorKey(detectorKey), worldId);
    }

    /** Immutable snapshot of scalar PAC settings. */
    Map<String, Object> settings();

    /** Read a setting by config path. Must be called on the server thread. */
    @Deprecated
    Object setting(String path);

    /** Read a standard PAC setting by its named enum. Must be called on the server thread. */
    Object setting(PacSetting setting);

    /** Read a detector setting by typed detector and field names. */
    Object detectorSetting(PacDetector detector, DetectorSetting setting);

    /** Writes a supported detector or enforcement setting; requires authority and server thread. */
    @Deprecated
    void setSetting(String path, Object value);

    /** Write a standard PAC setting by its named enum. */
    void setSetting(PacSetting setting, Object value);

    /** Write a detector setting by typed detector and field names. */
    void setDetectorSetting(PacDetector detector, DetectorSetting setting, Object value);

    List<BanInfo> activeBans();
    Optional<BanInfo> activeBan(UUID playerId);
    /** Looks up an active ban by UUID, exact player name, or an unambiguous name prefix. */
    Optional<BanInfo> findActiveBan(String playerOrUuid);

    /**
     * Reads a bounded history page. A null or blank identity returns server-wide history;
     * otherwise identity may be a UUID or previously stored player name.
     * Database reads run asynchronously and pages contain at most 50 rows.
     */
    CompletableFuture<DetectionHistoryPage> detectionHistory(String playerOrUuid,
                                                              int page, int pageSize);
    default CompletableFuture<DetectionHistoryPage> detectionHistory(int page) {
        return detectionHistory(null, page, 20);
    }
    default CompletableFuture<DetectionHistoryPage> detectionHistory(String playerOrUuid, int page) {
        return detectionHistory(playerOrUuid, page, 20);
    }

    /** Lifetime statistics grouped by detector; database reads run asynchronously. */
    CompletableFuture<List<DetectorStatistics>> detectorStatistics();
    CompletableFuture<Optional<DetectorStatistics>> detectorStatistics(PacDetector detector);

    /** Mark or unmark a stored record as a false positive; requires API control authority. */
    CompletableFuture<Boolean> markFalsePositive(long detectionId, boolean falsePositive);

    /** Writes a point-in-time JSON export into the PAC plugin directory. */
    CompletableFuture<Path> exportStatistics();

    /** DB-backed lookup; the future completes asynchronously. */
    CompletableFuture<Optional<SupportCaseInfo>> supportCase(String supportId);

    /**
     * Applies a ban and disconnects an online player. A null duration means permanent;
     * otherwise duration must be positive. Requires API control authority.
     */
    CompletableFuture<BanInfo> ban(UUID playerId, String playerName, String reason, Duration duration);

    /** Removes an active ban without resetting the player's PAC trust profile; requires API control authority. */
    CompletableFuture<Boolean> unban(UUID playerId);

    private static PacDetector requireDetectorKey(String key) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("Detector key is required.");
        return PacDetector.fromKey(key).orElseThrow(
                () -> new IllegalArgumentException("Unknown PAC detector: " + key));
    }

    private static World requireWorld(World world) {
        return java.util.Objects.requireNonNull(world, "world");
    }
}

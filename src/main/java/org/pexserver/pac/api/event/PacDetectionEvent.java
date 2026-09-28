package org.pexserver.pac.api.event;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.pexserver.pac.api.PacDetector;

import java.util.Map;
import java.util.UUID;

/** Fired synchronously on the server thread for each PAC detection record. */
public final class PacDetectionEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID playerId;
    private final String playerName;
    private final String detector;
    private final double score;
    private final String detail;
    private final Map<String, Double> metrics;
    private final boolean debugRecording;

    public PacDetectionEvent(UUID playerId, String playerName, String detector, double score,
                             String detail, Map<String, Double> metrics, boolean debugRecording) {
        this.playerId = playerId;
        this.playerName = playerName;
        this.detector = detector;
        this.score = score;
        this.detail = detail;
        this.metrics = Map.copyOf(metrics);
        this.debugRecording = debugRecording;
    }

    public UUID playerId() { return playerId; }
    public String playerName() { return playerName; }
    public String detector() { return detector; }
    /** Typed detector identifier; {@link #detector()} remains for compatibility. */
    public PacDetector pacDetector() {
        return PacDetector.fromKey(detector).orElseThrow(
                () -> new IllegalStateException("PAC API is missing detector enum for: " + detector));
    }
    public double score() { return score; }
    public String detail() { return detail; }
    public Map<String, Double> metrics() { return metrics; }
    public boolean debugRecording() { return debugRecording; }
    public UUID getPlayerId() { return playerId; }
    public String getPlayerName() { return playerName; }
    public String getDetector() { return detector; }
    public double getScore() { return score; }
    public String getDetail() { return detail; }
    public Map<String, Double> getMetrics() { return metrics; }
    public boolean isDebugRecording() { return debugRecording; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}

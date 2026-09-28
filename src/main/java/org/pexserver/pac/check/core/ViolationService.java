package org.pexserver.pac.check.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.pexserver.pac.PacPlugin;
import org.pexserver.pac.platform.NmsClock;
import org.pexserver.pac.storage.ViolationStore;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Main-thread score updates and alerts; persistence and enforcement IO stay off the server thread. */
public final class ViolationService {
    private record EnforcementPolicy(double dailyRiskAllowance, double trustLoss,
                                     double sixDayTrustThreshold, double permanentTrustThreshold,
                                     double trustRecoveryPerCleanDay) { }

    private final PacPlugin plugin;
    private final NmsClock clock;
    private final ContinuousViolationScore scores = new ContinuousViolationScore();
    private final Set<UUID> pendingEnforcement = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Map<String, Long>> lastAlertAt = new HashMap<>();

    public ViolationService(PacPlugin plugin, NmsClock clock) {
        this.plugin = plugin;
        this.clock = clock;
    }

    public void flag(UUID uuid, CheckModule module, String detail) {
        flag(uuid, module, detail, Map.of(), 1);
    }

    public void flag(UUID uuid, CheckModule module, String detail, Map<String, Double> metrics) {
        flag(uuid, module, detail, metrics, 1);
    }

    public void flag(UUID uuid, CheckModule module, String detail,
                     Map<String, Double> metrics, int scoreWeight) {
        if (!plugin.isEnabled()) return;
        boolean debugRecording = plugin.isDebugRecording();
        Map<String, Double> capturedMetrics = metrics == null ? Map.of() : Map.copyOf(metrics);
        Runnable action = () -> record(uuid, module, detail, capturedMetrics,
                debugRecording, scoreWeight);
        // PacketEvents and the Geyser bridge may call from a network thread.
        // Bukkit event checks already run on the server thread and can be
        // processed immediately without adding a one-tick punishment delay.
        if (Bukkit.isPrimaryThread()) action.run();
        else Bukkit.getScheduler().runTask(plugin, action);
    }

    private void record(UUID uuid, CheckModule module, String detail,
                        Map<String, Double> metrics, boolean queuedInDebugMode, int scoreWeight) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null || plugin.isExempt(uuid, module) || !plugin.enabled(uuid, module)) return;

        boolean debugRecording = queuedInDebugMode || plugin.isDebugRecording();
        var config = plugin.getConfig();
        long now = System.currentTimeMillis();
        // Score decay and combo windows must not stall when the system clock is adjusted.
        long scoreTime = System.nanoTime() / 1_000_000L;
        long halfLifeMillis = Math.round(setting("scoring.half-life-seconds", 30, 0.001, 31_536_000) * 1000);
        long repeatWindowMillis = Math.round(setting("scoring.repeat-window-seconds", 2, 0, 300) * 1000);
        double repeatMultiplier = setting("scoring.repeat-multiplier", 1.5, 1, 100);
        double maximumMultiplier = setting("scoring.maximum-multiplier", 8, 1, 1000);
        double maximumScore = setting("scoring.maximum-score", 1000, 1, 1_000_000);
        double baseWeight = Math.max(0, scoreWeight);
        ContinuousViolationScore.Update update = scores.add(uuid, scoreTime, baseWeight,
                halfLifeMillis, repeatWindowMillis, repeatMultiplier, maximumMultiplier, maximumScore);
        double threshold = setting("punishments.score-threshold", 25, 0.01, 1_000_000);
        scores.resetClaimBelow(uuid, threshold);

        Map<String, Double> captured = new HashMap<>(metrics);
        captured.put("pac_score", update.score());
        captured.put("pac_score_added", update.added());
        captured.put("pac_base_weight", baseWeight);
        captured.put("pac_repeat_streak", (double) update.streak());
        captured.put("pac_repeat_multiplier", update.multiplier());
        captured.put("pac_score_half_life_ms", (double) halfLifeMillis);
        String scoreDetail = detail + "; PAC score=" + formatNumber(update.score())
                + " added=" + formatNumber(update.added())
                + " streak=" + update.streak()
                + " multiplier=x" + formatNumber(update.multiplier())
                + "; tick=" + clock.tick();
        plugin.store().record(uuid, player.getName(), module.key(), update.score(),
                scoreDetail, debugRecording, captured);
        Bukkit.getPluginManager().callEvent(new org.pexserver.pac.api.event.PacDetectionEvent(
                uuid, player.getName(), module.key(), update.score(), detail, captured, debugRecording));

        String detectorPath = "detectors." + module.key();
        double alertThreshold = setting(detectorPath + ".alert-score-threshold", 1, 0.01, 1_000_000);
        if (config.getBoolean("alerts.enabled", true) && update.score() >= alertThreshold
                && shouldAlert(uuid, module.key(), scoreTime)) {
            String alert = (debugRecording ? "[記録のみ] " : "")
                    + player.getName() + " / " + module.key()
                    + " / Score " + formatNumber(update.score()) + " (x"
                    + formatNumber(update.multiplier()) + ") / " + detail;
            plugin.getLogger().warning(ChatColor.stripColor(alert));
            Component inGame = LegacyComponentSerializer.legacySection().deserialize(plugin.prefix())
                    .append(Component.text(debugRecording ? "記録 " : "検知 ",
                            debugRecording ? NamedTextColor.AQUA : NamedTextColor.RED))
                    .append(Component.text(player.getName(), NamedTextColor.WHITE))
                    .append(Component.text("  »  ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(module.key(), NamedTextColor.YELLOW))
                    .append(Component.text("  Score ", NamedTextColor.GRAY))
                    .append(Component.text(String.format(java.util.Locale.ROOT, "%.2f", update.score()),
                            NamedTextColor.GOLD))
                    .hoverEvent(HoverEvent.showText(Component.text(detail, NamedTextColor.GRAY)))
                    .clickEvent(ClickEvent.suggestCommand("/pac history 1 " + player.getName()));
            for (Player staff : Bukkit.getOnlinePlayers())
                if (staff.hasPermission("pac.alerts") && plugin.alerts(staff.getUniqueId()))
                    staff.sendMessage(inGame);
        }

        if (debugRecording || plugin.isDebugRecording()) return;
        if (module.automaticKickEligible()
                && config.getBoolean(detectorPath + ".kick-enabled", false)
                && update.score() >= setting(detectorPath + ".kick-score-threshold", 10, 0.01, 1_000_000)) {
            player.kick(plugin.kickMessage());
            return;
        }

        boolean canEnforce = module.automaticBanEligible()
                && config.getBoolean(detectorPath + ".ban-enabled", true)
                && config.getBoolean("punishments.probation-enabled", true);
        if (!canEnforce || !scores.claimThreshold(uuid, threshold)) return;

        if (!pendingEnforcement.add(uuid)) return;
        EnforcementPolicy policy = new EnforcementPolicy(
                setting("punishments.daily-risk-allowance", 3, 0.01, 1_000_000),
                setting("punishments.trust-loss-per-action", 20, 0, 100),
                setting("punishments.six-day-trust-threshold", 60, 0, 100),
                setting("punishments.permanent-trust-threshold", 25, 0, 100),
                setting("punishments.trust-recovery-per-clean-day", 5, 0, 100));
        enforceAsync(uuid, player.getName(), module.key(), update.score(), threshold, policy,
                plugin.enforcementGeneration());
    }

    private void enforceAsync(UUID uuid, String name, String detector, double score,
                              double threshold, EnforcementPolicy policy, long generation) {
        try {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                Component kickMessage = null;
                boolean failed = false;
                try {
                    if (plugin.enforcementLive(generation))
                        kickMessage = applyEnforcement(uuid, name, detector, score, threshold,
                                policy, generation);
                } catch (Exception e) {
                    failed = true;
                    plugin.getLogger().severe("PAC score enforcement failed: " + e.getMessage());
                } finally {
                    pendingEnforcement.remove(uuid);
                }

                if (!plugin.isEnabled()) return;
                if (failed) {
                    Bukkit.getScheduler().runTask(plugin, () -> scores.releaseThreshold(uuid));
                } else if (kickMessage != null) {
                    Component message = kickMessage;
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        Player online = Bukkit.getPlayer(uuid);
                        if (plugin.enforcementLive(generation)
                                && online != null && online.isOnline())
                            online.kick(message);
                    });
                }
            });
        } catch (RuntimeException e) {
            pendingEnforcement.remove(uuid);
            scores.releaseThreshold(uuid);
            plugin.getLogger().severe("Could not queue PAC score enforcement: " + e.getMessage());
        }
    }

    private Component applyEnforcement(UUID uuid, String name, String detector, double score,
                                       double threshold, EnforcementPolicy policy,
                                       long generation) throws Exception {
        long now = System.currentTimeMillis();
        String day = LocalDate.now(ZoneOffset.UTC).toString();
        ViolationStore store = plugin.store();
        var state = store.enforcement(uuid, name, day);

        double trust = recoverTrust(state.trust(), state.lastViolationAt(), now,
                policy.trustRecoveryPerCleanDay());
        double confidence = Math.min(1.0, score / threshold);
        double trustMultiplier = 1.0 + ((100.0 - trust) / 50.0);
        double dailyRisk = state.dailyRisk() + confidence * trustMultiplier;

        if (dailyRisk < policy.dailyRiskAllowance()) {
            double updatedTrust = trust;
            if (!plugin.runEnforcementWhenLive(generation, () -> store.saveEnforcement(
                    new ViolationStore.Enforcement(uuid, name, trustStage(updatedTrust),
                            updatedTrust, day, dailyRisk, now)))) return null;
            return plugin.kickMessage();
        }

        trust = Math.max(0.0, trust - policy.trustLoss() * confidence);
        boolean permanent = trust < policy.permanentTrustThreshold();
        int days = trust >= policy.sixDayTrustThreshold() ? 6 : 30;
        int nextStage = permanent ? 3 : days == 30 ? 2 : 1;
        long expiresAt = permanent ? 0 : now + Duration.ofDays(days).toMillis();

        double updatedTrust = trust;
        ViolationStore.Ban[] savedBan = new ViolationStore.Ban[1];
        if (!plugin.runEnforcementWhenLive(generation, () -> {
            savedBan[0] = store.applyBan(uuid, name, detector, expiresAt, permanent, nextStage);
            store.saveEnforcement(new ViolationStore.Enforcement(uuid, name, nextStage,
                    updatedTrust, day, 0, now));
        })) return null;
        plugin.publishBan(savedBan[0]);
        return plugin.banMessage(permanent, expiresAt, savedBan[0].supportId());
    }

    private static double recoverTrust(double trust, long lastViolationAt, long now,
                                       double recoveryPerDay) {
        if (lastViolationAt <= 0 || now <= lastViolationAt) return trust;
        long cleanDays = Duration.ofMillis(now - lastViolationAt).toDays();
        return Math.min(100.0, trust + cleanDays * recoveryPerDay);
    }

    private static int trustStage(double trust) {
        if (trust < 25.0) return 3;
        if (trust < 60.0) return 2;
        return trust < 100.0 ? 1 : 0;
    }

    private double setting(String path, double fallback, double minimum, double maximum) {
        double value = plugin.getConfig().getDouble(path, fallback);
        return Double.isFinite(value) ? Math.max(minimum, Math.min(maximum, value)) : fallback;
    }

    private static String formatNumber(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private boolean shouldAlert(UUID uuid, String detector, long now) {
        long interval = Math.round(setting("alerts.minimum-interval-ms", 1000, 0, 60_000));
        Map<String, Long> byDetector = lastAlertAt.computeIfAbsent(uuid, ignored -> new HashMap<>());
        Long previous = byDetector.get(detector);
        if (previous != null && now >= previous && now - previous < interval) return false;
        byDetector.put(detector, now);
        return true;
    }

    public void clearScores() {
        scores.clear();
        lastAlertAt.clear();
    }

    public void forget(UUID uuid) {
        scores.reset(uuid);
        lastAlertAt.remove(uuid);
    }
}

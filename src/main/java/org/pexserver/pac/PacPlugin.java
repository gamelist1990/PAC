package org.pexserver.pac;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.pexserver.pac.api.PacApi;
import org.pexserver.pac.api.PacApiImpl;
import org.pexserver.pac.api.BanInfo;
import org.pexserver.pac.api.event.PacBanEvent;
import org.pexserver.pac.check.core.BedrockViolationCheck;
import org.pexserver.pac.check.core.BedrockViolationContext;
import org.pexserver.pac.check.core.CheckModule;
import org.pexserver.pac.check.core.CheckRegistry;
import org.pexserver.pac.check.core.DebugEnforcementGate;
import org.pexserver.pac.check.core.CheckSettings;
import org.pexserver.pac.check.core.MovementDispatchMetrics;
import org.pexserver.pac.check.core.PacketCheck;
import org.pexserver.pac.check.core.ViolationService;
import org.pexserver.pac.check.bedrock.prediction.BedrockPredictionCheck;
import org.pexserver.pac.check.java.movement.MotionPredictionCheck;
import org.pexserver.pac.check.java.movement.AntiHungerCheck;
import org.pexserver.pac.check.java.movement.AirPredictionCheck;
import org.pexserver.pac.check.java.movement.TimerPredictionCheck;
import org.pexserver.pac.check.java.movement.SurfacePredictionCheck;
import org.pexserver.pac.check.java.movement.WaterFlowPredictionCheck;
import org.pexserver.pac.check.java.movement.WaterMotionPredictionCheck;
import org.pexserver.pac.check.java.packet.CriticalPacketCheck;
import org.pexserver.pac.check.java.packet.InvalidMovementCheck;
import org.pexserver.pac.check.java.packet.InvalidPitchCheck;
import org.pexserver.pac.check.java.packet.PacketFloodCheck;
import org.pexserver.pac.check.java.action.FastPlaceCheck;
import org.pexserver.pac.check.java.action.FastBreakCheck;
import org.pexserver.pac.check.java.action.NukerCheck;
import org.pexserver.pac.check.java.action.InventoryMoveCheck;
import org.pexserver.pac.check.shared.BoatFlightCheck;
import org.pexserver.pac.check.shared.CrashChestCheck;
import org.pexserver.pac.check.shared.ScaffoldCheck;
import org.pexserver.pac.check.shared.ReachCheck;
import org.pexserver.pac.check.shared.KillAuraCheck;
import org.pexserver.pac.check.shared.NoClipCheck;
import org.pexserver.pac.check.shared.AttributeSwapGuard;
import org.pexserver.pac.check.shared.XrayCheck;
import org.pexserver.pac.command.PacCommand;
import org.pexserver.pac.movement.MotionEnvironment;
import org.pexserver.pac.movement.JavaRollbackWindow;
import org.pexserver.pac.movement.WaterFlowEnvironment;
import org.pexserver.pac.movement.WaterMotionEnvironment;
import org.pexserver.pac.movement.ground.GroundStateService;
import org.pexserver.pac.packet.PacketChecks;
import org.pexserver.pac.platform.BedrockSupport;
import org.pexserver.pac.platform.BedrockExtensionInstaller;
import org.pexserver.pac.platform.NmsClock;
import org.pexserver.pac.storage.ViolationStore;
import org.pexserver.pac.ui.PacSettingsUi;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PacPlugin extends JavaPlugin implements Listener {
    static final String CONFIG_VERSION_KEY = "config-version";
    static final int CONFIG_VERSION = 15;
    private static final String DEFAULT_BAN_SUFFIX = "&7Support: &bdiscord.gg/xxxx";
    private static final String DEFAULT_KICK_SUFFIX = "";
    private static final long JAVA_CORRECTION_INTERVAL_NANOS = 50_000_000L;
    private static final String[] MOVEMENT_REJECTION_CHECKS = {
            "invalid-movement", "invalid-pitch", "packet-flood", "motion-prediction",
            "air-prediction", "timer-prediction", "surface-prediction",
            "water-flow-prediction", "water-motion-prediction"
    };
    private final BedrockSupport bedrock = new BedrockSupport();
    private final NmsClock clock = new NmsClock();
    private final GroundStateService ground = new GroundStateService(clock);
    private final CheckRegistry checks = new CheckRegistry((uuid, module) -> enabled(uuid, module) && !isExempt(uuid, module));
    private final CheckSettings checkSettings = new CheckSettings();
    private final MovementDispatchMetrics worldSampleMetrics = new MovementDispatchMetrics();
    private final ViolationService violations = new ViolationService(this, clock);
    private volatile boolean acceptingBedrockInput;
    private final DebugEnforcementGate debugEnforcement = new DebugEnforcementGate();
    private final Set<UUID> exempt = ConcurrentHashMap.newKeySet();
    private final Set<UUID> alerts = ConcurrentHashMap.newKeySet();
    private final Set<UUID> bedrockPlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> bridgeObservedPlayers = ConcurrentHashMap.newKeySet();
    private final java.util.Map<UUID, UUID> playerWorlds = new ConcurrentHashMap<>();
    private final java.util.Map<UUID, java.util.Map<String, Boolean>> worldDetectorEnabled = new ConcurrentHashMap<>();
    private final java.util.Map<UUID, java.util.Map<String, Boolean>> worldDetectorCancel = new ConcurrentHashMap<>();
    private final java.util.Map<UUID, Long> lastJavaCorrection = new ConcurrentHashMap<>();
    private final long[] lastSampleFailureLog = new long[4];
    private volatile String banMessageSuffix = DEFAULT_BAN_SUFFIX;
    private volatile String kickMessageSuffix = DEFAULT_KICK_SUFFIX;
    private volatile boolean apiControlAuthority;
    private ViolationStore store;
    private PacketChecks packetChecks;
    private MotionEnvironment environment;
    private WaterFlowEnvironment waterFlow;
    private WaterMotionEnvironment waterMotion;
    private AirPredictionCheck airPrediction;
    private MotionPredictionCheck motionPrediction;
    private FastBreakCheck fastBreak;
    private NukerCheck nuker;
    private InventoryMoveCheck inventoryMove;
    private KillAuraCheck killAura;
    private PacketListenerCommon packetRegistration;
    private PacketListenerCommon outgoingMotionRegistration;
    private PacSettingsUi settingsUi;
    private PacApiImpl api;

    @Override public void onLoad() {
        BedrockExtensionInstaller.install(this);
    }

    @Override public void onEnable() {
        saveDefaultConfig();
        checks.register(new InvalidMovementCheck());
        checks.register(new InvalidPitchCheck());
        checks.register(new PacketFloodCheck());
        checks.register(new TimerPredictionCheck());
        checks.register(new AntiHungerCheck());
        motionPrediction = new MotionPredictionCheck();
        checks.register(motionPrediction);
        airPrediction = new AirPredictionCheck();
        checks.register(airPrediction);
        checks.register(new SurfacePredictionCheck());
        checks.register(new WaterFlowPredictionCheck());
        checks.register(new WaterMotionPredictionCheck());
        // Packet-critical history must only see coordinates accepted by movement checks.
        checks.register(new CriticalPacketCheck(this));
        BoatFlightCheck boatFlight = new BoatFlightCheck(this);
        checks.register(boatFlight);
        FastPlaceCheck fastPlace = new FastPlaceCheck(this);
        checks.register(fastPlace);
        fastBreak = new FastBreakCheck(this);
        checks.register(fastBreak);
        nuker = new NukerCheck(this);
        checks.register(nuker);
        inventoryMove = new InventoryMoveCheck(this);
        checks.register(inventoryMove);
        CrashChestCheck crashChest = new CrashChestCheck(this);
        ScaffoldCheck scaffold = new ScaffoldCheck(this);
        ReachCheck reach = new ReachCheck(this);
        killAura = new KillAuraCheck(this);
        checks.register(crashChest);
        checks.register(scaffold);
        checks.register(reach);
        checks.register(killAura);
        checks.register(new BedrockPredictionCheck());
        XrayCheck xray = new XrayCheck(this);
        NoClipCheck noClip = new NoClipCheck(this);
        checks.register(xray);
        checks.register(noClip);
        migrateSettingsDefaults();
        refreshSettings();
        try { store = new ViolationStore(this, getConfig().getInt("storage.busy-timeout-ms", 3000)); }
        catch (Exception e) { getLogger().severe("SQLite initialization failed: " + e.getMessage()); Bukkit.getPluginManager().disablePlugin(this); return; }
        api = new PacApiImpl(this);
        Bukkit.getServicesManager().register(PacApi.class, api, this, ServicePriority.Normal);
        packetChecks = new PacketChecks(this);
        environment = new MotionEnvironment(ground);
        waterFlow = new WaterFlowEnvironment();
        waterMotion = new WaterMotionEnvironment();
        packetRegistration = PacketEvents.getAPI().getEventManager().registerListener(packetChecks, PacketListenerPriority.NORMAL);
        outgoingMotionRegistration = PacketEvents.getAPI().getEventManager()
                .registerListener(packetChecks.outgoingListener(), PacketListenerPriority.MONITOR);
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(packetChecks, this);
        Bukkit.getPluginManager().registerEvents(environment, this);
        Bukkit.getPluginManager().registerEvents(boatFlight, this);
        Bukkit.getPluginManager().registerEvents(fastPlace, this);
        Bukkit.getPluginManager().registerEvents(fastBreak, this);
        Bukkit.getPluginManager().registerEvents(inventoryMove, this);
        Bukkit.getPluginManager().registerEvents(crashChest, this);
        Bukkit.getPluginManager().registerEvents(scaffold, this);
        Bukkit.getPluginManager().registerEvents(reach, this);
        Bukkit.getPluginManager().registerEvents(killAura, this);
        Bukkit.getPluginManager().registerEvents(xray, this);
        Bukkit.getPluginManager().registerEvents(noClip, this);
        Bukkit.getPluginManager().registerEvents(new AttributeSwapGuard(this), this);
        Bukkit.getScheduler().runTaskTimer(this, crashChest::scanPlayers, 1L, 20L);
        settingsUi = new PacSettingsUi(this);
        Bukkit.getPluginManager().registerEvents(settingsUi, this);
        for (Player player : Bukkit.getOnlinePlayers()) updatePlayer(player);
        Bukkit.getScheduler().runTaskTimer(this, fastBreak::sampleActiveBreaks, 1L, 1L);
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            long sampleStarted = System.nanoTime();
            environment.serverTiming().tick(sampleStarted);
            try {
                var players = Bukkit.getOnlinePlayers();
                int sampleTick = ground.currentTick();
                for (Player player : players) {
                    UUID uuid = player.getUniqueId();
                    boolean groundReady = true;
                    try { ground.samplePlayer(player, sampleTick); }
                    catch (RuntimeException e) {
                        groundReady = false;
                        reportSampleFailure(0, "ground", e);
                        ground.forget(uuid);
                        environment.discardFailedSample(uuid);
                        environment.grace(uuid, 250);
                    }
                    if (groundReady) {
                        try { environment.samplePlayer(player); }
                        catch (RuntimeException e) {
                            reportSampleFailure(1, "movement", e);
                            environment.discardFailedSample(uuid);
                            environment.grace(uuid, 250);
                        }
                    }
                    try { waterFlow.samplePlayer(player); }
                    catch (RuntimeException e) {
                        reportSampleFailure(2, "water flow", e);
                        waterFlow.forget(uuid);
                    }
                    try { waterMotion.samplePlayer(player); }
                    catch (RuntimeException e) {
                        reportSampleFailure(3, "water motion", e);
                        waterMotion.forget(uuid);
                    }
                }
            } finally {
                worldSampleMetrics.record(System.nanoTime() - sampleStarted);
            }
        }, 1L, 1L);
        Bukkit.getScheduler().runTaskTimer(this, () -> airPrediction.sampleSilence(this), 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, boatFlight::sampleOnlineVehicles, 1L, 1L);
        PacCommand command = new PacCommand(this);
        getCommand("pac").setExecutor(command);
        getCommand("pac").setTabCompleter(command);
        for (Player player : Bukkit.getOnlinePlayers()) updatePlayer(player);
        acceptingBedrockInput = true;
        getLogger().info("PAC enabled. PacketEvents, SQLite, and NMS clock ready.");
    }

    private void reportSampleFailure(int sampler, String name, RuntimeException error) {
        long now = System.currentTimeMillis();
        long previous = lastSampleFailureLog[sampler];
        if (previous != 0 && now >= previous && now - previous < 10_000) return;
        lastSampleFailureLog[sampler] = now;
        getLogger().log(java.util.logging.Level.SEVERE,
                "PAC " + name + " sampling failed; other samplers will continue", error);
    }

    @Override public void onDisable() {
        acceptingBedrockInput = false;
        Bukkit.getServicesManager().unregisterAll(this);
        if (killAura != null) killAura.close();
        if (packetRegistration != null) PacketEvents.getAPI().getEventManager().unregisterListener(packetRegistration);
        if (outgoingMotionRegistration != null)
            PacketEvents.getAPI().getEventManager().unregisterListener(outgoingMotionRegistration);
        if (store != null) {
            try { store.close(); }
            catch (Exception e) { getLogger().severe("SQLite shutdown failed: " + e.getMessage()); }
        }
    }

    public void refreshSettings() {
        reloadConfig();
        banMessageSuffix = getConfig().getString("punishments.messages.ban-suffix", DEFAULT_BAN_SUFFIX);
        kickMessageSuffix = getConfig().getString("punishments.messages.kick-suffix", DEFAULT_KICK_SUFFIX);
        apiControlAuthority = getConfig().getBoolean("api.control-authority", false);
        loadWorldDetectorOverrides();
        boolean configuredDebug = getConfig().getBoolean("debug.recording-mode", false);
        if (debugEnforcement.setRecording(configuredDebug)) violations.clearScores();
        checkSettings.reload(checks, getConfig());
    }

    /** Upgrade legacy names and enable packet correction once for existing installs. */
    private void migrateSettingsDefaults() {
        int version = getConfig().getInt(CONFIG_VERSION_KEY, 0);
        if (version < 1) {
            for (String key : MOVEMENT_REJECTION_CHECKS)
                getConfig().set("detectors." + key + ".cancel", true);
        }
        if (version < 2) {
            if (!getConfig().contains("punishments.score-threshold"))
                getConfig().set("punishments.score-threshold",
                        getConfig().getDouble("punishments.enforcement-threshold", 25));
            for (CheckModule module : checks.modules()) {
                String detector = "detectors." + module.key();
                if (!getConfig().contains(detector + ".alert-score-threshold"))
                    getConfig().set(detector + ".alert-score-threshold",
                            getConfig().getDouble(detector + ".alert-threshold", 1));
                if (!getConfig().contains(detector + ".kick-score-threshold"))
                    getConfig().set(detector + ".kick-score-threshold",
                            getConfig().getDouble(detector + ".kick-threshold", 10));
            }
            setDefault("scoring.half-life-seconds", 30);
            setDefault("scoring.repeat-window-seconds", 2);
            setDefault("scoring.repeat-multiplier", 1.5);
            setDefault("scoring.maximum-multiplier", 8);
            setDefault("scoring.maximum-score", 1000);
            setDefault("punishments.rollback-enabled", true);
        }
        if (version < 4) {
            setDefault("detectors.nuker.enabled", true);
            setDefault("detectors.nuker.ban-enabled", true);
            setDefault("detectors.nuker.cancel", true);
            setDefault("detectors.nuker.max-packets-per-second", 80);
            setDefault("detectors.nuker.max-targets-per-second", 24);
            setDefault("detectors.nuker.alert-score-threshold", 1);
        }
        if (version < 5) {
            setDefault("detectors.anti-hunger.enabled", true);
            setDefault("detectors.anti-hunger.ban-enabled", true);
            setDefault("detectors.anti-hunger.cancel", true);
            setDefault("detectors.anti-hunger.alert-score-threshold", 1);
        }
        if (version < 6) {
            setDefault("detectors.critical-packet.enabled", true);
            setDefault("detectors.critical-packet.ban-enabled", true);
            setDefault("detectors.critical-packet.cancel", true);
            setDefault("detectors.critical-packet.alert-score-threshold", 1);
        }
        if (version < 7) {
            setDefault("detectors.xray.enabled", false);
            setDefault("detectors.xray.ban-enabled", true);
            setDefault("detectors.xray.cancel", false);
            setDefault("detectors.xray.alert-score-threshold", 1);
            setDefault("detectors.xray.window-seconds", 120);
            setDefault("detectors.xray.minimum-blocks", 60);
            setDefault("detectors.xray.rare-high-ratio", 0.08);
            setDefault("detectors.xray.rare-medium-ratio", 0.05);
            setDefault("detectors.xray.common-high-ratio", 0.15);
            setDefault("detectors.xray.common-medium-ratio", 0.08);
            setDefault("detectors.xray.suspicion-threshold", 18);
            setDefault("detectors.xray.rare-jump-distance", 10);
        }
        if (version < 8) {
            setDefault("detectors.noclip.enabled", false);
            setDefault("detectors.noclip.ban-enabled", true);
            setDefault("detectors.noclip.cancel", true);
            setDefault("detectors.noclip.alert-score-threshold", 1);
            setDefault("detectors.noclip.phase-flags-required", 5);
            setDefault("detectors.noclip.collision-tolerance", 0.10);
            setDefault("detectors.noclip.max-distance", 10);
            setDefault("detectors.noclip.kick-enabled", false);
            setDefault("detectors.noclip.kick-score-threshold", 10);
        }
        if (version < 9) {
        }
        if (version < 10) {
            // Contact overlap was previously treated as punitive evidence even
            // though vanilla entity pushing allows it during normal movement.
        }
        if (version < 11) {
            setDefault("punishments.messages.ban", "&cBanned\n&7Duration: &f{duration}\n" + DEFAULT_BAN_SUFFIX);
            setDefault("punishments.messages.kick", "&cKicked");
        }
        if (version < 12) {
            String oldBanMessage = getConfig().getString("punishments.messages.ban");
            String oldKickMessage = getConfig().getString("punishments.messages.kick");
            getConfig().set("punishments.messages.ban-suffix", extractLegacyBanSuffix(oldBanMessage));
            getConfig().set("punishments.messages.kick-suffix", extractLegacyKickSuffix(oldKickMessage));
        }
        if (version < 13) {
            setDefault("api.control-authority", false);
            setDefault("api.world-overrides", new java.util.LinkedHashMap<>());
        }
        if (version < 14) {
            setDefault("detectors.inventory-move.enabled", true);
            setDefault("detectors.inventory-move.ban-enabled", true);
            setDefault("detectors.inventory-move.cancel", true);
            setDefault("detectors.inventory-move.alert-score-threshold", 1);
        }
        if (version < CONFIG_VERSION) {
            getConfig().set(CONFIG_VERSION_KEY, CONFIG_VERSION);
            saveConfig();
        }
    }

    private void setDefault(String path, Object value) {
        if (!getConfig().contains(path)) getConfig().set(path, value);
    }

    private void loadWorldDetectorOverrides() {
        worldDetectorEnabled.clear();
        worldDetectorCancel.clear();
        org.bukkit.configuration.ConfigurationSection worlds =
                getConfig().getConfigurationSection("api.world-overrides");
        if (worlds == null) return;
        for (String rawWorldId : worlds.getKeys(false)) {
            UUID worldId;
            try { worldId = UUID.fromString(rawWorldId); }
            catch (IllegalArgumentException invalid) { continue; }
            for (CheckModule module : checks.modules()) {
                String root = "api.world-overrides." + rawWorldId + ".detectors." + module.key();
                if (getConfig().isBoolean(root + ".enabled"))
                    worldDetectorEnabled.computeIfAbsent(worldId, ignored -> new ConcurrentHashMap<>())
                            .put(module.key(), getConfig().getBoolean(root + ".enabled"));
                if (getConfig().isBoolean(root + ".cancel"))
                    worldDetectorCancel.computeIfAbsent(worldId, ignored -> new ConcurrentHashMap<>())
                            .put(module.key(), getConfig().getBoolean(root + ".cancel"));
            }
        }
    }

    private Boolean worldOverride(java.util.Map<UUID, java.util.Map<String, Boolean>> overrides,
                                  UUID worldId, String detectorKey) {
        if (!apiControlAuthority || worldId == null) return null;
        java.util.Map<String, Boolean> perWorld = overrides.get(worldId);
        return perWorld == null ? null : perWorld.get(detectorKey.toLowerCase(java.util.Locale.ROOT));
    }

    private void setWorldOverride(java.util.Map<UUID, java.util.Map<String, Boolean>> overrides,
                                  String property, String detectorKey, UUID worldId, boolean value) {
        requireApiControl();
        requireServerThread();
        if (worldId == null) throw new IllegalArgumentException("World UUID is required.");
        CheckModule module = checks.get(detectorKey);
        if (module == null) throw new IllegalArgumentException("Unknown detector: " + detectorKey);
        overrides.computeIfAbsent(worldId, ignored -> new ConcurrentHashMap<>())
                .put(module.key(), value);
        getConfig().set("api.world-overrides." + worldId + ".detectors." + module.key()
                + "." + property, value);
        saveConfig();
    }

    public void requireApiControl() {
        if (!apiControlAuthority)
            throw new IllegalStateException("Enable api.control-authority in PAC config before API writes.");
    }

    public void requireServerThread() {
        if (!Bukkit.isPrimaryThread())
            throw new IllegalStateException("PAC config API writes must run on the server thread.");
    }

    public Component banMessage(boolean permanent, long expiresAt, String supportId) {
        String duration = permanent ? "Permanent" : formatBanDuration(expiresAt - System.currentTimeMillis());
        Component fixedMessage = Component.text("Banned", NamedTextColor.RED)
                .append(Component.text("\nDuration: ", NamedTextColor.GRAY))
                .append(Component.text(duration, NamedTextColor.WHITE))
                .append(Component.text("\nSupport ID: ", NamedTextColor.GRAY))
                .append(Component.text(supportId, NamedTextColor.AQUA));
        return appendCustomSuffix(fixedMessage, banMessageSuffix);
    }

    public String banMessageForLogin(boolean permanent, long expiresAt, String supportId) {
        return LegacyComponentSerializer.legacySection().serialize(
                banMessage(permanent, expiresAt, supportId));
    }

    public Component kickMessage() {
        Component fixedMessage = Component.empty()
                .append(Component.text("Disconnected", NamedTextColor.RED)
                        .decorate(TextDecoration.BOLD))
                .append(Component.text("\n──────────────\n", NamedTextColor.DARK_GRAY))
                .append(Component.text("You were removed from the server.", NamedTextColor.WHITE))
                .append(Component.text("\nPlease try again later.", NamedTextColor.GRAY));
        return appendCustomSuffix(fixedMessage, kickMessageSuffix);
    }

    private static String formatBanDuration(long remainingMillis) {
        long remaining = Math.max(0, remainingMillis);
        if (remaining == 0) return "0s";
        if (remaining < 60_000L) {
            long seconds = remaining / 1_000L + (remaining % 1_000L == 0 ? 0 : 1);
            return seconds + "s";
        }

        long minutes = remaining / 60_000L + (remaining % 60_000L == 0 ? 0 : 1);
        if (minutes >= 24 * 60) {
            long days = minutes / (24 * 60);
            long hours = minutes / 60 % 24;
            long remainder = minutes % 60;
            return String.format(java.util.Locale.ROOT, "%dd %02dh %02dm", days, hours, remainder);
        }
        if (minutes >= 60)
            return String.format(java.util.Locale.ROOT, "%dh %02dm", minutes / 60, minutes % 60);
        return minutes + "m";
    }

    private static Component appendCustomSuffix(Component fixedMessage, String suffix) {
        if (suffix == null || suffix.isBlank()) return fixedMessage;
        return fixedMessage.append(Component.text("\n"))
                .append(LegacyComponentSerializer.legacyAmpersand().deserialize(suffix));
    }

    private static String extractLegacyBanSuffix(String legacyMessage) {
        if (legacyMessage == null || legacyMessage.isBlank()) return DEFAULT_BAN_SUFFIX;
        String[] lines = legacyMessage.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains("{duration}"))
                return String.join("\n", java.util.Arrays.copyOfRange(lines, i + 1, lines.length)).strip();
        }
        return DEFAULT_BAN_SUFFIX;
    }

    private static String extractLegacyKickSuffix(String legacyMessage) {
        if (legacyMessage == null || legacyMessage.isBlank()) return DEFAULT_KICK_SUFFIX;
        java.util.regex.Matcher prefix = java.util.regex.Pattern
                .compile("(?i)^(?:[&§][0-9A-FK-OR])*Kicked(?:[ :|\\-]+)?")
                .matcher(legacyMessage.trim());
        return prefix.find() ? legacyMessage.trim().substring(prefix.end()).strip() : DEFAULT_KICK_SUFFIX;
    }

    public CheckRegistry checks() { return checks; }
    public PacApi api() { return api; }
    public void publishBan(ViolationStore.Ban ban) {
        Runnable fire = () -> {
            if (store == null || !store.isCurrentBan(ban)) return;
            Bukkit.getPluginManager().callEvent(new PacBanEvent(
                    new BanInfo(ban.uuid(), ban.name(), ban.reason(), ban.createdAt(),
                            ban.expiresAt(), ban.permanent(), ban.stage(), ban.supportId())));
        };
        if (Bukkit.isPrimaryThread()) fire.run();
        else if (isEnabled()) Bukkit.getScheduler().runTask(this, fire);
    }
    public GroundStateService ground() { return ground; }
    public boolean geyserAvailable() { return bedrock.geyserAvailable(); }
    public boolean bedrockEngineAvailable() { return bedrock.engineAvailable(); }
    /** Called by the Geyser extension on its network thread. No Bukkit reads here. */
    public boolean acceptBedrockAuthInput(UUID uuid, long tick,
                                          double x, double y, double z,
                                          double dx, double dy, double dz,
                                          float yaw, float pitch) {
        if (!acceptingBedrockInput) return false;
        if (uuid == null) return false;
        bridgeObservedPlayers.add(uuid);
        KillAuraCheck currentKillAura = killAura;
        if (currentKillAura != null) currentKillAura.onBedrockRotation(uuid, yaw, pitch);
        return true;
    }
    /** Receives physics violations from the bundled Geyser engine. */
    public boolean acceptBedrockViolation(UUID uuid, String check, int level, String detail) {
        if (!acceptingBedrockInput || uuid == null) return false;
        bridgeObservedPlayers.add(uuid);
        checks.dispatch(new BedrockViolationContext(this, uuid, check, level, detail));
        return true;
    }
    public void forgetBedrockBridge(UUID uuid) {
        bridgeObservedPlayers.remove(uuid);
        checks.forget(uuid);
    }
    /** Queried by the Geyser engine before simulating each Bedrock movement input. */
    public boolean bedrockMovementExempt(UUID uuid) {
        CheckModule prediction = checks.get("bedrock-prediction");
        return !acceptingBedrockInput || isExempt(uuid) || prediction == null || !enabled(uuid, prediction);
    }
    public boolean enabled(CheckModule module) { return checkSettings.enabled(module); }
    public void setEnabled(CheckModule module, boolean value) { checkSettings.setEnabled(module, value); getConfig().set("detectors." + module.key() + ".enabled", value); saveConfig(); }
    public boolean enabled(UUID playerId, CheckModule module) {
        if (!apiControlAuthority) return enabled(module);
        UUID worldId = playerWorlds.get(playerId);
        Boolean override = worldId == null ? null : worldOverride(worldDetectorEnabled, worldId, module.key());
        return override == null ? enabled(module) : override;
    }
    public boolean apiControlAuthority() { return apiControlAuthority; }
    public Boolean worldDetectorEnabled(String detectorKey, UUID worldId) {
        return worldOverride(worldDetectorEnabled, worldId, detectorKey);
    }
    public Boolean worldDetectorCancel(String detectorKey, UUID worldId) {
        return worldOverride(worldDetectorCancel, worldId, detectorKey);
    }
    public boolean setWorldDetectorEnabled(String detectorKey, UUID worldId, boolean value) {
        setWorldOverride(worldDetectorEnabled, "enabled", detectorKey, worldId, value);
        return true;
    }
    public boolean setWorldDetectorCancel(String detectorKey, UUID worldId, boolean value) {
        setWorldOverride(worldDetectorCancel, "cancel", detectorKey, worldId, value);
        return true;
    }
    public void clearWorldDetectorOverride(String detectorKey, UUID worldId) {
        requireApiControl();
        requireServerThread();
        CheckModule module = checks.get(detectorKey);
        if (module == null) throw new IllegalArgumentException("Unknown detector: " + detectorKey);
        if (worldId == null) throw new IllegalArgumentException("World UUID is required.");
        String path = "api.world-overrides." + worldId + ".detectors." + module.key();
        clearWorldOverrideValue(worldDetectorEnabled, worldId, module.key());
        clearWorldOverrideValue(worldDetectorCancel, worldId, module.key());
        getConfig().set(path + ".enabled", null);
        getConfig().set(path + ".cancel", null);
        saveConfig();
    }
    public void clearWorldDetectorEnabledOverride(String detectorKey, UUID worldId) {
        clearWorldDetectorProperty(detectorKey, worldId, "enabled", worldDetectorEnabled);
    }
    public void clearWorldDetectorCancelOverride(String detectorKey, UUID worldId) {
        clearWorldDetectorProperty(detectorKey, worldId, "cancel", worldDetectorCancel);
    }
    private void clearWorldDetectorProperty(String detectorKey, UUID worldId, String property,
                                            java.util.Map<UUID, java.util.Map<String, Boolean>> overrides) {
        requireApiControl();
        requireServerThread();
        CheckModule module = checks.get(detectorKey);
        if (module == null) throw new IllegalArgumentException("Unknown detector: " + detectorKey);
        if (worldId == null) throw new IllegalArgumentException("World UUID is required.");
        clearWorldOverrideValue(overrides, worldId, module.key());
        getConfig().set("api.world-overrides." + worldId + ".detectors." + module.key()
                + "." + property, null);
        saveConfig();
    }
    private static void clearWorldOverrideValue(
            java.util.Map<UUID, java.util.Map<String, Boolean>> overrides,
            UUID worldId, String detectorKey) {
        java.util.Map<String, Boolean> values = overrides.get(worldId);
        if (values == null) return;
        values.remove(detectorKey);
        if (values.isEmpty()) overrides.remove(worldId, values);
    }
    public int maxPacketsPerSecond() { return checkSettings.maxPacketsPerSecond(); }
    public double predictionOffsetThreshold() { return checkSettings.predictionOffsetThreshold(); }
    public double predictionBufferThreshold() { return checkSettings.predictionBufferThreshold(); }
    public double airOffsetThreshold() { return checkSettings.airOffsetThreshold(); }
    public double airHorizontalOffsetThreshold() { return checkSettings.airHorizontalOffsetThreshold(); }
    public double airBufferThreshold() { return checkSettings.airBufferThreshold(); }
    public double elytraOffsetThreshold() { return checkSettings.elytraOffsetThreshold(); }
    public double elytraBufferThreshold() { return checkSettings.elytraBufferThreshold(); }
    public double waterMotionOffsetThreshold() { return checkSettings.waterMotionOffsetThreshold(); }
    public double waterMotionBufferThreshold() { return checkSettings.waterMotionBufferThreshold(); }
    public MotionEnvironment environment() { return environment; }
    public WaterFlowEnvironment waterFlow() { return waterFlow; }
    public WaterMotionEnvironment waterMotion() { return waterMotion; }
    public boolean rollbackEnabled() { return checkSettings.rollbackEnabled(); }
    public boolean moduleCancelEnabled(CheckModule module) { return checkSettings.cancel(module); }
    public boolean cancel(CheckModule module) { return rollbackEnabled() && checkSettings.cancel(module); }
    public boolean cancel(CheckModule module, UUID playerId) {
        if (!rollbackEnabled()) return false;
        if (!apiControlAuthority) return checkSettings.cancel(module);
        UUID worldId = playerWorlds.get(playerId);
        Boolean override = worldId == null ? null : worldOverride(worldDetectorCancel, worldId, module.key());
        return override == null ? checkSettings.cancel(module) : override;
    }
    public boolean isDebugRecording() { return debugEnforcement.recording(); }
    public long enforcementGeneration() { return debugEnforcement.generation(); }
    public boolean enforcementLive(long generation) { return debugEnforcement.live(generation); }
    public boolean runEnforcementWhenLive(long generation, DebugEnforcementGate.Action action)
            throws Exception {
        return debugEnforcement.runWhenLive(generation, action);
    }
    public void setDebugRecording(boolean enabled) {
        if (debugEnforcement.setRecording(enabled)) violations.clearScores();
        getConfig().set("debug.recording-mode", enabled);
        saveConfig();
    }
    public void exportStatistics(CommandSender sender) {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                java.nio.file.Path exported = store.exportStatistics();
                Bukkit.getScheduler().runTask(this, () -> sender.sendMessage(prefix()
                        + "統計JSONを出力しました: " + getDataFolder().toPath().relativize(exported)));
            } catch (Exception exception) {
                getLogger().severe("Could not export PAC statistics: " + exception.getMessage());
                Bukkit.getScheduler().runTask(this, () -> sender.sendMessage(prefix()
                        + "統計JSONの出力に失敗しました。ログを確認してください。"));
            }
        });
    }
    public boolean isExempt(UUID uuid) { return exempt.contains(uuid); }
    public boolean isBedrockPlayer(UUID uuid) { return bedrockPlayers.contains(uuid) || bridgeObservedPlayers.contains(uuid); }
    public FastBreakCheck fastBreak() { return fastBreak; }
    public NukerCheck nuker() { return nuker; }
    public InventoryMoveCheck inventoryMove() { return inventoryMove; }
    /** Queried by the bundled Geyser hook before it forwards Bedrock movement input. */
    public int bedrockInventoryInput(UUID uuid, boolean directional, boolean jump) {
        return inventoryMove == null ? 0 : inventoryMove.onBedrockInput(uuid, directional, jump,
                System.currentTimeMillis());
    }
    public int maxNukerPacketsPerSecond() { return checkSettings.maxNukerPacketsPerSecond(); }
    public int maxNukerTargetsPerSecond() { return checkSettings.maxNukerTargetsPerSecond(); }
    public boolean recentExternalMotion(UUID uuid) {
        return packetChecks != null && packetChecks.recentExternalMotion(uuid);
    }
    public boolean recentPluginVelocity(UUID uuid) {
        return packetChecks != null && packetChecks.recentPluginVelocity(uuid);
    }
    public PacketChecks.ServerMotionGrant serverMotionGrant(UUID uuid) {
        return packetChecks == null ? null : packetChecks.serverMotionGrant(uuid);
    }
    public long externalMotionSequence(UUID uuid) {
        return packetChecks == null ? 0 : packetChecks.externalMotionSequence(uuid);
    }
    public boolean isExempt(UUID uuid, CheckModule module) {
        boolean bedrockPlayer = isBedrockPlayer(uuid);
        return isExempt(uuid)
                || (module instanceof PacketCheck packet && bedrockPlayer && !packet.supportsBedrock())
                || (module instanceof BedrockViolationCheck && !bedrockPlayer);
    }
    public void setBypass(UUID uuid, boolean value) { if (value) exempt.add(uuid); else exempt.remove(uuid); }
    public boolean alerts(UUID uuid) { return alerts.contains(uuid); }
    public void setAlerts(UUID uuid, boolean value) { if (value) alerts.add(uuid); else alerts.remove(uuid); }
    public ViolationStore store() { return store; }
    public MovementDispatchMetrics worldSampleMetrics() { return worldSampleMetrics; }
    public PacSettingsUi settingsUi() { return settingsUi; }
    public String prefix() { return ChatColor.translateAlternateColorCodes('&', getConfig().getString("alerts.prefix", "&cPAC &7")); }

    public void flag(UUID uuid, CheckModule module, String detail) { violations.flag(uuid, module, detail); }
    public void flag(UUID uuid, CheckModule module, String detail, java.util.Map<String, Double> metrics) {
        violations.flag(uuid, module, detail, metrics);
    }
    public void flag(UUID uuid, CheckModule module, String detail,
                     java.util.Map<String, Double> metrics, int scoreWeight) {
        violations.flag(uuid, module, detail, metrics, scoreWeight);
    }

    /** Receives prediction diagnostics from the bundled Geyser engine. */
    public void recordBedrockDiagnostic(UUID uuid, String check, int level, String detail) {
        CheckModule module = checks.get("bedrock-prediction");
        if (module != null && uuid != null)
            flag(uuid, module, check + " engine-level=" + level + ": " + detail);
    }

    /** Rewinds a rejected Java movement packet to its last accepted position. */
    public void correctJavaMovement(UUID uuid, double x, double y, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return;
        scheduleJavaCorrection(uuid, x, y, z, Double.POSITIVE_INFINITY, null);
    }

    /** Returns an anomalous move to the latest accepted position, using a safe floor only as fallback. */
    public void correctJavaToSafeGround(UUID uuid) {
        MotionEnvironment.Snapshot accepted = environment.get(uuid);
        if (accepted != null && System.currentTimeMillis() - accepted.capturedAt() <= 1_000) {
            scheduleJavaCorrection(uuid, accepted.x(), accepted.y(), accepted.z(),
                    Double.POSITIVE_INFINITY, null);
            return;
        }
        MotionEnvironment.SafeGround safe = environment.safeGround(uuid);
        if (safe != null && System.currentTimeMillis() - safe.capturedAt() <= 5_000) {
            scheduleJavaCorrection(uuid, safe.x(), safe.y(), safe.z(), 1024, safe.world());
            return;
        }
        // Water and wall anomalies can persist long after the last safe floor.
        // A cancelled packet still needs an authoritative position response.
        MotionEnvironment.Snapshot fallback = environment.get(uuid);
        if (fallback != null) scheduleJavaCorrection(uuid,
                fallback.x(), fallback.y(), fallback.z(), 16, null);
    }

    private void scheduleJavaCorrection(UUID uuid, double x, double y, double z,
                                        double maxDistanceSquared, UUID expectedWorld) {
        long now = System.nanoTime();
        boolean[] scheduled = {false};
        lastJavaCorrection.compute(uuid, (ignored, last) -> {
            if (last != null && now - last < JAVA_CORRECTION_INTERVAL_NANOS) return last;
            scheduled[0] = true;
            return now;
        });
        if (!scheduled[0]) return;
        var anchor = JavaRollbackWindow.select(new JavaRollbackWindow.Position(x, y, z),
                environment.teleportGeneration(uuid), expectedWorld, now);
        var anchorPosition = anchor.position();
        UUID anchorWorld = anchor.worldId();
        long teleportGeneration = environment.teleportGeneration(uuid);
        long motionSequence = externalMotionSequence(uuid);
        Bukkit.getScheduler().runTask(this, () -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline() || isExempt(uuid)
                    || environment.teleportGeneration(uuid) != teleportGeneration
                    || externalMotionSequence(uuid) != motionSequence) {
                lastJavaCorrection.remove(uuid, now);
                return;
            }
            Location current = player.getLocation();
            if (anchorWorld != null && !current.getWorld().getUID().equals(anchorWorld)) {
                lastJavaCorrection.remove(uuid, now);
                return;
            }
            Location target = new Location(current.getWorld(), anchorPosition.x(), anchorPosition.y(),
                    anchorPosition.z(), current.getYaw(), current.getPitch());
            if (current.distanceSquared(target) > maxDistanceSquared) target = current;
            if (!clearPlayerBox(player, current, target)) {
                // A block may have appeared after the movement packet was rejected.
                // The server's current position is still the authoritative fallback.
                if (!clearPlayerBox(player, current, current)) {
                    lastJavaCorrection.remove(uuid, now);
                    return;
                }
                target = current;
            }
            environment.expectPacCorrection(uuid, target);
            if (player.teleport(target, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN,
                    io.papermc.paper.entity.TeleportFlag.Relative.VELOCITY_X,
                    io.papermc.paper.entity.TeleportFlag.Relative.VELOCITY_Y,
                    io.papermc.paper.entity.TeleportFlag.Relative.VELOCITY_Z)) {
                long correctedEpoch = environment.teleportGeneration(uuid);
                Location corrected = player.getLocation();
                if (!corrected.getWorld().equals(target.getWorld())
                        || corrected.distanceSquared(target) > 1.0E-6) {
                    // Another plugin redirected the teleport. Its destination
                    // is authoritative; the old PAC correction is no longer a
                    // valid prediction baseline.
                    environment.cancelExpectedPacCorrection(uuid);
                    if (motionPrediction != null) motionPrediction.forget(uuid);
                    if (airPrediction != null) airPrediction.forget(uuid);
                    return;
                }
                var velocity = player.getVelocity();
                var motionSnapshot = environment.get(uuid);
                if (motionPrediction != null) motionPrediction.afterCorrection(uuid,
                        corrected.getX(), corrected.getY(), corrected.getZ(), correctedEpoch,
                        velocity.getX(), velocity.getY(), velocity.getZ(), motionSnapshot);
                if (airPrediction != null) airPrediction.afterCorrection(uuid,
                        corrected.getX(), corrected.getY(), corrected.getZ(), correctedEpoch,
                        velocity.getX(), velocity.getY(), velocity.getZ(), motionSnapshot);
            } else {
                environment.cancelExpectedPacCorrection(uuid);
                lastJavaCorrection.remove(uuid, now);
            }
        });
    }

    private boolean clearPlayerBox(Player player, Location current, Location target) {
        var world = target.getWorld();
        BoundingBox actual = player.getBoundingBox().clone().shift(
                target.getX() - current.getX(), target.getY() - current.getY(),
                target.getZ() - current.getZ());
        // Preserve the player's current pose and scale when validating a rollback.
        BoundingBox body = new BoundingBox(actual.getMinX() + 0.005,
                actual.getMinY() + 0.005, actual.getMinZ() + 0.005,
                actual.getMaxX() - 0.005, actual.getMaxY() - 0.005,
                actual.getMaxZ() - 0.005);
        for (int x = (int) Math.floor(body.getMinX()); x <= (int) Math.floor(body.getMaxX()); x++) {
            for (int z = (int) Math.floor(body.getMinZ()); z <= (int) Math.floor(body.getMaxZ()); z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) return false;
                for (int y = (int) Math.floor(body.getMinY()); y <= (int) Math.floor(body.getMaxY()); y++) {
                    var block = world.getBlockAt(x, y, z);
                    for (BoundingBox shape : block.getBlockData().getCollisionShape(block.getLocation()).getBoundingBoxes()) {
                        if (body.overlaps(shape.clone().shift(x, y, z))) return false;
                    }
                }
            }
        }
        return true;
    }

    private void updatePlayer(Player player) {
        UUID uuid = player.getUniqueId();
        playerWorlds.put(uuid, player.getWorld().getUID());
        if (bedrock.isBedrock(uuid)) bedrockPlayers.add(uuid);
        else bedrockPlayers.remove(uuid);
        if (player.hasPermission("pac.alerts")) alerts.add(uuid);
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        updatePlayer(player);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (player.isOnline()) updatePlayer(player);
        }, 20L);
    }
    @EventHandler public void onChangedWorld(PlayerChangedWorldEvent event) {
        playerWorlds.put(event.getPlayer().getUniqueId(), event.getPlayer().getWorld().getUID());
    }
    @EventHandler public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        playerWorlds.remove(uuid);
        exempt.remove(uuid); alerts.remove(uuid);
        lastJavaCorrection.remove(uuid);
        bedrockPlayers.remove(uuid); bridgeObservedPlayers.remove(uuid);
        checks.forget(uuid);
        if (packetChecks != null) packetChecks.forget(uuid);
        ground.forget(uuid);
        if (environment != null) environment.forget(uuid);
        if (waterFlow != null) waterFlow.forget(uuid);
        if (waterMotion != null) waterMotion.forget(uuid);
        violations.forget(uuid);
    }
    @EventHandler public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        ViolationStore.Ban ban = store.banOf(event.getUniqueId());
        if (ban != null) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED,
                banMessageForLogin(ban.permanent(), ban.expiresAt(), ban.supportId()));
        }
    }
}

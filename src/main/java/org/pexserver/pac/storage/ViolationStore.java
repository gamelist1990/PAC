package org.pexserver.pac.storage;

import org.bukkit.plugin.Plugin;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.security.SecureRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

public final class ViolationStore implements AutoCloseable {
    private static final int WRITE_BATCH_SIZE = 256;
    private static final int MAX_BATCHES_PER_PASS = 4;
    private static final String SUPPORT_ID_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom SUPPORT_ID_RANDOM = new SecureRandom();
    private record PendingViolation(long sequence, UUID uuid, String name, String detector,
                                    double score, String detail, long createdAt,
                                    boolean debugMode, Map<String, Double> metrics) { }
    public record Ban(UUID uuid, String name, String reason, long createdAt,
                      long expiresAt, boolean permanent, int stage, String supportId) {
        public boolean expired(long now) {
            return !permanent && expiresAt > 0 && expiresAt <= now;
        }
    }
    public record SupportCase(String supportId, UUID uuid, String name, String reason,
                              long createdAt, long expiresAt, boolean permanent,
                              int stage, boolean active) { }
    public record Enforcement(UUID uuid, String name, int stage, double trust,
                              String day, double dailyRisk, long lastViolationAt) { }
    public record Violation(long id, UUID uuid, String name, String detector, double score,
                            String detail, long createdAt, boolean falsePositive,
                            boolean debugMode, String metricsJson) { }
    public record HistoryPage(List<Violation> rows, long total, int page, int pages) {
        public HistoryPage { rows = List.copyOf(rows); }
    }
    public record DetectorStatistics(String detector, long detections, long falsePositives,
                                     long debugDetections, double averageScore,
                                     double maximumScore, long lastDetectedAt) { }

    private static final Pattern NUMBERED_FIELD = Pattern.compile(
            "([A-Za-z][A-Za-z0-9_.-]*)\\s*=\\s*([-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][-+]?\\d+)?)");
    private static final Pattern NUMBER_GROUP = Pattern.compile("([A-Za-z][A-Za-z0-9_. -]*)\\s*=\\s*\\(([^)]*)\\)");
    private static final Pattern NUMBER_TOKEN = Pattern.compile("[-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][-+]?\\d+)?");
    private static final DateTimeFormatter EXPORT_NAME = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss-SSS", Locale.ROOT).withZone(ZoneOffset.UTC);

    private final Connection connection;
    private final String databaseUrl;
    private final int busyTimeout;
    private final Plugin plugin;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "PAC-SQLite");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<UUID, Ban> bans = new ConcurrentHashMap<>();
    private final Map<UUID, Ban> pendingExpiry = new ConcurrentHashMap<>();
    private final Object writeGate = new Object();
    private final ArrayDeque<PendingViolation> pendingViolations = new ArrayDeque<>();
    private boolean drainScheduled;
    private boolean closed;
    private long nextWriteSequence;
    private volatile long lastProcessedSequence;

    public ViolationStore(Plugin plugin, int busyTimeout) throws Exception {
        this.plugin = plugin;
        this.busyTimeout = Math.max(0, busyTimeout);
        Files.createDirectories(plugin.getDataFolder().toPath());
        Class.forName("org.sqlite.JDBC");
        databaseUrl = "jdbc:sqlite:" + plugin.getDataFolder().toPath().resolve("pac.db");
        connection = DriverManager.getConnection(databaseUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA busy_timeout=" + this.busyTimeout);
            statement.execute("CREATE TABLE IF NOT EXISTS violations (id INTEGER PRIMARY KEY, uuid TEXT NOT NULL, name TEXT NOT NULL, detector TEXT NOT NULL, score REAL NOT NULL, detail TEXT NOT NULL, created_at INTEGER NOT NULL)");
            addColumn(statement, "violations", "false_positive", "INTEGER NOT NULL DEFAULT 0");
            addColumn(statement, "violations", "debug_mode", "INTEGER NOT NULL DEFAULT 0");
            addColumn(statement, "violations", "metrics_json", "TEXT NOT NULL DEFAULT '{}'");
            statement.execute("CREATE INDEX IF NOT EXISTS violations_player_page ON violations(uuid, created_at DESC, id DESC)");
            statement.execute("CREATE INDEX IF NOT EXISTS violations_detector_page ON violations(detector, created_at DESC, id DESC)");
            statement.execute("CREATE INDEX IF NOT EXISTS violations_name_time ON violations(name COLLATE NOCASE, created_at DESC, id DESC)");
            statement.execute("CREATE INDEX IF NOT EXISTS violations_recent_page ON violations(created_at DESC, id DESC)");
            statement.execute("DROP INDEX IF EXISTS violations_player_time");
            statement.execute("DROP INDEX IF EXISTS violations_detector_time");
            statement.execute("CREATE TABLE IF NOT EXISTS bans (uuid TEXT PRIMARY KEY, name TEXT NOT NULL, reason TEXT NOT NULL, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL DEFAULT 0, permanent INTEGER NOT NULL DEFAULT 1, stage INTEGER NOT NULL DEFAULT 0)");
            addColumn(statement, "bans", "expires_at", "INTEGER NOT NULL DEFAULT 0");
            addColumn(statement, "bans", "permanent", "INTEGER NOT NULL DEFAULT 1");
            addColumn(statement, "bans", "stage", "INTEGER NOT NULL DEFAULT 0");
            addColumn(statement, "bans", "support_id", "TEXT NOT NULL DEFAULT ''");
            statement.execute("CREATE TABLE IF NOT EXISTS support_cases (support_id TEXT PRIMARY KEY, uuid TEXT NOT NULL, name TEXT NOT NULL, reason TEXT NOT NULL, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL DEFAULT 0, permanent INTEGER NOT NULL DEFAULT 1, stage INTEGER NOT NULL DEFAULT 0, active INTEGER NOT NULL DEFAULT 1)");
            statement.execute("CREATE INDEX IF NOT EXISTS support_cases_player_time ON support_cases(uuid, created_at DESC)");
            statement.execute("CREATE TABLE IF NOT EXISTS enforcement (uuid TEXT PRIMARY KEY, name TEXT NOT NULL, stage INTEGER NOT NULL DEFAULT 0, trust REAL NOT NULL DEFAULT 100, day TEXT NOT NULL DEFAULT '', daily_risk REAL NOT NULL DEFAULT 0, last_violation_at INTEGER NOT NULL DEFAULT 0)");
            addColumn(statement, "enforcement", "last_violation_at", "INTEGER NOT NULL DEFAULT 0");
            List<Ban> loadedBans = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery("SELECT uuid,name,reason,created_at,expires_at,permanent,stage,support_id FROM bans")) {
                while (rows.next()) {
                    UUID uuid = UUID.fromString(rows.getString(1));
                    loadedBans.add(new Ban(uuid, rows.getString(2), rows.getString(3),
                            rows.getLong(4), rows.getLong(5), rows.getInt(6) != 0,
                            rows.getInt(7), rows.getString(8)));
                }
            }
            for (Ban loaded : loadedBans) bans.put(loaded.uuid(), backfillSupportCase(loaded));
        }
    }

    private Ban backfillSupportCase(Ban ban) throws SQLException {
        String supportId = ban.supportId();
        if (supportId == null || supportId.isBlank()) {
            for (int attempt = 0; attempt < 32; attempt++) {
                Ban withId = withSupportId(ban, generateSupportId());
                if (!insertSupportCase(withId, true, true)) continue;
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE bans SET support_id=? WHERE uuid=?")) {
                    update.setString(1, withId.supportId());
                    update.setString(2, withId.uuid().toString());
                    update.executeUpdate();
                }
                return withId;
            }
            throw new SQLException("Could not issue support IDs for existing bans.");
        }
        insertSupportCase(ban, true, true);
        return ban;
    }

    private boolean insertSupportCase(Ban ban, boolean active, boolean ignoreDuplicate)
            throws SQLException {
        String insert = ignoreDuplicate ? "INSERT OR IGNORE INTO support_cases"
                : "INSERT INTO support_cases";
        try (PreparedStatement statement = connection.prepareStatement(insert
                + "(support_id,uuid,name,reason,created_at,expires_at,permanent,stage,active)"
                + " VALUES(?,?,?,?,?,?,?,?,?)")) {
            statement.setString(1, ban.supportId());
            statement.setString(2, ban.uuid().toString());
            statement.setString(3, ban.name());
            statement.setString(4, ban.reason());
            statement.setLong(5, ban.createdAt());
            statement.setLong(6, ban.expiresAt());
            statement.setInt(7, ban.permanent() ? 1 : 0);
            statement.setInt(8, ban.stage());
            statement.setInt(9, active ? 1 : 0);
            return statement.executeUpdate() > 0;
        }
    }

    private static Ban withSupportId(Ban ban, String supportId) {
        return new Ban(ban.uuid(), ban.name(), ban.reason(), ban.createdAt(),
                ban.expiresAt(), ban.permanent(), ban.stage(), supportId);
    }

    private static String generateSupportId() {
        StringBuilder id = new StringBuilder("PAC-");
        for (int i = 0; i < 8; i++) {
            if (i == 4) id.append('-');
            id.append(SUPPORT_ID_ALPHABET.charAt(SUPPORT_ID_RANDOM.nextInt(SUPPORT_ID_ALPHABET.length())));
        }
        return id.toString();
    }

    private static void addColumn(Statement statement, String table, String column, String type)
            throws SQLException {
        try (ResultSet columns = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (columns.next()) {
                if (column.equalsIgnoreCase(columns.getString("name"))) return;
            }
        }
        statement.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
    }

    public Ban banOf(UUID uuid) {
        Ban ban = bans.get(uuid);
        if (ban == null || !ban.expired(System.currentTimeMillis())) return ban;
        if (pendingExpiry.putIfAbsent(uuid, ban) == null) {
            try {
                writer.execute(() -> {
                    try { unbanIfCurrent(ban, false); }
                    catch (SQLException e) {
                        plugin.getLogger().severe("Could not expire PAC ban: " + e.getMessage());
                    } finally { pendingExpiry.remove(uuid, ban); }
                });
            } catch (RejectedExecutionException closedWriter) {
                pendingExpiry.remove(uuid, ban);
            }
        }
        // A renewal may have replaced the expired entry while cleanup was queued.
        Ban current = bans.get(uuid);
        return current != null && !current.expired(System.currentTimeMillis()) ? current : null;
    }

    public List<Ban> bans() {
        long now = System.currentTimeMillis();
        return bans.values().stream()
                .filter(ban -> !ban.expired(now))
                .sorted((left, right) -> Long.compare(right.createdAt(), left.createdAt()))
                .toList();
    }

    public int activeBanCount() {
        long now = System.currentTimeMillis();
        int count = 0;
        for (Ban ban : bans.values()) if (!ban.expired(now)) count++;
        return count;
    }

    public Optional<Ban> findBan(String input) {
        try {
            return Optional.ofNullable(banOf(UUID.fromString(input)));
        } catch (IllegalArgumentException ignored) {
            // Resolve stored Floodgate/offline names without Bukkit's profile cache.
        }

        Ban exact = null;
        Ban prefix = null;
        boolean ambiguousPrefix = false;
        long now = System.currentTimeMillis();
        for (Ban ban : bans.values()) {
            if (ban.expired(now)) continue;
            if (ban.name().equalsIgnoreCase(input)) {
                if (exact != null) return Optional.empty();
                exact = ban;
            }
            if (ban.name().regionMatches(true, 0, input, 0, input.length())) {
                if (prefix != null) ambiguousPrefix = true;
                else prefix = ban;
            }
        }
        return exact != null ? Optional.of(exact)
                : ambiguousPrefix ? Optional.empty() : Optional.ofNullable(prefix);
    }

    public void record(UUID uuid, String name, String detector, double score, String detail) {
        record(uuid, name, detector, score, detail, false, Map.of());
    }

    public void record(UUID uuid, String name, String detector, double score, String detail,
                       boolean debugMode, Map<String, Double> metrics) {
        long now = System.currentTimeMillis();
        Map<String, Double> capturedMetrics = metrics == null ? Map.of() : Map.copyOf(metrics);
        synchronized (writeGate) {
            if (closed) throw new IllegalStateException("PAC violation store is closed");
            pendingViolations.addLast(new PendingViolation(++nextWriteSequence, uuid, name,
                    detector, score, detail, now, debugMode, capturedMetrics));
            scheduleDrain();
        }
    }

    /** Called with writeGate held; only one ordinary drain task is queued. */
    private void scheduleDrain() {
        if (drainScheduled) return;
        drainScheduled = true;
        writer.execute(() -> {
            try {
                for (int i = 0; i < MAX_BATCHES_PER_PASS && drainBatch(); i++) { }
            } finally {
                synchronized (writeGate) {
                    drainScheduled = false;
                    if (!closed && !pendingViolations.isEmpty()) scheduleDrain();
                }
            }
        });
    }

    private boolean drainBatch() {
        List<PendingViolation> batch = new ArrayList<>(WRITE_BATCH_SIZE);
        synchronized (writeGate) {
            for (int i = 0; i < WRITE_BATCH_SIZE && !pendingViolations.isEmpty(); i++)
                batch.add(pendingViolations.removeFirst());
        }
        if (batch.isEmpty()) return false;
        writeBatch(batch);
        lastProcessedSequence = batch.getLast().sequence();
        return true;
    }

    private void writeBatch(List<PendingViolation> batch) {
        synchronized (connection) {
            try {
                connection.setAutoCommit(false);
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO violations(uuid,name,detector,score,detail,created_at,debug_mode,metrics_json) VALUES(?,?,?,?,?,?,?,?)")) {
                    for (PendingViolation row : batch) {
                        bindViolation(statement, row);
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
                connection.commit();
            } catch (SQLException | RuntimeException batchFailure) {
                try { connection.rollback(); }
                catch (SQLException rollbackFailure) {
                    plugin.getLogger().severe("Could not roll back PAC violation batch: "
                            + rollbackFailure.getMessage());
                    // The commit outcome is unknown. Retrying here could duplicate
                    // every row if SQLite already committed the transaction.
                    return;
                }
                plugin.getLogger().warning("PAC violation batch failed; retrying records individually: "
                        + batchFailure.getMessage());
                try { connection.setAutoCommit(true); }
                catch (SQLException restoreFailure) {
                    plugin.getLogger().severe("Could not reset PAC database transaction: "
                            + restoreFailure.getMessage());
                    return;
                }
                int failures = 0;
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO violations(uuid,name,detector,score,detail,created_at,debug_mode,metrics_json) VALUES(?,?,?,?,?,?,?,?)")) {
                    for (PendingViolation row : batch) {
                        try {
                            bindViolation(statement, row);
                            statement.executeUpdate();
                        } catch (SQLException | RuntimeException ignored) { failures++; }
                    }
                } catch (SQLException statementFailure) {
                    plugin.getLogger().severe("Could not retry PAC violation batch: "
                            + statementFailure.getMessage());
                    return;
                }
                if (failures > 0) plugin.getLogger().severe(
                        "Could not save " + failures + " PAC violation record(s) from a failed batch.");
            } finally {
                try { connection.setAutoCommit(true); }
                catch (SQLException e) {
                    plugin.getLogger().severe("Could not reset PAC database transaction: " + e.getMessage());
                }
            }
        }
    }

    private static void bindViolation(PreparedStatement statement, PendingViolation row) throws SQLException {
        statement.setString(1, row.uuid().toString());
        statement.setString(2, row.name());
        statement.setString(3, row.detector());
        statement.setDouble(4, row.score());
        statement.setString(5, row.detail());
        statement.setLong(6, row.createdAt());
        statement.setInt(7, row.debugMode() ? 1 : 0);
        statement.setString(8, metricsJson(row.metrics().isEmpty()
                ? extractNumericFields(row.detail()) : row.metrics()));
    }

    private void flushThrough(long targetSequence) {
        while (lastProcessedSequence < targetSequence) {
            if (!drainBatch()) throw new IllegalStateException("PAC violation write queue lost a record");
        }
    }

    public Ban ban(UUID uuid, String name, String reason) throws SQLException {
        return applyBan(uuid, name, reason, 0, true, 0);
    }

    public Ban applyBan(UUID uuid, String name, String reason, long expiresAt,
                        boolean permanent, int stage) throws SQLException {
        synchronized (connection) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement closePrevious = connection.prepareStatement(
                        "UPDATE support_cases SET active=0 WHERE uuid=? AND active=1")) {
                    closePrevious.setString(1, uuid.toString());
                    closePrevious.executeUpdate();
                }

                long createdAt = System.currentTimeMillis();
                Ban ban = null;
                for (int attempt = 0; attempt < 32; attempt++) {
                    Ban candidate = new Ban(uuid, name, reason, createdAt,
                            expiresAt, permanent, stage, generateSupportId());
                    if (insertSupportCase(candidate, true, true)) {
                        ban = candidate;
                        break;
                    }
                }
                if (ban == null) throw new SQLException("Could not issue a unique support ID.");

                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO bans(uuid,name,reason,created_at,expires_at,permanent,stage,support_id)"
                                + " VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(uuid) DO UPDATE SET"
                                + " name=excluded.name,reason=excluded.reason,created_at=excluded.created_at,"
                                + " expires_at=excluded.expires_at,permanent=excluded.permanent,"
                                + " stage=excluded.stage,support_id=excluded.support_id")) {
                    statement.setString(1, uuid.toString());
                    statement.setString(2, name);
                    statement.setString(3, reason);
                    statement.setLong(4, createdAt);
                    statement.setLong(5, expiresAt);
                    statement.setInt(6, permanent ? 1 : 0);
                    statement.setInt(7, stage);
                    statement.setString(8, ban.supportId());
                    statement.executeUpdate();
                }
                connection.commit();
                bans.put(uuid, ban);
                return ban;
            } catch (SQLException | RuntimeException failure) {
                try { connection.rollback(); }
                catch (SQLException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
                throw failure;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        }
    }

    public Optional<SupportCase> supportCase(String input) throws SQLException {
        String supportId = input.trim().toUpperCase(Locale.ROOT);
        synchronized (connection) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT support_id,uuid,name,reason,created_at,expires_at,permanent,stage,active"
                            + " FROM support_cases WHERE support_id=?")) {
                statement.setString(1, supportId);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) return Optional.empty();
                    return Optional.of(new SupportCase(rows.getString(1),
                            UUID.fromString(rows.getString(2)), rows.getString(3), rows.getString(4),
                            rows.getLong(5), rows.getLong(6), rows.getInt(7) != 0,
                            rows.getInt(8), rows.getInt(9) != 0));
                }
            }
        }
    }

    /** Cache-only identity check for a delayed main-thread kick. */
    public boolean isCurrentBan(Ban ban) {
        return ban != null && bans.get(ban.uuid()) == ban
                && !ban.expired(System.currentTimeMillis());
    }

    public Enforcement enforcement(UUID uuid, String name, String day) throws SQLException {
        synchronized (connection) {
            try (PreparedStatement insert = connection.prepareStatement("INSERT OR IGNORE INTO enforcement(uuid,name,stage,trust,day,daily_risk,last_violation_at) VALUES(?,?,0,100,?,0,0)")) {
                insert.setString(1, uuid.toString());
                insert.setString(2, name);
                insert.setString(3, day);
                insert.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("SELECT name,stage,trust,day,daily_risk,last_violation_at FROM enforcement WHERE uuid=?")) {
                statement.setString(1, uuid.toString());
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) throw new SQLException("Missing enforcement profile.");
                    String storedDay = rows.getString(4);
                    double risk = day.equals(storedDay) ? rows.getDouble(5) : 0;
                    return new Enforcement(uuid, rows.getString(1), rows.getInt(2),
                            rows.getDouble(3), day, risk, rows.getLong(6));
                }
            }
        }
    }

    public void saveEnforcement(Enforcement state) throws SQLException {
        synchronized (connection) {
            try (PreparedStatement statement = connection.prepareStatement("UPDATE enforcement SET name=?,stage=?,trust=?,day=?,daily_risk=?,last_violation_at=? WHERE uuid=?")) {
                statement.setString(1, state.name());
                statement.setInt(2, state.stage());
                statement.setDouble(3, state.trust());
                statement.setString(4, state.day());
                statement.setDouble(5, state.dailyRisk());
                statement.setLong(6, state.lastViolationAt());
                statement.setString(7, state.uuid().toString());
                statement.executeUpdate();
            }
        }
    }

    public boolean unban(UUID uuid) throws SQLException {
        synchronized (connection) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement("DELETE FROM bans WHERE uuid=?")) {
                    statement.setString(1, uuid.toString());
                    int changed = statement.executeUpdate();
                    if (changed > 0) {
                        try (PreparedStatement closeCases = connection.prepareStatement(
                                "UPDATE support_cases SET active=0 WHERE uuid=? AND active=1")) {
                            closeCases.setString(1, uuid.toString());
                            closeCases.executeUpdate();
                        }
                    }
                    connection.commit();
                    if (changed > 0) bans.remove(uuid);
                    return changed > 0;
                }
            } catch (SQLException | RuntimeException failure) {
                try { connection.rollback(); }
                catch (SQLException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
                throw failure;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        }
    }

    /** Remove only the ban selected by the operator; a newer automatic ban must survive. */
    public boolean unbanIfCurrent(Ban expected, boolean resetTrust) throws SQLException {
        UUID uuid = expected.uuid();
        synchronized (connection) {
            if (bans.get(uuid) != expected) return false;
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                int changed;
                try (PreparedStatement delete = connection.prepareStatement("DELETE FROM bans WHERE uuid=?")) {
                    delete.setString(1, uuid.toString());
                    changed = delete.executeUpdate();
                }
                if (changed > 0) {
                    try (PreparedStatement closeCase = connection.prepareStatement(
                            "UPDATE support_cases SET active=0 WHERE support_id=?")) {
                        closeCase.setString(1, expected.supportId());
                        closeCase.executeUpdate();
                    }
                }
                if (changed > 0 && resetTrust) {
                    try (PreparedStatement reset = connection.prepareStatement(
                            "UPDATE enforcement SET stage=0,trust=100,day='',daily_risk=0,last_violation_at=0 WHERE uuid=?")) {
                        reset.setString(1, uuid.toString());
                        reset.executeUpdate();
                    }
                }
                connection.commit();
                bans.remove(uuid, expected);
                return changed > 0;
            } catch (SQLException failure) {
                try { connection.rollback(); }
                catch (SQLException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
                throw failure;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        }
    }

    public void resetEnforcement(UUID uuid) throws SQLException {
        synchronized (connection) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE enforcement SET stage=0,trust=100,day='',daily_risk=0,last_violation_at=0 WHERE uuid=?")) {
                statement.setString(1, uuid.toString());
                statement.executeUpdate();
            }
        }
    }

    public List<Violation> history(UUID uuid, int limit) throws SQLException {
        awaitPendingWrites();
        List<Violation> result = new ArrayList<>();
        synchronized (connection) {
            try (PreparedStatement statement = connection.prepareStatement("SELECT id,uuid,name,detector,score,detail,created_at,false_positive,debug_mode,metrics_json FROM violations WHERE uuid=? ORDER BY created_at DESC,id DESC LIMIT ?")) {
                statement.setString(1, uuid.toString());
                statement.setInt(2, limit);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) result.add(readViolation(rows));
                }
            }
        }
        return result;
    }

    /** Resolves a stored name from violation history without a Bukkit profile lookup. */
    public List<Violation> history(String playerOrUuid, int limit) throws SQLException {
        try {
            return history(UUID.fromString(playerOrUuid), limit);
        } catch (IllegalArgumentException ignored) {
            // A player name is looked up in the records rather than on the server thread.
        }
        awaitPendingWrites();
        UUID uuid;
        synchronized (connection) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT uuid FROM violations WHERE name=? COLLATE NOCASE "
                            + "ORDER BY created_at DESC,id DESC LIMIT 1")) {
                statement.setString(1, playerOrUuid);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) return List.of();
                    uuid = UUID.fromString(rows.getString(1));
                }
            }
        }
        return history(uuid, limit);
    }

    /** A bounded, newest-first page across all players or one stored identity. */
    public HistoryPage historyPage(String playerOrUuid, int requestedPage, int pageSize)
            throws SQLException {
        if (pageSize < 1 || pageSize > 50) throw new IllegalArgumentException("Invalid history page size");
        awaitPendingWrites();
        try (Connection reader = openStatisticsReader()) {
            UUID filter = null;
            if (playerOrUuid != null && !playerOrUuid.isBlank()) {
                try { filter = UUID.fromString(playerOrUuid); }
                catch (IllegalArgumentException ignored) {
                    try (PreparedStatement lookup = reader.prepareStatement(
                            "SELECT uuid FROM violations WHERE name=? COLLATE NOCASE "
                                    + "ORDER BY created_at DESC,id DESC LIMIT 1")) {
                        lookup.setString(1, playerOrUuid);
                        try (ResultSet rows = lookup.executeQuery()) {
                            if (!rows.next()) return new HistoryPage(List.of(), 0, 1, 1);
                            filter = UUID.fromString(rows.getString(1));
                        }
                    }
                }
            }
            String where = filter == null ? "" : " WHERE uuid=?";
            long total;
            try (PreparedStatement count = reader.prepareStatement("SELECT COUNT(*) FROM violations" + where)) {
                if (filter != null) count.setString(1, filter.toString());
                try (ResultSet rows = count.executeQuery()) {
                    total = rows.next() ? rows.getLong(1) : 0;
                }
            }
            int pages = (int) Math.min(Integer.MAX_VALUE,
                    Math.max(1, (total + pageSize - 1) / pageSize));
            int page = Math.max(1, Math.min(requestedPage, pages));
            List<Violation> result = new ArrayList<>(pageSize);
            try (PreparedStatement select = reader.prepareStatement(
                    "SELECT id,uuid,name,detector,score,detail,created_at,false_positive,debug_mode,metrics_json "
                            + "FROM violations" + where + " ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?")) {
                int parameter = 1;
                if (filter != null) select.setString(parameter++, filter.toString());
                select.setInt(parameter++, pageSize);
                select.setLong(parameter, (long) (page - 1) * pageSize);
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) result.add(readViolation(rows));
                }
            }
            return new HistoryPage(result, total, page, pages);
        }
    }

    public List<DetectorStatistics> detectorStatistics() throws SQLException {
        awaitPendingWrites();
        List<DetectorStatistics> result = new ArrayList<>();
        try (Connection reader = openStatisticsReader();
             Statement statement = reader.createStatement();
             ResultSet rows = statement.executeQuery("SELECT detector,COUNT(*),SUM(false_positive),SUM(debug_mode),AVG(score),MAX(score),MAX(created_at) FROM violations GROUP BY detector ORDER BY detector COLLATE NOCASE")) {
            while (rows.next()) result.add(new DetectorStatistics(rows.getString(1), rows.getLong(2),
                    rows.getLong(3), rows.getLong(4), rows.getDouble(5), rows.getDouble(6), rows.getLong(7)));
        }
        return result;
    }

    /** Aggregate one detector without scanning and materializing every detector group. */
    public Optional<DetectorStatistics> detectorStatistic(String detector) throws SQLException {
        awaitPendingWrites();
        try (Connection reader = openStatisticsReader();
             PreparedStatement statement = reader.prepareStatement(
                     "SELECT detector,COUNT(*),SUM(false_positive),SUM(debug_mode),AVG(score),MAX(score),MAX(created_at) "
                             + "FROM violations WHERE detector=? GROUP BY detector")) {
            statement.setString(1, detector);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                return Optional.of(new DetectorStatistics(rows.getString(1), rows.getLong(2),
                        rows.getLong(3), rows.getLong(4), rows.getDouble(5),
                        rows.getDouble(6), rows.getLong(7)));
            }
        }
    }

    private Connection openStatisticsReader() throws SQLException {
        Connection reader = DriverManager.getConnection(databaseUrl);
        try (Statement settings = reader.createStatement()) {
            settings.execute("PRAGMA busy_timeout=" + busyTimeout);
            settings.execute("PRAGMA query_only=ON");
        } catch (SQLException failure) {
            try { reader.close(); }
            catch (SQLException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
        return reader;
    }

    public List<Violation> recentViolations(String detector, int limit, int offset) throws SQLException {
        awaitPendingWrites();
        List<Violation> result = new ArrayList<>();
        synchronized (connection) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id,uuid,name,detector,score,detail,created_at,false_positive,debug_mode,metrics_json FROM violations WHERE detector=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?")) {
                statement.setString(1, detector);
                statement.setInt(2, Math.max(1, Math.min(50, limit)));
                statement.setInt(3, Math.max(0, offset));
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) result.add(readViolation(rows));
                }
            }
        }
        return result;
    }

    public boolean markFalsePositive(long violationId, boolean value) throws SQLException {
        synchronized (connection) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE violations SET false_positive=? WHERE id=?")) {
                statement.setInt(1, value ? 1 : 0);
                statement.setLong(2, violationId);
                return statement.executeUpdate() > 0;
            }
        }
    }

    /** Removes stored detection statistics and reclaims the SQLite pages they used. */
    public int clearStatistics() throws SQLException {
        awaitPendingWrites();
        synchronized (connection) {
            int removed;
            try (Statement statement = connection.createStatement()) {
                removed = statement.executeUpdate("DELETE FROM violations");
                statement.execute("VACUUM");
                try (ResultSet checkpoint = statement.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)")) {
                    if (checkpoint.next() && checkpoint.getInt(1) != 0) {
                        plugin.getLogger().warning("PAC statistics were cleared, but SQLite could not fully truncate its WAL file.");
                    }
                }
                statement.execute("PRAGMA optimize");
            }
            return removed;
        }
    }

    /** Exports a point-in-time statistics summary and the detailed records from SQLite. */
    public Path exportStatistics() throws SQLException, java.io.IOException {
        awaitPendingWrites();
        Path output = plugin.getDataFolder().toPath().resolve(
                "pac-statistics-" + EXPORT_NAME.format(Instant.now()) + "-"
                        + UUID.randomUUID().toString().substring(0, 8) + ".json");
        Path temporary = output.resolveSibling(output.getFileName() + ".tmp");
        Files.createDirectories(output.getParent());
        try (Connection snapshot = openStatisticsReader()) {
            // The first SELECT fixes a WAL snapshot shared by all export queries.
            snapshot.setAutoCommit(false);
            try (var out = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8);
                 Statement summaryStatement = snapshot.createStatement();
                 ResultSet summary = summaryStatement.executeQuery(
                         "SELECT COUNT(*),COALESCE(SUM(false_positive),0),COALESCE(SUM(debug_mode),0) FROM violations")) {
                summary.next();
                out.write("{\n  \"schemaVersion\": 1,\n  \"generatedAt\": ");
                out.write(jsonString(Instant.now().toString()));
                out.write(",\n  \"summary\": {\"detections\": ");
                out.write(Long.toString(summary.getLong(1)));
                out.write(", \"falsePositives\": ");
                out.write(Long.toString(summary.getLong(2)));
                out.write(", \"debugDetections\": ");
                out.write(Long.toString(summary.getLong(3)));
                out.write("},\n  \"detectors\": [");
                boolean first = true;
                try (Statement statement = snapshot.createStatement();
                     ResultSet rows = statement.executeQuery("SELECT detector,COUNT(*),SUM(false_positive),SUM(debug_mode),AVG(score),MAX(score),MAX(created_at) FROM violations GROUP BY detector ORDER BY detector COLLATE NOCASE")) {
                    while (rows.next()) {
                        if (!first) out.write(",");
                        first = false;
                        out.write("\n    {\"detector\":"); out.write(jsonString(rows.getString(1)));
                        out.write(",\"detections\":"); out.write(Long.toString(rows.getLong(2)));
                        out.write(",\"falsePositives\":"); out.write(Long.toString(rows.getLong(3)));
                        out.write(",\"debugDetections\":"); out.write(Long.toString(rows.getLong(4)));
                        out.write(",\"averageScore\":"); out.write(Double.toString(rows.getDouble(5)));
                        out.write(",\"maximumScore\":"); out.write(Double.toString(rows.getDouble(6)));
                        out.write(",\"lastDetectedAt\":"); out.write(Long.toString(rows.getLong(7)));
                        out.write("}");
                    }
                }
                out.write("\n  ],\n  \"records\": [");
                first = true;
                try (Statement statement = snapshot.createStatement();
                     ResultSet rows = statement.executeQuery("SELECT id,uuid,name,detector,score,detail,created_at,false_positive,debug_mode,metrics_json FROM violations ORDER BY created_at,id")) {
                    while (rows.next()) {
                        if (!first) out.write(",");
                        first = false;
                        Violation violation = readViolation(rows);
                        out.write("\n    {\"id\":"); out.write(Long.toString(violation.id()));
                        out.write(",\"uuid\":"); out.write(jsonString(violation.uuid().toString()));
                        out.write(",\"player\":"); out.write(jsonString(violation.name()));
                        out.write(",\"detector\":"); out.write(jsonString(violation.detector()));
                        out.write(",\"score\":"); out.write(Double.toString(violation.score()));
                        out.write(",\"detail\":"); out.write(jsonString(violation.detail()));
                        out.write(",\"metrics\":"); out.write(validJsonObject(violation.metricsJson()));
                        out.write(",\"falsePositive\":"); out.write(Boolean.toString(violation.falsePositive()));
                        out.write(",\"debugRecording\":"); out.write(Boolean.toString(violation.debugMode()));
                        out.write(",\"createdAt\":"); out.write(Long.toString(violation.createdAt()));
                        out.write("}");
                    }
                }
                out.write("\n  ]\n}\n");
            }
            snapshot.commit();
        } catch (SQLException | java.io.IOException failure) {
            try { Files.deleteIfExists(temporary); }
            catch (java.io.IOException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            throw failure;
        }
        try {
            try {
                Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, output);
            }
        } catch (java.io.IOException failure) {
            try { Files.deleteIfExists(temporary); }
            catch (java.io.IOException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            throw failure;
        }
        return output;
    }

    private void awaitPendingWrites() throws SQLException {
        try {
            long target;
            synchronized (writeGate) { target = nextWriteSequence; }
            writer.submit(() -> flushThrough(target)).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new SQLException("Timed out waiting for PAC database writes.", e);
        }
    }

    private static Violation readViolation(ResultSet rows) throws SQLException {
        return new Violation(rows.getLong(1), UUID.fromString(rows.getString(2)), rows.getString(3),
                rows.getString(4), rows.getDouble(5), rows.getString(6), rows.getLong(7),
                rows.getInt(8) != 0, rows.getInt(9) != 0, rows.getString(10));
    }

    private static Map<String, Double> extractNumericFields(String detail) {
        Map<String, Double> values = new LinkedHashMap<>();
        if (detail == null) return values;
        Matcher matcher = NUMBERED_FIELD.matcher(detail);
        while (matcher.find()) {
            try {
                double value = Double.parseDouble(matcher.group(2));
                if (Double.isFinite(value)) values.put(matcher.group(1), value);
            } catch (NumberFormatException ignored) { }
        }
        Matcher groups = NUMBER_GROUP.matcher(detail);
        while (groups.find()) {
            String key = groups.group(1).trim().replaceAll("\\s+", "_");
            Matcher tokens = NUMBER_TOKEN.matcher(groups.group(2));
            int index = 1;
            while (tokens.find()) {
                try {
                    double value = Double.parseDouble(tokens.group());
                    if (Double.isFinite(value)) values.put(key + "_" + index++, value);
                } catch (NumberFormatException ignored) { }
            }
        }
        return values;
    }

    private static String metricsJson(Map<String, Double> metrics) {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        List<Map.Entry<String, Double>> entries = metrics.entrySet().stream()
                .filter(entry -> entry.getKey() != null && entry.getValue() != null
                        && Double.isFinite(entry.getValue()))
                .sorted(Map.Entry.comparingByKey())
                .toList();
        for (var entry : entries) {
            if (!first) json.append(',');
            first = false;
            json.append(jsonString(entry.getKey())).append(':').append(entry.getValue());
        }
        return json.append('}').toString();
    }

    private static String validJsonObject(String json) {
        return json != null && json.startsWith("{") && json.endsWith("}") ? json : "{}";
    }

    private static String jsonString(String value) {
        if (value == null) return "null";
        StringBuilder escaped = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> { if (c < 0x20) escaped.append(String.format(Locale.ROOT, "\\u%04x", (int) c)); else escaped.append(c); }
            }
        }
        return escaped.append('"').toString();
    }

    @Override public void close() throws Exception {
        Future<?> flush;
        synchronized (writeGate) {
            if (closed) return;
            closed = true;
            long target = nextWriteSequence;
            flush = writer.submit(() -> flushThrough(target));
            writer.shutdown();
        }
        flush.get(30, TimeUnit.SECONDS);
        if (!writer.awaitTermination(5, TimeUnit.SECONDS))
            throw new SQLException("PAC database writer did not stop after flushing records");
        synchronized (connection) { connection.close(); }
    }
}

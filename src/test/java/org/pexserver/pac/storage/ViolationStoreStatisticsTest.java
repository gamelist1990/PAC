package org.pexserver.pac.storage;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViolationStoreStatisticsTest {
    @TempDir Path directory;

    @Test void existingDatabaseGainsOnlyMissingColumns() throws Exception {
        Path database = directory.resolve("pac.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE violations (id INTEGER PRIMARY KEY, uuid TEXT NOT NULL, name TEXT NOT NULL, detector TEXT NOT NULL, score REAL NOT NULL, detail TEXT NOT NULL, created_at INTEGER NOT NULL)");
            statement.execute("CREATE TABLE bans (uuid TEXT PRIMARY KEY, name TEXT NOT NULL, reason TEXT NOT NULL, created_at INTEGER NOT NULL)");
            statement.execute("CREATE TABLE enforcement (uuid TEXT PRIMARY KEY, name TEXT NOT NULL, stage INTEGER NOT NULL DEFAULT 0, trust REAL NOT NULL DEFAULT 100, day TEXT NOT NULL DEFAULT '', daily_risk REAL NOT NULL DEFAULT 0)");
        }
        try (ViolationStore store = new ViolationStore(plugin(directory), 1000)) {
            store.record(UUID.randomUUID(), "Alex", "motion-prediction", 1, "migrated");
            assertEquals(1, store.detectorStatistics().getFirst().detections());
        }
    }

    @Test void historyQueriesUseIndexesAfterSchemaMigration() throws Exception {
        Plugin plugin = plugin(directory);
        try (ViolationStore ignored = new ViolationStore(plugin, 1000);
             var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("pac.db"))) {
            assertQueryUsesIndex(connection,
                    "SELECT uuid FROM violations WHERE name=? COLLATE NOCASE "
                            + "ORDER BY created_at DESC,id DESC LIMIT 1",
                    "Alex", "violations_name_time");
            assertQueryUsesIndex(connection,
                    "SELECT id FROM violations WHERE uuid=? ORDER BY created_at DESC,id DESC LIMIT 10",
                    UUID.randomUUID().toString(), "violations_player_page");
            assertQueryUsesIndex(connection,
                    "SELECT id FROM violations WHERE detector=? ORDER BY created_at DESC,id DESC LIMIT 5 OFFSET 10",
                    "motion-prediction", "violations_detector_page");
            try (var statement = connection.prepareStatement(
                    "EXPLAIN QUERY PLAN SELECT id FROM violations ORDER BY created_at DESC,id DESC LIMIT 5 OFFSET 10");
                 var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertTrue(rows.getString("detail").contains("violations_recent_page"));
            }
        }
    }

    private static void assertQueryUsesIndex(java.sql.Connection connection, String query,
                                             String value, String index) throws Exception {
        try (var statement = connection.prepareStatement("EXPLAIN QUERY PLAN " + query)) {
            statement.setString(1, value);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertTrue(rows.getString("detail").contains(index), rows.getString("detail"));
            }
        }
    }

    @Test void queuedDetectionBurstIsDurableAcrossPluginShutdown() throws Exception {
        Plugin plugin = plugin(directory);
        UUID uuid = UUID.randomUUID();
        int burst = 10_000;
        try (ViolationStore store = new ViolationStore(plugin, 1000)) {
            for (int i = 0; i < burst; i++)
                store.record(uuid, "Alex", "motion-prediction", i, "offset=0.25");
        }
        try (ViolationStore reopened = new ViolationStore(plugin, 1000)) {
            assertEquals(burst, reopened.detectorStatistics().getFirst().detections());
        }
    }

    @Test void parallelDetectionsAreVisibleToStatisticsBeforeShutdown() throws Exception {
        Plugin plugin = plugin(directory);
        UUID uuid = UUID.randomUUID();
        try (ViolationStore store = new ViolationStore(plugin, 1000)) {
            IntStream.range(0, 4_000).parallel().forEach(i ->
                    store.record(uuid, "Alex", "motion-prediction", i, "offset=0.25"));
            assertEquals(4_000, store.detectorStatistics().getFirst().detections());
        }
    }

    @Test void individualDetectorAggregationReturnsOnlyItsOwnRecords() throws Exception {
        try (ViolationStore store = new ViolationStore(plugin(directory), 1000)) {
            UUID uuid = UUID.randomUUID();
            store.record(uuid, "Alex", "motion-prediction", 2, "movement");
            store.record(uuid, "Alex", "motion-prediction", 4, "movement");
            store.record(uuid, "Alex", "fast-break", 9, "mining");

            var stats = store.detectorStatistic("motion-prediction").orElseThrow();
            assertEquals(2, stats.detections());
            assertEquals(3, stats.averageScore());
            assertEquals(4, stats.maximumScore());
            assertTrue(store.detectorStatistic("missing").isEmpty());
        }
    }

    @Test void oneInvalidRecordCannotDiscardTheRestOfItsBatch() throws Exception {
        Plugin plugin = plugin(directory);
        UUID uuid = UUID.randomUUID();
        try (ViolationStore store = new ViolationStore(plugin, 1000)) {
            store.record(uuid, "Alex", "motion-prediction", 1, "valid before");
            store.record(uuid, null, "motion-prediction", 2, "invalid name");
            store.record(uuid, "Alex", "motion-prediction", 3, "valid after");
            assertEquals(2, store.detectorStatistics().getFirst().detections());
        }
    }

    @Test void clearingStatisticsDeletesViolationHistoryAndLeavesBanRecordsIntact() throws Exception {
        Plugin plugin = plugin(directory);
        UUID uuid = UUID.randomUUID();
        try (ViolationStore store = new ViolationStore(plugin, 1000)) {
            store.record(uuid, "Alex", "motion-prediction", 3, "offset=0.25");
            store.record(uuid, "Alex", "combat-reach", 2, "distance=3.2");
            store.ban(uuid, "Alex", "test ban");

            int removed = store.clearStatistics();

            assertEquals(2, removed);
            assertTrue(store.detectorStatistics().isEmpty());
            assertTrue(store.history(uuid, 10).isEmpty());
            assertEquals(1, store.bans().size());
        }
    }

    @Test void detectionsKeepNumericMetricsDebugStateAndFalsePositiveReviewInJsonExport() throws Exception {
        Plugin plugin = plugin(directory);
        UUID uuid = UUID.randomUUID();
        try (ViolationStore store = new ViolationStore(plugin, 1000)) {
            store.record(uuid, "A\"lex", "motion-prediction", 2,
                    "offset=0.125 horizontal=0.45", true, Map.of("offset", 0.125, "horizontal", 0.45));
            store.record(uuid, "Alex", "motion-prediction", 3,
                    "offset=0.025 displacement=(0.4, -0.2)", false, Map.of());

            var detector = store.detectorStatistics().getFirst();
            assertEquals(2, detector.detections());
            assertEquals(0, detector.falsePositives());
            assertEquals(1, detector.debugDetections());
            assertEquals(2.5, detector.averageScore(), 1.0e-12);

            var latest = store.recentViolations("motion-prediction", 10, 0).stream()
                    .filter(ViolationStore.Violation::debugMode).findFirst().orElseThrow();
            assertTrue(latest.metricsJson().contains("\"offset\":0.125"));
            assertTrue(latest.debugMode());
            var parsed = store.recentViolations("motion-prediction", 10, 0).stream()
                    .filter(v -> !v.debugMode()).findFirst().orElseThrow();
            assertTrue(parsed.metricsJson().contains("\"displacement_1\":0.4"));
            assertTrue(parsed.metricsJson().contains("\"displacement_2\":-0.2"));
            assertTrue(store.markFalsePositive(latest.id(), true));

            Path exported = store.exportStatistics();
            assertEquals(directory, exported.getParent());
            assertFalse(Files.exists(exported.resolveSibling(exported.getFileName() + ".tmp")));
            String json = Files.readString(exported);
            assertTrue(json.startsWith("{\n  \"schemaVersion\": 1,"));
            assertTrue(json.contains("\"falsePositives\":1"));
            assertTrue(json.contains("\"debugRecording\":true"));
            assertTrue(json.contains("\"player\":\"A\\\"lex\""));
            assertTrue(json.contains("\"metrics\":{\"horizontal\":0.45,\"offset\":0.125}"));
            assertFalse(json.contains("\"metrics\":\"{"));
        }
    }

    @Test void storedNameHistoryResolvesTheMostRecentPlayerWithoutBukkitProfileLookup() throws Exception {
        Plugin plugin = plugin(directory);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        try (ViolationStore store = new ViolationStore(plugin, 1000)) {
            store.record(first, "Alex", "motion-prediction", 1, "first player");
            store.record(second, "ALEX", "fast-break", 2, "second player");
            var records = store.history("alex", 10);
            assertEquals(1, records.size());
            assertEquals(second, records.getFirst().uuid());
            assertEquals(1, store.history(first, 10).size());
            assertEquals(1, store.history(first.toString(), 10).size());
            assertTrue(store.history("unknown", 10).isEmpty());
        }
    }

    @Test void historyPagesAllPlayersAndOneIdentityWithoutLoadingWholeLog() throws Exception {
        UUID alex = UUID.randomUUID();
        UUID sam = UUID.randomUUID();
        try (ViolationStore store = new ViolationStore(plugin(directory), 1000)) {
            for (int i = 0; i < 12; i++) {
                UUID uuid = i % 2 == 0 ? alex : sam;
                store.record(uuid, i % 2 == 0 ? "Alex" : "Sam",
                        "motion-prediction", i, "sample=" + i);
            }
            var first = store.historyPage(null, 1, 5);
            var last = store.historyPage(null, 3, 5);
            assertEquals(12, first.total());
            assertEquals(3, first.pages());
            assertEquals(5, first.rows().size());
            assertEquals(11, first.rows().getFirst().score());
            assertEquals(2, last.rows().size());
            assertEquals(0, last.rows().getLast().score());

            var named = store.historyPage("alex", 2, 5);
            assertEquals(6, named.total());
            assertEquals(2, named.pages());
            assertEquals(1, named.rows().size());
            assertEquals(alex, named.rows().getFirst().uuid());
            assertEquals(6, store.historyPage(alex.toString(), 1, 5).total());
            assertTrue(store.historyPage("missing", 1, 5).rows().isEmpty());
        }
    }

    @Test void expiredBanDoesNotHideANewBanAndNameLookupHandlesAmbiguity() throws Exception {
        Plugin plugin = plugin(directory);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        try (ViolationStore store = new ViolationStore(plugin, 1000)) {
            store.applyBan(first, "Alex", "old", System.currentTimeMillis() - 1, false, 1);
            assertEquals(null, store.banOf(first));
            assertEquals(0, store.activeBanCount());
            store.applyBan(first, "Alex", "renewed", 0, true, 2);
            assertEquals("renewed", store.banOf(first).reason());
            assertEquals(1, store.activeBanCount());
            store.applyBan(second, "Alexa", "other", 0, true, 1);
            assertEquals(first, store.findBan("alex").orElseThrow().uuid());
            assertTrue(store.findBan("ale").isEmpty());
            assertEquals(second, store.findBan("alexa").orElseThrow().uuid());
        }
        try (ViolationStore reopened = new ViolationStore(plugin, 1000)) {
            assertEquals("renewed", reopened.banOf(first).reason(),
                    "queued expiry cleanup must not delete the replacement ban");
        }
    }

    @Test void expiredBanCleanupPersistsAfterWriterDrain() throws Exception {
        Plugin plugin = plugin(directory);
        UUID uuid = UUID.randomUUID();
        try (ViolationStore store = new ViolationStore(plugin, 1000)) {
            store.applyBan(uuid, "Alex", "expired", System.currentTimeMillis() - 1, false, 1);
            assertEquals(null, store.banOf(uuid));
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("pac.db"));
             var statement = connection.prepareStatement("SELECT COUNT(*) FROM bans WHERE uuid=?")) {
            statement.setString(1, uuid.toString());
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt(1));
            }
        }
    }

    @Test void unbanWithTrustResetUpdatesBothRecords() throws Exception {
        Plugin plugin = plugin(directory);
        UUID uuid = UUID.randomUUID();
        String day = "2026-09-27";
        try (ViolationStore store = new ViolationStore(plugin, 1000)) {
            store.applyBan(uuid, "Alex", "test", 0, true, 2);
            store.enforcement(uuid, "Alex", day);
            store.saveEnforcement(new ViolationStore.Enforcement(
                    uuid, "Alex", 2, 35, day, 4, 12345));

            ViolationStore.Ban selected = store.banOf(uuid);
            assertTrue(store.unbanIfCurrent(selected, true));
            assertEquals(null, store.banOf(uuid));
            var reset = store.enforcement(uuid, "Alex", day);
            assertEquals(0, reset.stage());
            assertEquals(100, reset.trust());
            assertEquals(0, reset.dailyRisk());
            assertEquals(0, reset.lastViolationAt());
            assertFalse(store.unbanIfCurrent(selected, true));

            ViolationStore.Ban newBan = store.applyBan(uuid, "Alex", "new automatic ban", 0, true, 3);
            assertFalse(store.isCurrentBan(selected));
            assertTrue(store.isCurrentBan(newBan));
            assertFalse(store.unbanIfCurrent(selected, false));
            assertEquals("new automatic ban", store.banOf(uuid).reason());
            assertTrue(store.unbanIfCurrent(store.banOf(uuid), false));
            assertFalse(store.isCurrentBan(newBan));
        }
    }

    private static Plugin plugin(Path dataFolder) {
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getDataFolder" -> dataFolder.toFile();
                    case "getLogger" -> Logger.getLogger("PAC-test");
                    case "getName" -> "PAC";
                    case "toString" -> "PAC-test-plugin";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }
}

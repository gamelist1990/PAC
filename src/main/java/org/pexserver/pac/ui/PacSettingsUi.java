package org.pexserver.pac.ui;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.pexserver.pac.PacPlugin;
import org.pexserver.pac.check.core.CheckModule;
import org.pexserver.pac.storage.ViolationStore;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;

/** Category list with left-side switches and right-side per-detector settings. */
public final class PacSettingsUi implements Listener {
    private static final String[] CATEGORIES = {"移動", "Combat", "エクスプロイト", "Other"};
    private static final int[] THRESHOLD_SLOTS = {10, 12, 14, 16, 28, 30, 32, 34};
    private record NumberSetting(String name, String suffix, double fallback, double min, double max, boolean integer) { }
    private record Holder(int category, int page, String key, boolean thresholds) implements InventoryHolder {
        @Override public Inventory getInventory() { throw new UnsupportedOperationException(); }
    }
    private record StatisticsHolder(String detector, int page, List<Long> violationIds,
                                    List<Boolean> falsePositiveStates) implements InventoryHolder {
        private StatisticsHolder {
            violationIds = List.copyOf(violationIds);
            falsePositiveStates = List.copyOf(falsePositiveStates);
        }
        @Override public Inventory getInventory() { throw new UnsupportedOperationException(); }
    }
    private record ClearStatisticsHolder() implements InventoryHolder {
        @Override public Inventory getInventory() { throw new UnsupportedOperationException(); }
    }
    private record HistoryHolder(String playerName, int page, boolean fromStatistics,
                                 List<String> playerNames) implements InventoryHolder {
        private HistoryHolder { playerNames = List.copyOf(playerNames); }
        @Override public Inventory getInventory() { throw new UnsupportedOperationException(); }
    }
    private record BanListHolder(int page) implements InventoryHolder {
        @Override public Inventory getInventory() { throw new UnsupportedOperationException(); }
    }
    private final PacPlugin plugin;
    /** Main-thread generation: a closed or superseded statistics request must not reopen the UI. */
    private final Map<UUID, Long> statisticsRequests = new HashMap<>();
    private long nextRequestId;
    public PacSettingsUi(PacPlugin plugin) { this.plugin = plugin; }
    public void open(Player player) { list(player, 0, 0); }

    private long nextStatisticsRequest(Player player) {
        long request = ++nextRequestId;
        statisticsRequests.put(player.getUniqueId(), request);
        return request;
    }

    private boolean currentStatisticsRequest(Player player, long request) {
        return player.isOnline() && statisticsRequests.getOrDefault(player.getUniqueId(), 0L) == request;
    }

    @EventHandler public void onClose(InventoryCloseEvent event) {
        InventoryHolder holder = event.getInventory().getHolder(false);
        if (holder instanceof Holder || holder instanceof StatisticsHolder
                || holder instanceof ClearStatisticsHolder || holder instanceof HistoryHolder
                || holder instanceof BanListHolder) {
            statisticsRequests.remove(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        statisticsRequests.remove(event.getPlayer().getUniqueId());
    }

    private void list(Player player, int category, int requestedPage) {
        List<CheckModule> modules = modules(category);
        int maxPage = Math.max(0, (modules.size() - 1) / 5);
        int page = Math.max(0, Math.min(requestedPage, maxPage));
        Inventory inv = inventory(new Holder(category, page, null, false), 54,
                "PAC  •  " + CATEGORIES[category] + " の検知");
        for (int c = 0; c < CATEGORIES.length; c++) {
            boolean selected = c == category;
            inv.setItem(1 + 2 * c, item(selected ? Material.LIME_DYE : Material.BOOK,
                    selected ? NamedTextColor.GREEN : NamedTextColor.YELLOW,
                    CATEGORIES[c] + (selected ? "  ✓" : ""),
                    selected ? "表示中のカテゴリ" : "クリックしてカテゴリを切り替える"));
        }
        inv.setItem(8, item(Material.COMPARATOR, "全体設定",
                "通知・記録・制裁を設定", "クリックして開く"));
        for (int row = 0; row < 5; row++) {
            int index = page * 5 + row;
            if (index >= modules.size()) break;
            CheckModule module = modules.get(index);
            boolean enabled = plugin.enabled(module);
            inv.setItem(10 + row * 9, item(enabled ? Material.LIME_DYE : Material.GRAY_DYE,
                    enabled ? NamedTextColor.GREEN : NamedTextColor.RED,
                    enabled ? "有効  •  クリックで無効" : "無効  •  クリックで有効",
                    "対象: " + module.key()));
            inv.setItem(13 + row * 9, item(Material.PAPER, NamedTextColor.WHITE,
                    module.key(),
                    "検知: " + onOff(enabled),
                    "左: 検知を切り替える",
                    "右: 詳細設定を開く"));
            inv.setItem(16 + row * 9, item(Material.COMPARATOR, "詳細設定  →",
                    "自動制裁・ロールバック・閾値", "対象: " + module.key()));
        }
        if (page > 0) inv.setItem(45, item(Material.ARROW, "← 前へ", "前の5件"));
        inv.setItem(47, item(Material.BOOK, "検知統計", "記録数・誤検知を確認"));
        inv.setItem(49, item(Material.PAPER, "ページ  " + (page + 1) + " / " + (maxPage + 1),
                modules.size() + "件の検知"));
        inv.setItem(51, item(Material.WRITABLE_BOOK, "検知履歴", "全プレイヤーの最新記録"));
        if (page < maxPage) inv.setItem(53, item(Material.ARROW, "次へ →", "次の5件"));
        player.openInventory(inv);
    }

    private void detail(Player player, Holder holder, CheckModule module) {
        Inventory inv = inventory(holder, 54, "PAC  •  " + module.key());
        String base = "detectors." + module.key();
        inv.setItem(4, item(Material.PAPER, NamedTextColor.WHITE, module.key(),
                "カテゴリ: " + CATEGORIES[holder.category()],
                "検知と制裁の動作を設定"));
        inv.setItem(10, item(plugin.enabled(module) ? Material.LIME_DYE : Material.GRAY_DYE,
                "検知  •  " + onOff(plugin.enabled(module)),
                "クリックで有効/無効を切り替える"));
        inv.setItem(12, item(Material.LEVER,
                "違反動作のキャンセル",
            "この検知: " + onOff(plugin.moduleCancelEnabled(module)),
            "全体ロールバック: " + onOff(plugin.rollbackEnabled()),
            "クリックで切り替える"));
        inv.setItem(14, item(module.automaticBanEligible() ? Material.NETHERITE_SWORD : Material.BARRIER,
                "自動BAN",
                module.automaticBanEligible() ? "状態: " + onOff(plugin.getConfig().getBoolean(base + ".ban-enabled", true))
                        : "この検知は自動BAN対象外",
                module.automaticBanEligible() ? "クリックで切り替える" : ""));
        inv.setItem(16, item(module.automaticKickEligible() ? Material.IRON_BOOTS : Material.BARRIER,
                "自動Kick",
                module.automaticKickEligible() ? "状態: " + onOff(plugin.getConfig().getBoolean(base + ".kick-enabled", false))
                        : "この検知は自動Kick対象外",
                module.automaticKickEligible() ? "クリックで切り替える" : ""));
        inv.setItem(20, item(Material.BELL, "通知を出すScore",
            "現在値: " + current(base + ".alert-score-threshold", 1),
            "クリックで数値を変更する"));
        inv.setItem(22, item(module.automaticKickEligible() ? Material.REDSTONE : Material.BARRIER,
                "Kickを行うScore",
                module.automaticKickEligible() ? "現在値: " + current(base + ".kick-score-threshold", 10)
                        : "この検知は自動Kick対象外",
                module.automaticKickEligible() ? "クリックで数値を変更する" : ""));
        inv.setItem(24, item(numbers(module.key()).isEmpty() ? Material.BARRIER : Material.REPEATER,
                "検知固有の閾値",
                numbers(module.key()).isEmpty() ? "変更できる閾値はありません" : "速度・許容差などを設定",
                numbers(module.key()).isEmpty() ? "" : "クリックして開く"));
        inv.setItem(49, item(Material.ARROW, "← 検知一覧へ", "元のページに戻る"));
        player.openInventory(inv);
    }

    private void thresholds(Player player, Holder holder, CheckModule module) {
        Inventory inv = inventory(holder, 54, "PAC  •  " + module.key() + " の閾値");
        inv.setItem(4, item(Material.REPEATER, NamedTextColor.WHITE,
                "検知固有の閾値", "対象: " + module.key(), "クリックで数値を変更"));
        List<NumberSetting> settings = numbers(module.key());
        for (int i = 0; i < settings.size(); i++) {
            NumberSetting s = settings.get(i);
            inv.setItem(THRESHOLD_SLOTS[i], item(Material.REDSTONE, s.name(),
                    "現在値: " + current("detectors." + module.key() + "." + s.suffix(), s.fallback()),
                    "入力範囲: " + s.min() + " ～ " + s.max(),
                    "クリックで変更"));
        }
        if (settings.isEmpty()) inv.setItem(22, item(Material.PAPER, "変更できる閾値はありません", "他の設定は詳細画面で変更できます"));
        inv.setItem(49, item(Material.ARROW, "← 詳細へ", "前の画面に戻る"));
        player.openInventory(inv);
    }

    private void global(Player player) {
        Inventory inv = inventory(new Holder(0, 0, null, true), 54, "PAC  •  全体設定");
        inv.setItem(4, item(Material.COMPARATOR, NamedTextColor.WHITE, "全体設定",
                "通知・補正・制裁・記録", "項目をクリックして変更"));
        inv.setItem(10, toggleItem("検知通知", "alerts.enabled", "スタッフへのゲーム内通知"));
        inv.setItem(12, toggleItem("ロールバック", "punishments.rollback-enabled",
                "違反移動のキャンセルと位置補正"));
        inv.setItem(14, toggleItem("自動制裁", "punishments.probation-enabled",
                "Scoreによる自動BANの判定"));
        inv.setItem(16, item(plugin.isDebugRecording() ? Material.LIME_DYE : Material.GRAY_DYE,
                "Debug記録  •  " + onOff(plugin.isDebugRecording()),
                "検知と位置補正は継続",
                "自動Kick・BANは停止",
                "クリックで切り替える"));
        inv.setItem(19, item(Material.REDSTONE, "制裁判定Score",
                "現在値: " + current("punishments.score-threshold", 25), "クリックで変更"));
        inv.setItem(21, item(Material.SCAFFOLDING, "日次リスク上限",
                "現在値: " + current("punishments.daily-risk-allowance", 3), "クリックで変更"));
        inv.setItem(23, item(Material.WITHER_ROSE, "信頼低下量",
                "現在値: " + current("punishments.trust-loss-per-action", 20), "クリックで変更"));
        inv.setItem(28, item(Material.REDSTONE, "連続Score倍率",
                "現在値: " + current("scoring.repeat-multiplier", 1.5),
                "短時間に再検知した時の倍率", "クリックで変更"));
        inv.setItem(30, item(Material.CLOCK, "Score半減期",
                "現在値: " + current("scoring.half-life-seconds", 30) + " 秒",
                "検知が止まるとScoreが減衰", "クリックで変更"));
        inv.setItem(37, item(Material.BOOK, "検知統計", "検知数と誤検知を表示"));
        inv.setItem(39, item(Material.WRITABLE_BOOK, "検知履歴", "全プレイヤーの最新記録"));
        inv.setItem(41, item(Material.PLAYER_HEAD, "BANリスト",
                "現在 " + plugin.store().activeBanCount() + "件", "クリックして確認"));
        inv.setItem(43, item(Material.CHEST, "統計をJSON出力",
                "DBの統計をplugins/PACへ保存", "クリックして出力"));
        inv.setItem(49, item(Material.ARROW, "← 検知一覧へ", "前の画面に戻る"));
        player.openInventory(inv);
    }

    @EventHandler public void onClick(InventoryClickEvent event) {
        if (event.getInventory().getHolder(false) instanceof BanListHolder bans) {
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player) || !player.hasPermission("pac.settings")) return;
            int slot = event.getRawSlot();
            if (slot == 49) global(player);
            else if (slot == 45 && bans.page() > 0) banList(player, bans.page() - 1);
            else if (slot == 53 && event.getCurrentItem() != null
                    && event.getCurrentItem().getType() == Material.ARROW)
                banList(player, bans.page() + 1);
            return;
        }
        if (event.getInventory().getHolder(false) instanceof HistoryHolder history) {
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player) || !player.hasPermission("pac.settings")) return;
            int slot = event.getRawSlot();
            if (slot < 0 || slot >= event.getInventory().getSize()) return;
            if (slot == 45 && history.page() > 1) {
                history(player, history.page() - 1, history.playerName(), history.fromStatistics());
            } else if (slot == 53 && event.getCurrentItem() != null
                    && event.getCurrentItem().getType() == Material.ARROW) {
                history(player, history.page() + 1, history.playerName(), history.fromStatistics());
            } else if (slot == 47) {
                if (history.fromStatistics()) statistics(player, 0); else global(player);
            } else if (slot == 49 && history.playerName() != null) {
                history(player, 1, null, history.fromStatistics());
            } else if (history.playerName() == null) {
                for (int row = 0; row < history.playerNames().size(); row++) {
                    if (slot == 16 + row * 9) {
                        history(player, 1, history.playerNames().get(row), history.fromStatistics());
                        return;
                    }
                }
            }
            return;
        }
        if (event.getInventory().getHolder(false) instanceof ClearStatisticsHolder) {
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player) || !player.hasPermission("pac.settings")) return;
            int slot = event.getRawSlot();
            if (slot == 11) clearStatistics(player);
            else if (slot == 15) statistics(player, 0);
            return;
        }
        if (event.getInventory().getHolder(false) instanceof StatisticsHolder stats) {
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player) || !player.hasPermission("pac.settings")) return;
            statisticsClick(player, stats, event.getRawSlot());
            return;
        }
        if (!(event.getInventory().getHolder(false) instanceof Holder h)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !player.hasPermission("pac.settings")) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getInventory().getSize()) return;
        if (h.key() == null && !h.thresholds()) {
            for (int c = 0; c < CATEGORIES.length; c++) if (slot == 1 + 2 * c) { list(player, c, 0); return; }
            if (slot == 8) { global(player); return; }
            if (slot == 47) { statistics(player, 0); return; }
            if (slot == 51) { history(player, 1, null, false); return; }
            if (slot == 45 && h.page() > 0 || slot == 53 && event.getCurrentItem() != null
                    && event.getCurrentItem().getType() == Material.ARROW) {
                list(player, h.category(), h.page() + (slot == 45 ? -1 : 1));
                return;
            }
            for (int row = 0; row < 5; row++) {
                int index = h.page() * 5 + row;
                List<CheckModule> modules = modules(h.category());
                if (index >= modules.size()) break;
                CheckModule module = modules.get(index);
                if (slot == 10 + row * 9) { plugin.setEnabled(module, !plugin.enabled(module)); list(player, h.category(), h.page()); return; }
                if (slot == 16 + row * 9) { detail(player, new Holder(h.category(), h.page(), module.key(), false), module); return; }
            }
            return;
        }
        if (h.key() == null) { globalClick(player, slot); return; }
        CheckModule module = plugin.checks().get(h.key());
        if (module == null) { open(player); return; }
        String base = "detectors." + module.key();
        Holder detailHolder = new Holder(h.category(), h.page(), h.key(), false);
        Runnable back = () -> detail(player, detailHolder, module);
        if (h.thresholds()) {
            if (slot == 49) { back.run(); return; }
            List<NumberSetting> settings = numbers(module.key());
            for (int index = 0; index < settings.size(); index++) {
                if (slot != THRESHOLD_SLOTS[index]) continue;
                NumberSetting s = settings.get(index);
                numberDialog(player, s, base + "." + s.suffix(), () -> thresholds(player, h, module));
                return;
            }
            return;
        }
        switch (slot) {
            case 10 -> { plugin.setEnabled(module, !plugin.enabled(module)); back.run(); }
            case 12 -> {
                plugin.getConfig().set(base + ".cancel", !plugin.moduleCancelEnabled(module));
                plugin.saveConfig(); plugin.refreshSettings(); back.run(); }
                case 20 -> numberDialog(player,
                    new NumberSetting("アラートScore", "", 1, 0.1, 10000, false),
                    base + ".alert-score-threshold", back);
            case 14 -> { if (module.automaticBanEligible()) {
                plugin.getConfig().set(base + ".ban-enabled",
                        !plugin.getConfig().getBoolean(base + ".ban-enabled", true));
                plugin.saveConfig(); plugin.refreshSettings(); back.run(); } }
            case 16 -> { if (module.automaticKickEligible()) { toggle(base + ".kick-enabled"); back.run(); } }
            case 22 -> { if (module.automaticKickEligible())
                numberDialog(player, new NumberSetting("Kick Score", "", 10, 0.1, 10000, false), base + ".kick-score-threshold", back); }
            case 24 -> { if (!numbers(module.key()).isEmpty())
                thresholds(player, new Holder(h.category(), h.page(), h.key(), true), module); }
            case 49 -> list(player, h.category(), h.page());
            default -> { }
        }
    }

    private void globalClick(Player player, int slot) {
        switch (slot) {
            case 10 -> { toggle("alerts.enabled"); global(player); }
            case 12 -> { toggle("punishments.rollback-enabled"); global(player); }
            case 14 -> { toggle("punishments.probation-enabled"); global(player); }
            case 16 -> { plugin.setDebugRecording(!plugin.isDebugRecording()); global(player); }
            case 19 -> numberDialog(player, new NumberSetting("制裁判定Score", "", 25, 0.1, 10000, false), "punishments.score-threshold", () -> global(player));
            case 21 -> numberDialog(player, new NumberSetting("日次リスク上限", "", 3, 0.1, 100, false), "punishments.daily-risk-allowance", () -> global(player));
            case 23 -> numberDialog(player, new NumberSetting("信頼低下", "", 20, 0.1, 100, false), "punishments.trust-loss-per-action", () -> global(player));
            case 28 -> numberDialog(player, new NumberSetting("連続Score倍率", "", 1.5, 1, 3, false),
                    "scoring.repeat-multiplier", () -> global(player));
            case 30 -> numberDialog(player, new NumberSetting("Score半減期（秒）", "", 30, 5, 3600, true),
                    "scoring.half-life-seconds", () -> global(player));
            case 37 -> statistics(player, 0);
            case 39 -> history(player, 1, null, false);
            case 41 -> banList(player, 0);
            case 43 -> { plugin.exportStatistics(player); player.sendMessage(plugin.prefix() + "統計JSONを出力しています。"); }
            case 49 -> open(player);
            default -> { }
        }
    }

    private void banList(Player player, int requestedPage) {
        List<ViolationStore.Ban> bans = plugin.store().bans();
        int maxPage = Math.max(0, (bans.size() - 1) / 5);
        int page = Math.max(0, Math.min(requestedPage, maxPage));
        Inventory inv = inventory(new BanListHolder(page), 54, "PAC  •  BANリスト");
        inv.setItem(4, item(Material.PLAYER_HEAD, NamedTextColor.WHITE,
                "現在のBAN: " + bans.size() + "件", "新しい順に表示",
                "解除は /pac unban <名前|UUID>"));
        if (bans.isEmpty()) inv.setItem(22, item(Material.PAPER,
                "現在のBANはありません", "新しいBANはここに表示されます"));
        for (int row = 0; row < 5; row++) {
            int index = page * 5 + row;
            if (index >= bans.size()) break;
            ViolationStore.Ban ban = bans.get(index);
            String expires = ban.permanent() ? "永久" : java.time.format.DateTimeFormatter
                    .ofPattern("yyyy/MM/dd HH:mm").withZone(java.time.ZoneId.systemDefault())
                    .format(java.time.Instant.ofEpochMilli(ban.expiresAt()));
            inv.setItem(10 + row * 9, item(Material.PLAYER_HEAD, NamedTextColor.RED,
                    ban.name(), "段階: " + ban.stage(), "期限: " + expires,
                    "理由: " + abbreviate(ban.reason(), 95)));
            inv.setItem(16 + row * 9, item(Material.PAPER, "識別情報",
                    "UUID: " + ban.uuid(), "解除: /pac unban " + ban.name()));
        }
        if (page > 0) inv.setItem(45, item(Material.ARROW, "← 前へ", "前の5件"));
        inv.setItem(49, item(Material.ARROW, "← 全体設定へ", "ページ " + (page + 1) + " / " + (maxPage + 1)));
        if (page < maxPage) inv.setItem(53, item(Material.ARROW, "次へ →", "次の5件"));
        player.openInventory(inv);
    }

    private void history(Player player, int page, String playerName, boolean fromStatistics) {
        long request = nextStatisticsRequest(player);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                ViolationStore.HistoryPage result = plugin.store().historyPage(playerName, page, 5);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (currentStatisticsRequest(player, request))
                        renderHistory(player, playerName, fromStatistics, result);
                });
            } catch (java.sql.SQLException exception) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (currentStatisticsRequest(player, request))
                        player.sendMessage(plugin.prefix() + "履歴DBを読み込めませんでした: " + exception.getMessage());
                });
            }
        });
    }

    private void renderHistory(Player player, String playerName, boolean fromStatistics,
                               ViolationStore.HistoryPage page) {
        List<String> names = page.rows().stream().map(ViolationStore.Violation::name).toList();
        Inventory inv = inventory(new HistoryHolder(playerName, page.page(), fromStatistics, names),
                54, "PAC  •  " + (playerName == null ? "全員の検知履歴" : playerName + " の履歴"));
        inv.setItem(4, item(Material.BOOK, NamedTextColor.WHITE,
                playerName == null ? "全員の検知履歴" : playerName + " の検知履歴",
                "合計: " + page.total() + "件",
                "ページ: " + page.page() + " / " + page.pages(),
                playerName == null ? "右側の頭アイコンでプレイヤーを絞り込み" : "下のコンパスで絞り込みを解除"));
        if (page.rows().isEmpty()) inv.setItem(22, item(Material.PAPER,
                "履歴はありません", "新しい検知が記録されると表示されます"));
        for (int row = 0; row < page.rows().size(); row++) {
            ViolationStore.Violation violation = page.rows().get(row);
            String date = java.time.format.DateTimeFormatter.ofPattern("MM/dd HH:mm:ss")
                    .withZone(java.time.ZoneId.systemDefault())
                    .format(java.time.Instant.ofEpochMilli(violation.createdAt()));
            inv.setItem(10 + row * 9, item(Material.PAPER, NamedTextColor.WHITE,
                    violation.name() + "  »  " + violation.detector(),
                    "#" + violation.id() + "  •  " + date,
                    "Score: " + String.format(java.util.Locale.ROOT, "%.2f", violation.score()),
                    (violation.debugMode() ? "記録モード  •  " : "")
                            + (violation.falsePositive() ? "誤検知確認済み" : "検知記録"),
                    "詳細: " + abbreviate(violation.detail(), 90),
                    "数値: " + abbreviate(violation.metricsJson(), 90)));
            if (playerName == null) inv.setItem(16 + row * 9,
                    item(Material.PLAYER_HEAD, violation.name() + " の履歴  →",
                            "このプレイヤーだけ表示"));
        }
        if (page.page() > 1) inv.setItem(45, item(Material.ARROW, "← 前へ", "前の5件"));
        inv.setItem(47, item(Material.ARROW, fromStatistics ? "← 統計へ" : "← 全体設定へ",
                "前の画面に戻る"));
        if (playerName != null) inv.setItem(49, item(Material.COMPASS, "全員の履歴",
                "プレイヤーの絞り込みを解除"));
        else inv.setItem(49, item(Material.PAPER,
                "ページ  " + page.page() + " / " + page.pages(), "全員の検知履歴"));
        if (page.page() < page.pages()) inv.setItem(53, item(Material.ARROW, "次へ →", "次の5件"));
        player.openInventory(inv);
    }

    private void statistics(Player player, int requestedPage) {
        long request = nextStatisticsRequest(player);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                List<ViolationStore.DetectorStatistics> stored = plugin.store().detectorStatistics();
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (currentStatisticsRequest(player, request))
                        renderStatistics(player, requestedPage, stored);
                });
            } catch (java.sql.SQLException exception) {
                Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(
                        plugin.prefix() + "統計DBを読み込めませんでした: " + exception.getMessage()));
            }
        });
    }

    private void renderStatistics(Player player, int requestedPage,
                                  List<ViolationStore.DetectorStatistics> stored) {
            var byDetector = stored.stream().collect(java.util.stream.Collectors.toMap(
                    ViolationStore.DetectorStatistics::detector, value -> value));
            List<CheckModule> modules = plugin.checks().modules().stream()
                    .sorted(java.util.Comparator.comparing(CheckModule::key)).toList();
            int maxPage = Math.max(0, (modules.size() - 1) / 5);
            int page = Math.max(0, Math.min(requestedPage, maxPage));
            Inventory inv = inventory(new StatisticsHolder(null, page, List.of(), List.of()), 54,
                    "PAC  •  検知統計");
            long total = stored.stream().mapToLong(ViolationStore.DetectorStatistics::detections).sum();
            long falsePositives = stored.stream().mapToLong(ViolationStore.DetectorStatistics::falsePositives).sum();
            inv.setItem(1, item(Material.PAPER, "総検知", total + "件", "全検知の合計"));
            inv.setItem(3, item(Material.RED_DYE, "確認済み誤検知", falsePositives + "件",
                    "検知記録に付けたラベル"));
            inv.setItem(5, item(Material.BOOK, "全員の検知履歴", "新しい順に表示", "プレイヤーで絞り込み可能"));
            inv.setItem(7, item(Material.WRITABLE_BOOK, "JSONを出力", "DBの検知履歴と数値を保存"));
            inv.setItem(8, item(Material.ARROW, "← 全体設定", "前の画面に戻る"));
            for (int row = 0; row < 5; row++) {
                int index = page * 5 + row;
                if (index >= modules.size()) break;
                CheckModule module = modules.get(index);
                ViolationStore.DetectorStatistics stats = byDetector.get(module.key());
                long detections = stats == null ? 0 : stats.detections();
                long fps = stats == null ? 0 : stats.falsePositives();
                long debug = stats == null ? 0 : stats.debugDetections();
                double average = stats == null ? 0 : stats.averageScore();
                double maximum = stats == null ? 0 : stats.maximumScore();
                double fpRate = detections == 0 ? 0 : (double) fps * 100 / detections;
                inv.setItem(10 + row * 9, item(detections == 0 ? Material.BOOK : Material.ENCHANTED_BOOK,
                        module.key(), "検知: " + detections + "件  /  誤検知: " + fps + "件 ("
                                + String.format(java.util.Locale.ROOT, "%.1f", fpRate) + "%)",
                        "記録モード: " + debug + "件",
                        "Score 平均: " + String.format(java.util.Locale.ROOT, "%.2f", average)
                                + "  /  最大: " + String.format(java.util.Locale.ROOT, "%.2f", maximum),
                        "クリックで最近の検知を表示"));
                inv.setItem(16 + row * 9, item(Material.COMPARATOR, "最近の検知  →",
                        "対象: " + module.key(), "誤検知ラベルもここで確認"));
            }
            if (page > 0) inv.setItem(45, item(Material.ARROW, "← 前へ", "前の5件"));
            inv.setItem(47, item(Material.TNT, NamedTextColor.RED,
                    "統計履歴を削除", "確認画面を開く", "BAN履歴は削除されません"));
            inv.setItem(49, item(Material.PAPER, "ページ  " + (page + 1) + " / " + (maxPage + 1),
                    "検知モジュール別の集計"));
            if (page < maxPage) inv.setItem(53, item(Material.ARROW, "次へ →", "次の5件"));
            player.openInventory(inv);
    }

    private void detectorStatistics(Player player, String detector, int requestedPage) {
        long request = nextStatisticsRequest(player);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                ViolationStore.DetectorStatistics stats = plugin.store().detectorStatistic(detector).orElse(null);
                int maxPage = stats == null ? 0 : (int) Math.min(Integer.MAX_VALUE / 5,
                        Math.max(0, (stats.detections() - 1) / 5));
                int page = Math.max(0, Math.min(requestedPage, maxPage));
                List<ViolationStore.Violation> rows = plugin.store().recentViolations(detector, 5, page * 5);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (currentStatisticsRequest(player, request))
                        renderDetectorStatistics(player, detector, page, stats, rows);
                });
            } catch (java.sql.SQLException exception) {
                Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(
                        plugin.prefix() + "統計DBを読み込めませんでした: " + exception.getMessage()));
            }
        });
    }

    private void renderDetectorStatistics(Player player, String detector, int page,
                                          ViolationStore.DetectorStatistics stats,
                                          List<ViolationStore.Violation> rows) {
            List<Long> ids = new java.util.ArrayList<>(rows.stream().map(ViolationStore.Violation::id).toList());
            List<Boolean> falsePositiveStates = new java.util.ArrayList<>(rows.stream()
                    .map(ViolationStore.Violation::falsePositive).toList());
            while (ids.size() < 5) ids.add(0L);
            while (falsePositiveStates.size() < 5) falsePositiveStates.add(false);
            Inventory inv = inventory(new StatisticsHolder(detector, page, ids, falsePositiveStates), 54,
                    "PAC  •  " + detector + " の統計");
            inv.setItem(1, item(Material.ENCHANTED_BOOK, detector,
                    "検知: " + (stats == null ? 0 : stats.detections()) + "件",
                    "記録モード: " + (stats == null ? 0 : stats.debugDetections()) + "件"));
            inv.setItem(3, item(Material.RED_DYE, "確認済み誤検知",
                    (stats == null ? 0 : stats.falsePositives()) + "件"));
            inv.setItem(5, item(Material.WRITABLE_BOOK, "JSONを出力", "全検知履歴と数値を保存"));
            inv.setItem(8, item(Material.ARROW, "← 統計一覧", "前の画面に戻る"));
            for (int row = 0; row < 5; row++) {
                if (row >= rows.size()) continue;
                ViolationStore.Violation violation = rows.get(row);
                String details = abbreviate(violation.detail(), 95);
                String numeric = abbreviate(violation.metricsJson(), 95);
                inv.setItem(10 + row * 9, item(Material.PAPER,
                        violation.name() + " / Score " + String.format(java.util.Locale.ROOT, "%.2f", violation.score()),
                        "#" + violation.id() + " | " + java.time.Instant.ofEpochMilli(violation.createdAt())
                                + (violation.debugMode() ? " | 記録モード" : ""),
                        "詳細: " + details,
                        "数値: " + numeric));
                inv.setItem(16 + row * 9, item(violation.falsePositive() ? Material.LIME_DYE : Material.RED_DYE,
                        violation.falsePositive() ? "誤検知:確認済み" : "誤検知として記録",
                        "クリックで状態を切り替え"));
            }
            if (page > 0) inv.setItem(45, item(Material.ARROW, "← 前へ", "前の5件"));
            inv.setItem(49, item(Material.PAPER, "ページ  " + (page + 1),
                    "左: 詳細を見る", "右: 誤検知ラベルを切り替える"));
            if (stats != null && (long) (page + 1) * 5 < stats.detections())
                inv.setItem(53, item(Material.ARROW, "次へ →", "次の5件"));
            player.openInventory(inv);
    }

    private void statisticsClick(Player player, StatisticsHolder holder, int slot) {
        if (slot == 5) {
            if (holder.detector() == null) history(player, 1, null, true);
            else { plugin.exportStatistics(player); player.sendMessage(plugin.prefix() + "統計JSONを出力しています。"); }
            return;
        }
        if (holder.detector() == null) {
            if (slot == 7) { plugin.exportStatistics(player); player.sendMessage(plugin.prefix() + "統計JSONを出力しています。"); return; }
            if (slot == 47) { confirmClearStatistics(player); return; }
            if (slot == 8) { global(player); return; }
            if (slot == 45 && holder.page() > 0 || slot == 53 && isArrow(player, slot)) {
                statistics(player, holder.page() + (slot == 45 ? -1 : 1)); return;
            }
            for (int row = 0; row < 5; row++) if (slot == 10 + row * 9 || slot == 16 + row * 9) {
                int index = holder.page() * 5 + row;
                List<CheckModule> modules = plugin.checks().modules().stream()
                        .sorted(java.util.Comparator.comparing(CheckModule::key)).toList();
                if (index < modules.size()) detectorStatistics(player, modules.get(index).key(), 0);
                return;
            }
            return;
        }
        if (slot == 8) { statistics(player, 0); return; }
        if (slot == 45 && holder.page() > 0 || slot == 53 && isArrow(player, slot)) {
            detectorStatistics(player, holder.detector(), holder.page() + (slot == 45 ? -1 : 1));
            return;
        }
        for (int row = 0; row < holder.violationIds().size(); row++) {
            if (slot != 16 + row * 9) continue;
            long id = holder.violationIds().get(row);
            if (id == 0) return;
            boolean newValue = !holder.falsePositiveStates().get(row);
            long request = nextStatisticsRequest(player);
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    plugin.store().markFalsePositive(id, newValue);
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (currentStatisticsRequest(player, request))
                            detectorStatistics(player, holder.detector(), holder.page());
                    });
                } catch (java.sql.SQLException exception) {
                    Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(
                            plugin.prefix() + "誤検知ラベルを保存できませんでした: " + exception.getMessage()));
                }
            });
            return;
        }
    }

    private void confirmClearStatistics(Player player) {
        Inventory inv = inventory(new ClearStatisticsHolder(), 27, "PAC  •  履歴の削除確認");
        inv.setItem(4, item(Material.TNT, NamedTextColor.RED,
                "検知履歴をすべて削除しますか？",
                "違反履歴と誤検知ラベルを削除",
                "BAN履歴・設定・出力済みJSONは維持"));
        inv.setItem(11, item(Material.REDSTONE_BLOCK, NamedTextColor.RED,
                "削除してDBを最適化", "この操作は取り消せません"));
        inv.setItem(15, item(Material.BARRIER, "戻る", "統計画面へ戻る"));
        player.openInventory(inv);
    }

    private void clearStatistics(Player player) {
        player.closeInventory();
        player.sendMessage(plugin.prefix() + "統計履歴を削除してDBを最適化しています。");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                int removed = plugin.store().clearStatistics();
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) player.sendMessage(plugin.prefix()
                            + "統計履歴 " + removed + "件を削除しました。BAN履歴は維持されています。");
                });
            } catch (java.sql.SQLException exception) {
                Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(
                        plugin.prefix() + "統計履歴を削除できませんでした: " + exception.getMessage()));
            }
        });
    }

    private static String abbreviate(String value, int limit) {
        if (value == null) return "";
        return value.length() <= limit ? value : value.substring(0, limit - 1) + "…";
    }

    private static boolean isArrow(Player player, int slot) {
        ItemStack item = player.getOpenInventory().getTopInventory().getItem(slot);
        return item != null && item.getType() == Material.ARROW;
    }

    private void numberDialog(Player player, NumberSetting s, String path, Runnable back) {
        player.closeInventory();
        Dialog dialog = Dialog.create(b -> b.empty()
                .base(DialogBase.builder(Component.text(s.name(), NamedTextColor.GOLD))
                        .inputs(List.of(DialogInput.text("value", Component.text("値"))
                                .initial(current(path, s.fallback())).width(250).build())).build())
                .type(DialogType.confirmation(
                        ActionButton.builder(Component.text("保存", NamedTextColor.GREEN))
                                .action(DialogAction.customClick((view, audience) -> {
                                    if (!(audience instanceof Player target)) return;
                                    try {
                                        double n = Double.parseDouble(view.getText("value"));
                                        if (!Double.isFinite(n) || n < s.min() || n > s.max() || (s.integer() && n != Math.floor(n))) throw new NumberFormatException();
                                        plugin.getConfig().set(path, s.integer() ? (int) n : n);
                                        plugin.saveConfig(); plugin.refreshSettings();
                                        Bukkit.getScheduler().runTask(plugin, back);
                                    } catch (NumberFormatException ex) {
                                        target.sendMessage(plugin.prefix() + s.min() + " ～ " + s.max() + " の数値を入力してください。");
                                        Bukkit.getScheduler().runTask(plugin, () -> numberDialog(target, s, path, back));
                                    }
                                }, ClickCallback.Options.builder().uses(1).build())).build(),
                        ActionButton.builder(Component.text("戻る", NamedTextColor.RED))
                                .action(DialogAction.customClick((view, audience) -> {
                                    if (audience instanceof Player) Bukkit.getScheduler().runTask(plugin, back);
                                }, ClickCallback.Options.builder().uses(1).build())).build())));
        player.showDialog(dialog);
    }

    private List<CheckModule> modules(int category) {
        return plugin.checks().modules().stream().filter(m -> category(m.key()) == category).toList();
    }
    private static int category(String key) {
        if (key.equals("crash-chest") || key.equals("nuker") || key.equals("anti-hunger")) return 2;
        if (key.equals("scaffold") || key.equals("reach") || key.equals("kill-aura")
                || key.equals("critical-packet")) return 1;
        if (key.equals("xray") || key.equals("noclip")) return 3;
        return 0;
    }
    private static List<NumberSetting> numbers(String key) {
        return switch (key) {
            case "packet-flood" -> List.of(new NumberSetting("最大パケット/秒", "max-per-second", 240, 40, 5000, true));
            case "nuker" -> List.of(new NumberSetting("採掘パケット/秒", "max-packets-per-second", 80, 40, 5000, true),
                    new NumberSetting("採掘対象/秒", "max-targets-per-second", 24, 8, 512, true));
            case "motion-prediction" -> List.of(new NumberSetting("移動Offset", "offset-threshold", 0.04, 0.02, 10, false),
                    new NumberSetting("移動Buffer", "buffer-threshold", 8, 3, 100, false));
            case "air-prediction" -> List.of(new NumberSetting("空中Offset", "offset-threshold", 0.06, 0.02, 10, false),
                    new NumberSetting("水平Offset", "horizontal-offset-threshold", 0.015, 0.005, 10, false),
                    new NumberSetting("空中Buffer", "buffer-threshold", 6, 3, 100, false),
                    new NumberSetting("Elytra Offset", "elytra-offset-threshold", 0.035, 0.01, 5, false),
                    new NumberSetting("Elytra Buffer", "elytra-buffer-threshold", 6, 3, 100, false));
            case "water-motion-prediction" -> List.of(new NumberSetting("水中Offset", "offset-threshold", 0.05, 0.05, 10, false),
                    new NumberSetting("水中Buffer", "buffer-threshold", 6, 3, 100, false));
            case "xray" -> List.of(new NumberSetting("統計時間窓（秒）", "window-seconds", 120, 30, 3600, true),
                    new NumberSetting("最低採掘数", "minimum-blocks", 60, 20, 5000, true),
                    new NumberSetting("希少鉱石高比率", "rare-high-ratio", 0.08, 0.01, 1, false),
                    new NumberSetting("希少鉱石中比率", "rare-medium-ratio", 0.05, 0.005, 1, false),
                    new NumberSetting("通常鉱石高比率", "common-high-ratio", 0.15, 0.01, 1, false),
                    new NumberSetting("通常鉱石中比率", "common-medium-ratio", 0.08, 0.005, 1, false),
                    new NumberSetting("疑い値閾値", "suspicion-threshold", 18, 5, 100, false),
                    new NumberSetting("希少鉱石間距離", "rare-jump-distance", 10, 5, 64, false));
            case "noclip" -> List.of(new NumberSetting("連続侵入フラグ数", "phase-flags-required", 5, 3, 20, true),
                    new NumberSetting("衝突許容幅", "collision-tolerance", 0.10, 0, 0.30, false),
                    new NumberSetting("最大移動距離", "max-distance", 10, 1, 10, false));
            default -> List.of();
        };
    }
    private String current(String path, double fallback) {
        if (path.equals("detectors.water-motion-prediction.offset-threshold"))
            return String.valueOf(plugin.waterMotionOffsetThreshold());
        Object value = plugin.getConfig().get(path);
        return value == null ? String.valueOf(fallback) : String.valueOf(value);
    }
    private void toggle(String path) {
        plugin.getConfig().set(path, !plugin.getConfig().getBoolean(path));
        plugin.saveConfig(); plugin.refreshSettings();
    }
    private ItemStack toggleItem(String name, String path, String explanation) {
        boolean enabled = plugin.getConfig().getBoolean(path);
        return item(enabled ? Material.LIME_DYE : Material.GRAY_DYE,
                enabled ? NamedTextColor.GREEN : NamedTextColor.RED,
                name + "  •  " + onOff(enabled), explanation, "クリックで切り替える");
    }
    private static Inventory inventory(InventoryHolder holder, int size, String title) {
        Inventory inv = Bukkit.createInventory(holder, size, Component.text(title, NamedTextColor.GOLD));
        ItemStack border = item(Material.BLACK_STAINED_GLASS_PANE, NamedTextColor.DARK_GRAY, " ");
        ItemStack side = item(Material.GRAY_STAINED_GLASS_PANE, NamedTextColor.DARK_GRAY, " ");
        for (int row = 0; row < size / 9; row++) {
            if (row == 0 || row == size / 9 - 1) {
                for (int col = 0; col < 9; col++) inv.setItem(row * 9 + col, border);
            } else {
                inv.setItem(row * 9, side);
                inv.setItem(row * 9 + 8, side);
            }
        }
        return inv;
    }
    private static ItemStack item(Material material, String name, String... lore) {
        return item(material, NamedTextColor.YELLOW, name, lore);
    }
    private static ItemStack item(Material material, NamedTextColor color, String name, String... lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(name, color));
        List<Component> lines = new java.util.ArrayList<>();
        for (String paragraph : lore) {
            if (paragraph == null || paragraph.isBlank()) continue;
            for (String line : paragraph.split("\\R")) {
                int cursor = 0;
                while (cursor < line.length()) {
                    int end = line.offsetByCodePoints(cursor,
                            Math.min(38, line.codePointCount(cursor, line.length())));
                    lines.add(Component.text(line.substring(cursor, end), NamedTextColor.GRAY));
                    cursor = end;
                }
            }
        }
        meta.lore(lines);
        stack.setItemMeta(meta);
        return stack;
    }
    private static String onOff(boolean value) { return value ? "有効" : "無効"; }
}

package org.pexserver.pac.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.pexserver.pac.PacPlugin;
import org.pexserver.pac.check.core.CheckModule;
import org.pexserver.pac.storage.ViolationStore;

import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class PacCommand implements CommandExecutor, TabCompleter {
    private static final DateTimeFormatter HISTORY_TIME = DateTimeFormatter
            .ofPattern("MM/dd HH:mm:ss").withZone(ZoneId.systemDefault());
    private final PacPlugin plugin;
    public PacCommand(PacPlugin plugin) { this.plugin = plugin; }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            help(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (!sender.hasPermission("pac." + sub)) { sender.sendMessage(plugin.prefix() + "Permission denied."); return true; }
        try {
            switch (sub) {
                case "ui" -> ui(sender);
                case "debug" -> debug(sender, args);
                case "alerts" -> alerts(sender, args);
                case "bypass" -> bypass(sender, args);
                case "detector" -> detector(sender, args);
                case "ground" -> ground(sender, args);
                case "settings" -> settings(sender, args);
                case "ban" -> ban(sender, args);
                case "unban" -> unban(sender, args);
                case "banlist" -> banlist(sender, args);
                case "support" -> support(sender, args);
                case "history" -> history(sender, args);
                case "reload" -> { plugin.refreshSettings(); sender.sendMessage(plugin.prefix() + "Configuration reloaded."); }
                default -> sender.sendMessage(plugin.prefix() + "Unknown subcommand. Use /pac help.");
            }
        } catch (IllegalArgumentException e) {
            sender.sendMessage(plugin.prefix() + "入力を確認してください: " + e.getMessage());
        }
        return true;
    }

    private void help(CommandSender sender) {
        sender.sendMessage(plugin.prefix() + "§ePAC コマンド§7  /pac <コマンド>");
        if (sender.hasPermission("pac.ui")) sender.sendMessage("§7画面 §f/pac ui §8— 検知・制裁・統計の設定");
        if (sender.hasPermission("pac.debug")) sender.sendMessage("§7診断 §f/pac debug [on|off|status|export] §8— 記録モードと統計出力");
        if (sender.hasPermission("pac.alerts")) sender.sendMessage("§7通知 §f/pac alerts [on|off] §8— 自分宛の通知を切替");
        if (sender.hasPermission("pac.detector")) sender.sendMessage("§7検知 §f/pac detector <名前|list> [on|off]");
        if (sender.hasPermission("pac.bypass")) sender.sendMessage("§7除外 §f/pac bypass <オンライン名|UUID> <on|off>");
        if (sender.hasPermission("pac.ban")) sender.sendMessage("§7制裁 §f/pac ban <プレイヤー> [理由]");
        if (sender.hasPermission("pac.unban")) sender.sendMessage("§7解除 §f/pac unban <プレイヤー> [reset]");
        if (sender.hasPermission("pac.banlist")) sender.sendMessage("§7一覧 §f/pac banlist [ページ]");
        if (sender.hasPermission("pac.support")) sender.sendMessage("§7サポート §f/pac support <ID> §8— BANの照会");
        if (sender.hasPermission("pac.history")) sender.sendMessage("§7履歴 §f/pac history <ページ> [プレイヤー] §8— 省略時は全員");
        if (sender.hasPermission("pac.ground")) sender.sendMessage("§7移動診断 §f/pac ground <オンライン名>");
        if (sender.hasPermission("pac.settings")) sender.sendMessage("§7設定 §f/pac settings [項目] [値]");
        if (sender.hasPermission("pac.reload")) sender.sendMessage("§7反映 §f/pac reload");
    }

    private void ui(CommandSender sender) {
        if (!(sender instanceof Player player))
            throw new IllegalArgumentException("Only players can open the PAC UI.");
        plugin.settingsUi().open(player);
    }

    private void debug(CommandSender sender, String[] args) {
        String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "toggle";
        switch (action) {
            case "on", "true" -> plugin.setDebugRecording(true);
            case "off", "false" -> plugin.setDebugRecording(false);
            case "toggle" -> plugin.setDebugRecording(!plugin.isDebugRecording());
            case "status" -> { }
            case "export" -> {
                plugin.exportStatistics(sender);
                sender.sendMessage(plugin.prefix() + "SQLiteの統計をJSONへ出力しています。");
                return;
            }
            default -> throw new IllegalArgumentException("Usage: /pac debug [on|off|status|export]");
        }
        sender.sendMessage(plugin.prefix() + "Debug記録モード: "
                + (plugin.isDebugRecording() ? "ON（検知を記録し、自動制裁を停止。取消・位置補正は全体ロールバック設定に従う）" : "OFF"));
        if (action.equals("status")) {
            var metrics = plugin.checks().movementDispatchMetrics().snapshot();
            sender.sendMessage(plugin.prefix() + String.format(Locale.ROOT,
                    "Java移動packet処理: samples=%d average=%.2fµs p95≤%.2fµs max=%.2fµs",
                    metrics.samples(), metrics.averageNanos() / 1_000.0,
                    metrics.p95UpperBoundNanos() / 1_000.0,
                    metrics.maximumNanos() / 1_000.0));
            var world = plugin.worldSampleMetrics().snapshot();
            sender.sendMessage(plugin.prefix() + String.format(Locale.ROOT,
                    "環境サンプリング/tick: samples=%d average=%.3fms p95≤%.3fms max=%.3fms",
                    world.samples(), world.averageNanos() / 1_000_000.0,
                    world.p95UpperBoundNanos() / 1_000_000.0,
                    world.maximumNanos() / 1_000_000.0));
        }
    }

    private void alerts(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) throw new IllegalArgumentException("Only players can toggle alerts.");
        boolean value = args.length > 1 ? parseToggle(args[1]) : !plugin.alerts(player.getUniqueId());
        plugin.setAlerts(player.getUniqueId(), value);
        sender.sendMessage(plugin.prefix() + "自分宛の検知通知: " + (value ? "有効" : "無効"));
    }

    private void bypass(CommandSender sender, String[] args) {
        if (args.length < 3) throw new IllegalArgumentException("Usage: /pac bypass <online-name|uuid> <on|off>");
        String identity = String.join(" ", Arrays.copyOfRange(args, 1, args.length - 1));
        Player target = null;
        try { target = Bukkit.getPlayer(UUID.fromString(identity)); }
        catch (IllegalArgumentException ignored) { }
        if (target == null) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.getName().equalsIgnoreCase(identity)) { target = online; break; }
            }
        }
        if (target == null) throw new IllegalArgumentException("Player must be online.");
        boolean value = parseToggle(args[args.length - 1]);
        plugin.setBypass(target.getUniqueId(), value);
        sender.sendMessage(plugin.prefix() + target.getName() + " の検知除外: "
                + (value ? "有効" : "無効") + "（ログアウトまで）");
    }

    private void detector(CommandSender sender, String[] args) {
        if (args.length < 2) throw new IllegalArgumentException("Usage: /pac detector <name> [on|off]");
        if (args[1].equalsIgnoreCase("list")) {
            sender.sendMessage(plugin.prefix() + "Detectors: " + detectorNames());
            return;
        }
        CheckModule module = plugin.checks().get(args[1]);
        if (module == null) throw new IllegalArgumentException("Unknown detector. " + detectorNames());
        if (args.length > 2) plugin.setEnabled(module, parseToggle(args[2]));
        sender.sendMessage(plugin.prefix() + module.key() + ": " + (plugin.enabled(module) ? "有効" : "無効"));
    }

    private void ground(CommandSender sender, String[] args) {
        if (args.length < 2) throw new IllegalArgumentException("Usage: /pac ground <online-player>");
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) throw new IllegalArgumentException("Player must be online.");
        var state = plugin.ground().state(target.getUniqueId());
        var environment = plugin.environment().get(target.getUniqueId());
        sender.sendMessage(plugin.prefix() + target.getName() + " ground="
                + (state == null || !state.known() ? "unknown" : state.onGround())
                + (state == null ? "" : " supportY=" + state.supportY() + " stableTicks=" + state.consecutiveTicks() + " tick=" + state.tick())
                + (environment == null ? " prediction=grace/unknown"
                    : " ordinaryGround=" + environment.ordinaryGround() + " ordinaryAir=" + environment.ordinaryAir()));
    }

    private void settings(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(plugin.prefix() + "Settings: alerts.enabled=" + plugin.getConfig().getBoolean("alerts.enabled")
                    + ", packet-flood.max-per-second=" + plugin.maxPacketsPerSecond()
                    + ", geyser-api-available=" + plugin.geyserAvailable()
                    + ", bedrock-engine-active=" + plugin.bedrockEngineAvailable()
                    + ", punishments.rollback-enabled=" + plugin.rollbackEnabled()
                    + ", punishments.probation-enabled=" + plugin.getConfig().getBoolean("punishments.probation-enabled")
                    + ", punishments.score-threshold=" + plugin.getConfig().getDouble("punishments.score-threshold", 25)
                    + ", punishments.daily-risk-allowance=" + plugin.getConfig().getDouble("punishments.daily-risk-allowance")
                    + ", punishments.trust-loss-per-action=" + plugin.getConfig().getDouble("punishments.trust-loss-per-action"));
            return;
        }
        String path = args[1];
        if (!List.of("alerts.enabled", "punishments.probation-enabled", "punishments.rollback-enabled",
                "punishments.score-threshold", "punishments.daily-risk-allowance",
                "punishments.trust-loss-per-action",
                "scoring.repeat-multiplier", "scoring.half-life-seconds",
                "detectors.packet-flood.max-per-second").contains(path))
            throw new IllegalArgumentException("This setting is not editable with /pac. Edit config.yml and reload.");
        if (args.length > 2) {
            Object value;
            if (path.equals("alerts.enabled") || path.equals("punishments.probation-enabled")
                    || path.equals("punishments.rollback-enabled")) {
                value = parseToggle(args[2]);
            } else if (path.endsWith(".max-per-second") || path.endsWith(".half-life-seconds")) {
                value = Integer.parseInt(args[2]);
            } else {
                value = Double.parseDouble(args[2]);
            }
            if (value instanceof Integer integer && integer < 1) throw new IllegalArgumentException("Value must be positive.");
            if (value instanceof Double number && (!Double.isFinite(number) || number <= 0))
                throw new IllegalArgumentException("Value must be positive.");
            plugin.getConfig().set(path, value);
            plugin.saveConfig();
            plugin.refreshSettings();
        }
        sender.sendMessage(plugin.prefix() + path + " = " + plugin.getConfig().get(path));
    }

    private void ban(CommandSender sender, String[] args) {
        if (args.length < 2) throw new IllegalArgumentException("Usage: /pac ban <player|uuid> [reason]");
        OfflinePlayer target = resolve(args[1]);
        String reason = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : "Anti-cheat violation";
        UUID uuid = target.getUniqueId();
        String name = target.getName() == null ? args[1] : target.getName();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                ViolationStore.Ban saved = plugin.store().ban(uuid, name, reason);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    plugin.publishBan(saved);
                    Player online = Bukkit.getPlayer(uuid);
                    if (plugin.store().isCurrentBan(saved)) {
                        if (online != null && online.isOnline())
                            online.kick(plugin.banMessage(saved.permanent(), saved.expiresAt(), saved.supportId()));
                        sender.sendMessage(plugin.prefix() + "Banned " + name
                                + ". Support ID: " + saved.supportId());
                    } else {
                        sender.sendMessage(plugin.prefix()
                                + "BANが変更または解除されたため、キックを取り消しました。");
                    }
                });
            } catch (SQLException exception) {
                Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(
                        plugin.prefix() + "BANの保存に失敗しました: " + exception.getMessage()));
            }
        });
    }

    private void unban(CommandSender sender, String[] args) {
        if (args.length < 2) {
            throw new IllegalArgumentException(
                    "Usage: /pac unban <player|uuid> [reset]");
        }
        ViolationStore.Ban ban = plugin.store().findBan(args[1])
                .orElseThrow(() -> new IllegalArgumentException(
                        "BAN中のプレイヤーが見つかりません。名前またはUUIDを確認してください。"));

        boolean reset = false;
        if (args.length >= 3) {
            if (!args[2].equalsIgnoreCase("reset")) {
                throw new IllegalArgumentException(
                        "信頼性も初期化する場合は reset を指定してください。");
            }
            reset = true;
        }

        boolean resetTrust = reset;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                boolean removed = plugin.store().unbanIfCurrent(ban, resetTrust);
                Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(plugin.prefix()
                        + (removed ? ban.name() + (resetTrust
                        ? " のBANを解除し、信頼性をリセットしました。"
                        : " のBANを解除しました。信頼性は維持されます。")
                        : "BANが変更または解除されました。最新の状態を確認してください。")));
            } catch (SQLException exception) {
                Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(
                        plugin.prefix() + "BAN解除に失敗しました: " + exception.getMessage()));
            }
        });
    }

    private void banlist(CommandSender sender, String[] args) {
        int page = 1;
        if (args.length > 1) {
            try { page = Integer.parseInt(args[1]); }
            catch (NumberFormatException invalid) {
                throw new IllegalArgumentException("Usage: /pac banlist [page]");
            }
        }
        if (page < 1) throw new IllegalArgumentException("Page must be at least 1.");
        List<ViolationStore.Ban> bans = plugin.store().bans();
        int pages = Math.max(1, (bans.size() + 9) / 10);
        if (page > pages) throw new IllegalArgumentException("Last BAN page is " + pages + ".");
        sender.sendMessage(plugin.prefix() + "Active bans: " + bans.size() + " | page " + page + "/" + pages);
        long now = System.currentTimeMillis();
        int start = (page - 1) * 10;
        for (ViolationStore.Ban ban : bans.subList(start, Math.min(start + 10, bans.size()))) {
            String remaining = ban.permanent() ? "永久"
                    : java.time.Duration.ofMillis(Math.max(0, ban.expiresAt() - now)).toDays() + "日";
            sender.sendMessage("§c" + ban.name() + " §7stage=" + ban.stage()
                    + " remaining=" + remaining + " §bID=" + ban.supportId());
        }
    }

    private void support(CommandSender sender, String[] args) {
        if (args.length != 2) throw new IllegalArgumentException("Usage: /pac support <ID>");
        String supportId = args[1].trim().toUpperCase(Locale.ROOT);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                java.util.Optional<ViolationStore.SupportCase> result = plugin.store().supportCase(supportId);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (result.isEmpty()) {
                        sender.sendMessage(plugin.prefix() + "Support ID not found.");
                        return;
                    }
                    ViolationStore.SupportCase support = result.get();
                    long now = System.currentTimeMillis();
                    boolean expired = !support.permanent() && support.expiresAt() > 0
                            && support.expiresAt() <= now;
                    String status = support.active() && !expired ? "Active"
                            : expired ? "Expired" : "Closed";
                    String until = support.permanent() ? "Permanent"
                            : HISTORY_TIME.format(Instant.ofEpochMilli(support.expiresAt()));
                    sender.sendMessage(plugin.prefix() + "§bSupport case §f" + support.supportId());
                    sender.sendMessage("§7Player: §f" + support.name() + " §8(" + support.uuid() + ")");
                    sender.sendMessage("§7Status: §f" + status + " §8| §7Until: §f" + until);
                    sender.sendMessage("§7Created: §f"
                            + HISTORY_TIME.format(Instant.ofEpochMilli(support.createdAt())));
                    sender.sendMessage("§7Staff reason: §f" + support.reason());
                });
            } catch (SQLException exception) {
                Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(
                        plugin.prefix() + "Could not load support case: " + exception.getMessage()));
            }
        });
    }

    private void history(CommandSender sender, String[] args) {
        int requestedPage = 1;
        if (args.length >= 2) {
            try { requestedPage = Integer.parseInt(args[1]); }
            catch (NumberFormatException exception) {
                throw new IllegalArgumentException("ページは1以上の数値です。使い方: /pac history <ページ> [プレイヤー]");
            }
        }
        if (requestedPage < 1) throw new IllegalArgumentException("ページは1以上を指定してください。");
        String playerName = args.length >= 3
                ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : null;
        int page = requestedPage;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                ViolationStore.HistoryPage result = plugin.store().historyPage(playerName, page, 10);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    String scope = playerName == null ? "全員" : playerName;
                    sender.sendMessage(plugin.prefix() + "§e検知履歴 §7" + scope + " §8| §f"
                            + result.page() + "/" + result.pages() + " ページ §8| §f"
                            + result.total() + "件");
                    if (result.rows().isEmpty()) sender.sendMessage("§7履歴はありません。");
                    for (ViolationStore.Violation row : result.rows()) {
                        String time = HISTORY_TIME.format(Instant.ofEpochMilli(row.createdAt()));
                        String score = String.format(Locale.ROOT, "%.2f", row.score());
                        String status = (row.debugMode() ? " §b[記録]" : "")
                                + (row.falsePositive() ? " §a[誤検知]" : "");
                        if (sender instanceof Player) {
                            Component line = Component.text("#" + row.id() + " " + time + "  ", NamedTextColor.DARK_GRAY)
                                    .append(Component.text(row.name(), NamedTextColor.WHITE))
                                    .append(Component.text("  »  ", NamedTextColor.DARK_GRAY))
                                    .append(Component.text(row.detector(), NamedTextColor.YELLOW))
                                    .append(Component.text("  Score " + score, NamedTextColor.GOLD))
                                    .append(row.debugMode() ? Component.text(" [記録]", NamedTextColor.AQUA) : Component.empty())
                                    .append(row.falsePositive() ? Component.text(" [誤検知]", NamedTextColor.GREEN) : Component.empty())
                                    .hoverEvent(HoverEvent.showText(Component.text(row.detail()
                                            + "\n数値: " + row.metricsJson(), NamedTextColor.GRAY)))
                                    .clickEvent(ClickEvent.suggestCommand("/pac history 1 " + row.name()));
                            sender.sendMessage(line);
                        } else {
                            sender.sendMessage("#" + row.id() + " " + time + " " + row.name()
                                    + " / " + row.detector() + " / Score " + score
                                    + " " + status + " / " + row.detail());
                        }
                    }
                    if (result.page() < result.pages()) sender.sendMessage("§7次: §f/pac history "
                            + (result.page() + 1) + (playerName == null ? "" : " " + playerName));
                });
            } catch (SQLException exception) {
                Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(
                        plugin.prefix() + "履歴を読み込めませんでした: " + exception.getMessage()));
            }
        });
    }

    private static OfflinePlayer resolve(String input) {
        try { return Bukkit.getOfflinePlayer(UUID.fromString(input)); }
        catch (IllegalArgumentException ignored) {
            OfflinePlayer player = Bukkit.getOfflinePlayer(input);
            if (!player.hasPlayedBefore() && !player.isOnline()) throw new IllegalArgumentException("Unknown player. Use a UUID for players not cached by the server.");
            return player;
        }
    }

    private static boolean parseToggle(String input) {
        return switch (input.toLowerCase(Locale.ROOT)) {
            case "on", "true" -> true;
            case "off", "false" -> false;
            default -> throw new IllegalArgumentException("Expected on or off.");
        };
    }

    private String detectorNames() {
        return plugin.checks().modules().stream().map(CheckModule::key).toList().toString();
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> choices = new ArrayList<>();
        if (args.length == 1) {
            choices.add("help");
            for (String sub : List.of("ui", "debug", "alerts", "bypass", "detector", "ground", "settings", "ban", "unban", "banlist", "support", "history", "reload"))
                if (sender.hasPermission("pac." + sub)) choices.add(sub);
        }
        else if (args.length == 2 && args[0].equalsIgnoreCase("debug")) choices.addAll(List.of("on", "off", "status", "export"));
        else if (args.length == 2 && args[0].equalsIgnoreCase("detector")) {
            choices.add("list");
            choices.addAll(plugin.checks().modules().stream().map(CheckModule::key).toList());
        }
        else if (args.length == 2 && args[0].equalsIgnoreCase("settings")) choices.addAll(List.of(
            "alerts.enabled", "punishments.probation-enabled", "punishments.rollback-enabled",
            "punishments.score-threshold", "punishments.daily-risk-allowance",
            "punishments.trust-loss-per-action",
            "scoring.repeat-multiplier", "scoring.half-life-seconds",
            "detectors.packet-flood.max-per-second"));
        else if (args.length == 2 && args[0].equalsIgnoreCase("history")) choices.addAll(List.of("1", "2", "3"));
        else if (args.length == 3 && args[0].equalsIgnoreCase("history")) choices.addAll(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
        else if (args.length == 2 && List.of("bypass", "ground", "ban", "unban").contains(args[0].toLowerCase(Locale.ROOT))) choices.addAll(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
        else if (args.length == 3 && args[0].equalsIgnoreCase("unban")) {
            choices.add("reset");
        }
        else if (args.length == 3 && List.of("bypass", "detector").contains(args[0].toLowerCase(Locale.ROOT))) choices.addAll(List.of("on", "off"));
        return choices.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(args[args.length - 1].toLowerCase(Locale.ROOT))).toList();
    }
}

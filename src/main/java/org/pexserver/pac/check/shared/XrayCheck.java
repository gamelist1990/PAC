package org.pexserver.pac.check.shared;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.pexserver.pac.PacPlugin;
import org.pexserver.pac.check.core.AbstractCheck;
import org.pexserver.pac.check.core.EventCheck;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Experimental statistical ore-mining check. Disabled by default. */
public final class XrayCheck extends AbstractCheck implements EventCheck, Listener {
    private static final Map<Material, Integer> TRACKED_ORES = Map.ofEntries(
            Map.entry(Material.IRON_ORE, 1), Map.entry(Material.DEEPSLATE_IRON_ORE, 1),
            Map.entry(Material.GOLD_ORE, 2), Map.entry(Material.DEEPSLATE_GOLD_ORE, 2),
            Map.entry(Material.NETHER_GOLD_ORE, 2), Map.entry(Material.LAPIS_ORE, 1),
            Map.entry(Material.DEEPSLATE_LAPIS_ORE, 1), Map.entry(Material.REDSTONE_ORE, 1),
            Map.entry(Material.DEEPSLATE_REDSTONE_ORE, 1), Map.entry(Material.DIAMOND_ORE, 5),
            Map.entry(Material.DEEPSLATE_DIAMOND_ORE, 5), Map.entry(Material.EMERALD_ORE, 5),
            Map.entry(Material.DEEPSLATE_EMERALD_ORE, 5), Map.entry(Material.ANCIENT_DEBRIS, 8),
            Map.entry(Material.NETHER_QUARTZ_ORE, 1));

    private final PacPlugin plugin;
    private final Map<UUID, XrayMiningProfile> profiles = new ConcurrentHashMap<>();

    public XrayCheck(PacPlugin plugin) { this.plugin = plugin; }

    @Override public String key() { return "xray"; }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (!plugin.enabled(uuid, this) || plugin.isExempt(uuid)
                || player.getGameMode() != GameMode.SURVIVAL) return;

        Block block = event.getBlock();
        Material type = block.getType();
        Integer weight = TRACKED_ORES.get(type);
        boolean trackedOre = weight != null;
        boolean rareOre = trackedOre && weight >= 5;
        long now = System.currentTimeMillis();
        XrayMiningProfile profile = profiles.computeIfAbsent(uuid, ignored -> new XrayMiningProfile());
        XrayMiningProfile.Finding finding = profile.observe(now, block.getWorld().getUID(),
                block.getX(), block.getY(), block.getZ(), trackedOre, rareOre,
                trackedOre ? weight : 0, type == Material.ANCIENT_DEBRIS,
                trackedOre && isHiddenOre(block), settings());
        if (finding == null) return;

        if (plugin.cancel(this, uuid)) event.setCancelled(true);
        String detail = "experimental mining statistics: " + finding.reason()
                + String.format(java.util.Locale.ROOT,
                "; suspicion=%.2f threshold=%.2f window=%d blocks ores=%d rare=%d ore-ratio=%.4f",
                finding.suspicion(), finding.threshold(), finding.windowBlocks(), finding.oreBlocks(),
                finding.rareOreBlocks(), finding.trackedOreRatio());
        flagLimited(uuid, () -> plugin.flag(uuid, this, detail,
                finding.metrics(), finding.weight()));
    }

    private XrayMiningProfile.Settings settings() {
        String path = "detectors.xray.";
        long windowMillis = (long) boundedInt(path + "window-seconds", 120, 30, 3_600) * 1_000L;
        int minimumBlocks = boundedInt(path + "minimum-blocks", 60, 20, 5_000);
        double rareHigh = boundedDouble(path + "rare-high-ratio", 0.08, 0.01, 1.0);
        double rareMedium = Math.min(rareHigh,
                boundedDouble(path + "rare-medium-ratio", 0.05, 0.005, 1.0));
        double commonHigh = boundedDouble(path + "common-high-ratio", 0.15, 0.01, 1.0);
        double commonMedium = Math.min(commonHigh,
                boundedDouble(path + "common-medium-ratio", 0.08, 0.005, 1.0));
        double suspicionThreshold = boundedDouble(path + "suspicion-threshold", 18.0, 5.0, 100.0);
        double jumpDistance = boundedDouble(path + "rare-jump-distance", 10.0, 5.0, 64.0);
        return new XrayMiningProfile.Settings(windowMillis, minimumBlocks,
                rareHigh, rareMedium, commonHigh, commonMedium, suspicionThreshold, jumpDistance);
    }

    private int boundedInt(String path, int fallback, int min, int max) {
        return Math.max(min, Math.min(max, plugin.getConfig().getInt(path, fallback)));
    }

    private double boundedDouble(String path, double fallback, double min, double max) {
        double value = plugin.getConfig().getDouble(path, fallback);
        return Double.isFinite(value) ? Math.max(min, Math.min(max, value)) : fallback;
    }

    private static boolean isHiddenOre(Block block) {
        return !block.getRelative(0, 1, 0).getType().isAir()
                && !block.getRelative(0, -1, 0).getType().isAir()
                && !block.getRelative(1, 0, 0).getType().isAir()
                && !block.getRelative(-1, 0, 0).getType().isAir()
                && !block.getRelative(0, 0, 1).getType().isAir()
                && !block.getRelative(0, 0, -1).getType().isAir();
    }

    @Override public void forget(UUID uuid) {
        super.forget(uuid);
        profiles.remove(uuid);
    }
}

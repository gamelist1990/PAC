package org.pexserver.pac.check.java.action;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.pexserver.pac.PacPlugin;
import org.pexserver.pac.check.core.AbstractCheck;
import org.pexserver.pac.check.core.EventCheck;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Counts accepted placements rather than animation packets or cancelled attempts. */
public final class FastPlaceCheck extends AbstractCheck implements EventCheck, Listener {
    private final PacPlugin plugin;
    private final Map<UUID, ArrayDeque<Integer>> placements = new HashMap<>();
    public FastPlaceCheck(PacPlugin plugin) { this.plugin = plugin; }
    @Override public String key() { return "fast-place"; }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        var player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (player.getGameMode() != GameMode.SURVIVAL || plugin.isBedrockPlayer(uuid)
                || plugin.isExempt(uuid) || !plugin.enabled(uuid, this)) return;
        int tick = Bukkit.getCurrentTick();
        ArrayDeque<Integer> window = placements.computeIfAbsent(uuid, ignored -> new ArrayDeque<>());
        window.addLast(tick);
        while (!window.isEmpty() && tick - window.peekFirst() >= 20) window.removeFirst();
        if (window.size() >= 8) {
            if (plugin.cancel(this, uuid)) event.setCancelled(true);
            flagLimited(uuid, () -> plugin.flag(uuid, this,
                    window.size() + " accepted block placements in 20 ticks"));
        }
    }

    @Override public void forget(UUID uuid) { super.forget(uuid); placements.remove(uuid); }
}

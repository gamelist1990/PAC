package org.pexserver.pac.check.shared;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.util.Vector;
import org.pexserver.pac.PacPlugin;
import org.pexserver.pac.check.core.AbstractCheck;
import org.pexserver.pac.check.core.EventCheck;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Consecutive bridging placements that lie outside the player's interaction view. */
public final class ScaffoldCheck extends AbstractCheck implements EventCheck, Listener {
    private record Sequence(int tick, int count) { }
    private final Map<UUID, Sequence> sequences = new HashMap<>();
    private final PacPlugin plugin;
    public ScaffoldCheck(PacPlugin plugin) { this.plugin = plugin; }
    @Override public String key() { return "scaffold"; }
    @Override public boolean automaticKickEligible() { return true; }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (!plugin.enabled(uuid, this) || plugin.isExempt(uuid) || event.isCancelled()
                || player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;
        Location feet = player.getLocation();
        Location block = event.getBlockPlaced().getLocation().add(0.5, 0.5, 0.5);
        Location against = event.getBlockAgainst().getLocation().add(0.5, 0.5, 0.5);
        if (!feet.getWorld().equals(block.getWorld()) || block.getY() > feet.getY() + 0.2
                || Math.abs(block.getX() - feet.getX()) > 2.0 || Math.abs(block.getZ() - feet.getZ()) > 2.0) {
            sequences.remove(uuid); return;
        }
        Vector eye = player.getEyeLocation().toVector();
        Vector aim = player.getEyeLocation().getDirection();
        Vector toBlock = block.toVector().subtract(eye);
        Vector toAgainst = against.toVector().subtract(eye);
        double distance = Math.min(toBlock.length(), toAgainst.length());
        if (distance < 1.2 || distance > 5.5) { sequences.remove(uuid); return; }
        // Placing adjacent to a block can be valid even when the new block itself is outside view.
        double alignment = Math.max(aim.dot(toBlock.normalize()), aim.dot(toAgainst.normalize()));
        if (alignment > 0.25) { sequences.remove(uuid); return; }
        int tick = Bukkit.getCurrentTick();
        Sequence old = sequences.get(uuid);
        int count = old != null && tick - old.tick() <= 20 ? old.count() + 1 : 1;
        sequences.put(uuid, new Sequence(tick, count));
        if (count >= 3) {
            flagLimited(uuid, () -> plugin.flag(uuid, this, "bridging outside view: alignment=" + alignment));
            if (plugin.cancel(this, uuid)) event.setCancelled(true);
        }
    }
    @Override public void forget(UUID uuid) { super.forget(uuid); sequences.remove(uuid); }
}

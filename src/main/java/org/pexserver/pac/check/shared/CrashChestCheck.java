package org.pexserver.pac.check.shared;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.component.CustomData;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;
import org.pexserver.pac.PacPlugin;
import org.pexserver.pac.check.core.AbstractCheck;
import org.pexserver.pac.check.core.EventCheck;

import java.util.ArrayDeque;
import java.util.UUID;

/** Rejects the huge nested custom-data lists used by Wurst CrashChest before inventory transfer. */
public final class CrashChestCheck extends AbstractCheck implements EventCheck, Listener {
    private final PacPlugin plugin;
    public CrashChestCheck(PacPlugin plugin) { this.plugin = plugin; }
    @Override public String key() { return "crash-chest"; }

    @EventHandler public void onCreative(InventoryCreativeEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !active(player)) return;
        if (unsafe(event.getCursor())) {
            if (plugin.cancel(this, player.getUniqueId())) event.setCancelled(true);
            event.setCursor(new ItemStack(Material.AIR));
            report(player, "oversized creative item custom data");
        }
    }

    @EventHandler public void onClick(InventoryClickEvent event) {
        if (event instanceof InventoryCreativeEvent || !(event.getWhoClicked() instanceof Player player) || !active(player)) return;
        boolean badCursor = unsafe(event.getCursor());
        boolean badCurrent = unsafe(event.getCurrentItem());
        if (badCursor || badCurrent) {
            if (plugin.cancel(this, player.getUniqueId())) event.setCancelled(true);
            if (badCursor) event.setCursor(new ItemStack(Material.AIR));
            if (badCurrent) event.setCurrentItem(new ItemStack(Material.AIR));
            report(player, "oversized inventory item custom data");
        }
    }

    @EventHandler public void onDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        if (!active(player)) return;
        if (unsafe(event.getItemDrop().getItemStack())) {
            if (plugin.cancel(this, player.getUniqueId())) event.setCancelled(true);
            event.getItemDrop().remove();
            report(player, "oversized dropped item custom data");
        }
    }

    /** Scans creative equipment slots as well as inventory transfers. */
    public void scanPlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!active(player)) continue;
            var inventory = player.getInventory();
            for (int slot = 0; slot < inventory.getSize(); slot++) {
                if (unsafe(inventory.getItem(slot))) {
                    inventory.setItem(slot, null);
                    report(player, "oversized inventory item slot=" + slot);
                }
            }
        }
    }

    private boolean active(Player player) {
        UUID uuid = player.getUniqueId();
        return plugin.enabled(uuid, this) && !plugin.isExempt(uuid);
    }
    private void report(Player player, String detail) {
        UUID uuid = player.getUniqueId();
        flagLimited(uuid, () -> plugin.flag(uuid, this, detail));
    }

    static boolean unsafe(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        try {
            net.minecraft.world.item.ItemStack nms = CraftItemStack.asNMSCopy(item);
            CustomData data = nms.get(DataComponents.CUSTOM_DATA);
            return data != null && tooComplex(data.getUnsafe());
        } catch (RuntimeException exception) {
            return true;
        }
    }

    /** Iterative and bounded: never recursively serializes or prints hostile NBT. */
    static boolean tooComplex(CompoundTag root) {
        ArrayDeque<Tag> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        while (!queue.isEmpty()) {
            Tag tag = queue.removeFirst();
            if (++visited > 2048) return true;
            if (tag instanceof ListTag list) {
                if (list.size() > 512) return true;
                for (Tag child : list) {
                    if (queue.size() + visited >= 2048) return true;
                    queue.addLast(child);
                }
            } else if (tag instanceof CompoundTag compound) {
                if (compound.size() > 256) return true;
                for (Tag child : compound.values()) {
                    if (queue.size() + visited >= 2048) return true;
                    queue.addLast(child);
                }
            }
        }
        return false;
    }
}

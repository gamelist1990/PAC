package org.pexserver.pac.check.java.action;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerInput;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.WeakHashMap;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.pexserver.pac.PacPlugin;
import org.pexserver.pac.check.core.AbstractCheck;
import org.pexserver.pac.check.core.EventCheck;

public final class InventoryMoveCheck extends AbstractCheck implements EventCheck, Listener {
   private final PacPlugin plugin;
   private final InventoryMoveTracker tracker = new InventoryMoveTracker();
   private final Map<PlayerMoveEvent, Location> initialMoveTargets = Collections.synchronizedMap(new WeakHashMap<>());

   public InventoryMoveCheck(PacPlugin plugin) {
      this.plugin = plugin;
   }

   public String key() {
      return "inventory-move";
   }

   public void playerInventoryOpened(UUID uuid) {
      if (uuid != null) {
         this.tracker.setPlayerInventoryOpen(uuid, true);
      }
   }

   public void clientWindowClosed(UUID uuid) {
      if (uuid != null) {
         this.tracker.setPlayerInventoryOpen(uuid, false);
      }
   }

   public InventoryMoveTracker.Input onInput(UUID uuid, PacketReceiveEvent event, WrapperPlayClientPlayerInput packet, long now) {
      InventoryMoveTracker.Input raw = new InventoryMoveTracker.Input(
         packet.isForward(), packet.isBackward(), packet.isLeft(), packet.isRight(), packet.isJump(), packet.isShift(), packet.isSprint()
      );
      boolean active = this.tracker.inventoryOpen(uuid);
      boolean applicable = active && this.plugin.enabled(uuid, this) && !this.plugin.isExempt(uuid, this);
      InventoryMoveTracker.Input filtered = this.tracker.input(uuid, raw, now, applicable && this.plugin.cancel(this, uuid));
      if (!filtered.equals(raw)) {
         packet.setForward(filtered.forward());
         packet.setBackward(filtered.backward());
         packet.setLeft(filtered.left());
         packet.setRight(filtered.right());
         packet.setJump(filtered.jump());
         packet.setShift(filtered.shift());
         packet.setSprint(filtered.sprint());
         event.setLastUsedWrapper(packet);
         event.markForReEncode(true);
      }

      return filtered;
   }

   public int onBedrockInput(UUID uuid, boolean directional, boolean jump, long now) {
      if (this.tracker.inventoryOpen(uuid) && this.plugin.enabled(uuid, this) && !this.plugin.isExempt(uuid, this) && (directional || jump)) {
         if (this.plugin.recentExternalMotion(uuid)) {
            return this.plugin.cancel(this, uuid) ? 1 : 0;
         }

         this.tracker.movementIntent(uuid, now, directional, jump);
         this.flagLimited(
            uuid,
            () -> this.plugin
               .flag(
                  uuid,
                  this,
                  "Bedrock movement input while inventory is open",
                  Map.of("directional_input", directional ? 1.0 : 0.0, "jump_input", jump ? 1.0 : 0.0)
               )
         );
         return this.plugin.cancel(this, uuid) ? 2 : 0;
      } else {
         return 0;
      }
   }

   @EventHandler(priority = EventPriority.LOWEST)
   public void captureInitialMoveTarget(PlayerMoveEvent event) {
      if (event.getTo() != null) {
         this.initialMoveTargets.put(event, event.getTo().clone());
      }
   }

   @EventHandler(priority = EventPriority.MONITOR)
   public void onPlayerMove(PlayerMoveEvent event) {
      Location target = event.getTo();
      if (target != null) {
         Location initial = this.initialMoveTargets.remove(event);
         if (!event.isCancelled()) {
            Player player = event.getPlayer();
            UUID uuid = player.getUniqueId();
            long now = System.currentTimeMillis();
            long epoch = this.plugin.environment().teleportGeneration(uuid);
            Location from = event.getFrom();
            InventoryMoveTracker.Position fromPosition = new InventoryMoveTracker.Position(from.getX(), from.getY(), from.getZ());
            InventoryMoveTracker.Position position = new InventoryMoveTracker.Position(target.getX(), target.getY(), target.getZ());
            this.tracker.seedPosition(uuid, fromPosition, epoch);
            double dx = target.getX() - from.getX();
            double dy = target.getY() - from.getY();
            double dz = target.getZ() - from.getZ();
            if (!(Math.abs(dx) + Math.abs(dy) + Math.abs(dz) < 1.0E-8)) {
               boolean enabled = this.plugin.enabled(uuid, this) && !this.plugin.isExempt(uuid, this);
               boolean pluginRewroteTarget = !samePosition(initial, target);
               boolean externalMotion = pluginRewroteTarget || this.plugin.recentExternalMotion(uuid);
               InventoryMoveTracker.Movement result = this.tracker
                  .movement(uuid, position, epoch, externalMotion, enabled && this.plugin.cancel(this, uuid), now);
               if (enabled && result.violation()) {
                  this.flagLimited(
                     uuid,
                     () -> this.plugin
                        .flag(
                           uuid,
                           this,
                           String.format(Locale.ROOT, "movement input while inventory is open: delta=(%.3f, %.3f, %.3f)", dx, dy, dz),
                           Map.of("delta_x", dx, "delta_y", dy, "delta_z", dz, "horizontal_delta", Math.hypot(dx, dz))
                        )
                  );
                  if (result.blocked()) {
                     event.setCancelled(true);
                     InventoryMoveTracker.Position anchor = result.rollback() == null ? fromPosition : result.rollback();
                     this.plugin.correctJavaMovement(uuid, anchor.x(), anchor.y(), anchor.z());
                  }
               }
            }
         }
      }
   }

   private static boolean samePosition(Location first, Location second) {
      if (first != null && second != null && Objects.equals(first.getWorld(), second.getWorld())) {
         double dx = first.getX() - second.getX();
         double dy = first.getY() - second.getY();
         double dz = first.getZ() - second.getZ();
         return dx * dx + dy * dy + dz * dz < 1.0E-8;
      } else {
         return false;
      }
   }

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onInventoryOpen(InventoryOpenEvent event) {
      if (event.getPlayer() instanceof Player player) {
         // A server container replaces the local player-inventory screen.
         this.tracker.setPlayerInventoryOpen(player.getUniqueId(), false);
         this.tracker.setContainerOpen(player.getUniqueId(), true);
      }
   }

   @EventHandler(priority = EventPriority.MONITOR)
   public void onInventoryClose(InventoryCloseEvent event) {
      if (event.getPlayer() instanceof Player player) {
         this.tracker.setContainerOpen(player.getUniqueId(), false);
      }
   }

   public void forget(UUID uuid) {
      super.forget(uuid);
      this.tracker.forget(uuid);
   }
}

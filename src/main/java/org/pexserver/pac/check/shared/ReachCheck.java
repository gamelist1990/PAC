package org.pexserver.pac.check.shared;

import org.bukkit.attribute.Attribute;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.pexserver.pac.PacPlugin;
import org.pexserver.pac.check.core.AbstractCheck;
import org.pexserver.pac.check.core.EventCheck;

import java.util.UUID;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Measures eye-to-hitbox reach on server accepted melee attacks. */
public final class ReachCheck extends AbstractCheck implements EventCheck, Listener {
    private final PacPlugin plugin;
    private final CombatTargetHistory targetHistory = new CombatTargetHistory();
    private record Attack(int entityId, long at) { }
    private final Map<UUID, Attack> attacks = new ConcurrentHashMap<>();
    public ReachCheck(PacPlugin plugin) { this.plugin = plugin; }
    @Override public String key() { return "reach"; }

    public void onAttackPacket(UUID uuid, int entityId) {
        attacks.put(uuid, new Attack(entityId, System.currentTimeMillis()));
    }

    /** Main-thread player target sampling used for bounded reach rewind. */
    public void samplePlayer(Player player) {
        if (player == null || player.getWorld() == null) return;
        targetHistory.sample(player.getUniqueId(), player.getWorld().getUID(),
                player.getBoundingBox(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void onDamage(EntityDamageByEntityEvent event) {
        if (event.isCancelled() || !(event.getDamager() instanceof Player player)
                || !(event.getEntity() instanceof LivingEntity victim)) return;
        UUID uuid = player.getUniqueId();
        if (!plugin.enabled(uuid, this) || plugin.isExempt(uuid)) return;
        Attack attack = attacks.remove(uuid);
        long now = System.currentTimeMillis();
        long attackAt = attack == null ? now : attack.at();
        long rewindMillis = CombatTargetHistory.trustedRewindMillis(player.getPing());
        int trustedPing = (int) Math.min(Integer.MAX_VALUE, rewindMillis * 2L);
        var frame = plugin.environment().predictionFrame(uuid, attackAt, trustedPing);
        Vector eye = frame == null || frame.environment() == null
            ? player.getEyeLocation().toVector()
            : new Vector(frame.environment().x(), frame.environment().y() + player.getEyeHeight(),
                frame.environment().z());

        BoundingBox box = victim.getBoundingBox();
        if (victim instanceof Player victimPlayer) {
            CombatTargetHistory.Frame target = targetHistory.atOrBefore(victimPlayer.getUniqueId(),
                    victimPlayer.getWorld().getUID(), attackAt - rewindMillis);
            if (target != null && attackAt >= target.at() && attackAt - target.at() <= 500)
                box = target.box();
        }

        double x = clamp(eye.getX(), box.getMinX(), box.getMaxX()) - eye.getX();
        double y = clamp(eye.getY(), box.getMinY(), box.getMaxY()) - eye.getY();
        double z = clamp(eye.getZ(), box.getMinZ(), box.getMaxZ()) - eye.getZ();
        double distance = Math.sqrt(x*x + y*y + z*z);
        var attribute = player.getAttribute(Attribute.ENTITY_INTERACTION_RANGE);
        double baseReach = attribute == null ? 3.0 : attribute.getValue();
        // Ping changes which historical box is inspected; it no longer grants
        // raw extra reach. This prevents keepalive-only PingSpoof from turning
        // a large reported RTT directly into additional attack distance.
        double maxDistance = baseReach + 0.1;
        double expansion = 0.1;
        boolean rayHit = attack != null && attack.entityId() == victim.getEntityId()
            && frame != null && frame.environment() != null
            && CombatViewRay.intersects(eye, frame.environment().yaw(), player.getEyeLocation().getPitch(),
                box, expansion, maxDistance);
        if (rayHit || distance <= maxDistance) return;
        flagLimited(uuid, () -> plugin.flag(uuid, this,
                "eye-to-hitbox=" + String.format(java.util.Locale.ROOT, "%.2f", distance)
                    + " allowed=" + String.format(java.util.Locale.ROOT, "%.2f", maxDistance)
                    + " rewind=" + rewindMillis + "ms"
                    + " reportedPing=" + Math.max(0, player.getPing()) + "ms"));
        if (plugin.cancel(this, uuid)) event.setCancelled(true);
    }
    @Override public void forget(UUID uuid) {
        super.forget(uuid);
        attacks.remove(uuid);
        targetHistory.forget(uuid);
    }
    private static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
}

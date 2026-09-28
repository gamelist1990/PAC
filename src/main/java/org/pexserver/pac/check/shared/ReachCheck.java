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
    private record Attack(int entityId, long at) { }
    private final Map<UUID, Attack> attacks = new ConcurrentHashMap<>();
    public ReachCheck(PacPlugin plugin) { this.plugin = plugin; }
    @Override public String key() { return "reach"; }

    public void onAttackPacket(UUID uuid, int entityId) {
        attacks.put(uuid, new Attack(entityId, System.currentTimeMillis()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void onDamage(EntityDamageByEntityEvent event) {
        if (event.isCancelled() || !(event.getDamager() instanceof Player player)
                || !(event.getEntity() instanceof LivingEntity victim)) return;
        UUID uuid = player.getUniqueId();
        if (!plugin.enabled(uuid, this) || plugin.isExempt(uuid)) return;
        Attack attack = attacks.remove(uuid);
        var frame = plugin.environment().predictionFrame(uuid, System.currentTimeMillis(), player.getPing());
        Vector eye = frame == null || frame.environment() == null
            ? player.getEyeLocation().toVector()
            : new Vector(frame.environment().x(), frame.environment().y() + player.getEyeHeight(),
                frame.environment().z());
        BoundingBox box = victim.getBoundingBox();
        double x = clamp(eye.getX(), box.getMinX(), box.getMaxX()) - eye.getX();
        double y = clamp(eye.getY(), box.getMinY(), box.getMaxY()) - eye.getY();
        double z = clamp(eye.getZ(), box.getMinZ(), box.getMaxZ()) - eye.getZ();
        double distance = Math.sqrt(x*x + y*y + z*z);
        var attribute = player.getAttribute(Attribute.ENTITY_INTERACTION_RANGE);
        double baseReach = attribute == null ? 3.0 : attribute.getValue();
        double pingSeconds = Math.max(0, player.getPing()) / 1000.0;
        double maxDistance = baseReach + 0.1 + Math.min(0.75, pingSeconds * 3.0);
        double expansion = 0.1 + Math.min(0.25, pingSeconds * 0.5);
        boolean rayHit = attack != null && attack.entityId() == victim.getEntityId()
            && frame != null && frame.environment() != null
            && CombatViewRay.intersects(eye, frame.environment().yaw(), player.getEyeLocation().getPitch(),
                box, expansion, maxDistance);
        if (rayHit || distance <= maxDistance) return;
        flagLimited(uuid, () -> plugin.flag(uuid, this,
                "eye-to-hitbox=" + String.format(java.util.Locale.ROOT, "%.2f", distance)
                    + " allowed=" + String.format(java.util.Locale.ROOT, "%.2f", maxDistance)));
        if (plugin.cancel(this, uuid)) event.setCancelled(true);
    }
    @Override public void forget(UUID uuid) { super.forget(uuid); attacks.remove(uuid); }
    private static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
}

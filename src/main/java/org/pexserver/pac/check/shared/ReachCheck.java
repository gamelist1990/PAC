package org.pexserver.pac.check.shared;

import org.bukkit.attribute.Attribute;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Entity;
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
    private record Attack(int entityId, long at, long epoch) { }
    private final Map<UUID, Attack> attacks = new ConcurrentHashMap<>();
    public ReachCheck(PacPlugin plugin) { this.plugin = plugin; }
    @Override public String key() { return "reach"; }

    public void onAttackPacket(UUID uuid, int entityId) {
        attacks.put(uuid, new Attack(entityId, System.currentTimeMillis(),
                plugin.environment().teleportGeneration(uuid)));
    }

    /** Main-thread samples include nearby mobs, whose displayed positions also interpolate. */
    public void samplePlayer(Player player) {
        if (player == null || player.getWorld() == null) return;
        long now = System.currentTimeMillis();
        targetHistory.prune(now);
        targetHistory.sample(player.getUniqueId(), player.getWorld().getUID(), player.getBoundingBox(), now);
        for (Entity entity : player.getNearbyEntities(12, 12, 12)) {
            if (entity instanceof LivingEntity && entity.isValid())
                targetHistory.sample(entity.getUniqueId(), entity.getWorld().getUID(), entity.getBoundingBox(), now);
        }
    }

    BoundingBox stableTargetBox(Entity target, long now) {
        return targetHistory.stableBox(target.getUniqueId(), target.getWorld().getUID(),
                target.getBoundingBox(), now);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true) public void onDamage(EntityDamageByEntityEvent event) {
        if (event.isCancelled() || !(event.getDamager() instanceof Player player)
                || !(event.getEntity() instanceof LivingEntity victim)) return;
        if (event.getCause() != org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK
                || player.isDead() || victim.isDead() || !victim.isValid()) return;
        UUID uuid = player.getUniqueId();
        if (!plugin.enabled(uuid, this) || plugin.isExempt(uuid)) return;
        Attack attack = attacks.remove(uuid);
        long now = System.currentTimeMillis();
        // Damage events can also come from plugins and secondary attacks. Only
        // a matching, current-life ATTACK packet establishes a melee sample.
        if (attack == null || attack.entityId() != victim.getEntityId()
                || attack.epoch() != plugin.environment().teleportGeneration(uuid)
                || now < attack.at() || now - attack.at() > 200
                || plugin.environment().movementSuppressed(uuid)
                || plugin.recentExternalMotion(uuid)
                || player.isInsideVehicle() || victim.isInsideVehicle()
                || player.getPing() > 150) return;
        long attackAt = attack == null ? now : attack.at();
        // Reach is reconstructed at server receive time. Reported RTT is not
        // trusted for geometry because keepalive-only PingSpoof can inflate it
        // without delaying ATTACK or movement packets.
        long rewindMillis = CombatTargetHistory.trustedRewindMillis(player.getPing());
        var frame = plugin.environment().predictionFrame(uuid, attackAt, 0);
        if (frame == null || frame.environment() == null
                || attackAt < frame.capturedAt() || attackAt - frame.capturedAt() > 100) return;
        Vector eye = frame == null || frame.environment() == null
            ? player.getEyeLocation().toVector()
            : new Vector(frame.environment().x(), frame.environment().y() + player.getEyeHeight(),
                frame.environment().z());

        // Without acknowledged outbound entity positions, moving target geometry
        // is ambiguous. Do not turn server/client interpolation into a violation.
        BoundingBox box = stableTargetBox(victim, now);
        if (box == null) return;

        double x = clamp(eye.getX(), box.getMinX(), box.getMaxX()) - eye.getX();
        double y = clamp(eye.getY(), box.getMinY(), box.getMaxY()) - eye.getY();
        double z = clamp(eye.getZ(), box.getMinZ(), box.getMaxZ()) - eye.getZ();
        double distance = Math.sqrt(x*x + y*y + z*z);
        // The environment sampler can precede the accepted movement for this
        // attack. Either plausible eye position must be allowed to explain it.
        Vector currentEye = player.getEyeLocation().toVector();
        double cx = clamp(currentEye.getX(), box.getMinX(), box.getMaxX()) - currentEye.getX();
        double cy = clamp(currentEye.getY(), box.getMinY(), box.getMaxY()) - currentEye.getY();
        double cz = clamp(currentEye.getZ(), box.getMinZ(), box.getMaxZ()) - currentEye.getZ();
        double measuredDistance = Math.min(distance, Math.sqrt(cx*cx + cy*cy + cz*cz));
        var attribute = player.getAttribute(Attribute.ENTITY_INTERACTION_RANGE);
        double baseReach = attribute == null ? 3.0 : attribute.getValue();
        // Neither reach distance nor rewind time is derived from reported RTT.
        // This closes the remaining keepalive-only PingSpoof history-selection
        // bypass while preserving server-tick history at the ATTACK receive time.
        double maxDistance = baseReach + 0.1;
        double expansion = 0.1;
        boolean rayHit = attack != null && attack.entityId() == victim.getEntityId()
            && frame != null && frame.environment() != null
            && CombatViewRay.intersects(eye, frame.environment().yaw(), player.getEyeLocation().getPitch(),
                box, expansion, maxDistance);
        if (rayHit || measuredDistance <= maxDistance) return;
        flagLimited(uuid, () -> plugin.flag(uuid, this,
                "eye-to-hitbox=" + String.format(java.util.Locale.ROOT, "%.2f", measuredDistance)
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

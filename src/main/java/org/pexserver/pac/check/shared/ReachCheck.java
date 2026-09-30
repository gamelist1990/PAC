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
    private final CombatSceneTracker sceneTracker = new CombatSceneTracker();
    public CombatSceneTracker sceneTracker() { return sceneTracker; }
    private final CombatTargetHistory targetHistory = new CombatTargetHistory();
    private record Attack(int entityId, long at, long epoch, CombatSceneTracker.Frozen scene) { }
    private final Map<UUID, java.util.ArrayDeque<Attack>> attacks = new ConcurrentHashMap<>();
    public ReachCheck(PacPlugin plugin) { this.plugin = plugin; }
    @Override public String key() { return "reach"; }

    public void onAttackPacket(UUID uuid, int entityId) {
        long now = System.currentTimeMillis();
        var queue = attacks.computeIfAbsent(uuid, ignored -> new java.util.ArrayDeque<>());
        synchronized (queue) {
            queue.addLast(new Attack(entityId, now, plugin.environment().teleportGeneration(uuid),
                    sceneTracker.capture(uuid, entityId, now)));
            while (queue.size() > 64) queue.removeFirst();
        }
    }

    /** Main-thread samples include nearby mobs, whose displayed positions also interpolate. */
    public void samplePlayer(Player player) {
        if (player == null || player.getWorld() == null) return;
        long now = System.currentTimeMillis();
        targetHistory.prune(now);
        sceneTracker.sampleEntity(player.getEntityId(), player.getUniqueId(), now);
        targetHistory.sample(player.getUniqueId(), player.getWorld().getUID(), player.getBoundingBox(), now);
        for (Entity entity : player.getNearbyEntities(12, 12, 12)) {
            if (entity instanceof LivingEntity && entity.isValid()) {
                sceneTracker.sampleEntity(entity.getEntityId(), entity.getUniqueId(), now);
                targetHistory.sample(entity.getUniqueId(), entity.getWorld().getUID(), entity.getBoundingBox(), now);
            }
        }
    }

    BoundingBox relativeTargetShape(Entity target, long now) {
        var location = target.getLocation();
        return targetHistory.relativeShape(target.getUniqueId(), target.getWorld().getUID(),
                target.getBoundingBox(), new CombatTargetHistory.VectorOffset(location.getX(), location.getY(), location.getZ()), now);
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
        long now = System.currentTimeMillis();
        Attack attack = null;
        var queue = attacks.get(uuid);
        if (queue != null) synchronized (queue) {
            while (!queue.isEmpty() && now - queue.peekFirst().at() > 200) queue.removeFirst();
            for (var iterator = queue.iterator(); iterator.hasNext();) {
                var candidate = iterator.next();
                if (candidate.entityId() == victim.getEntityId()) { attack = candidate; iterator.remove(); break; }
            }
        }
        // Damage events can also come from plugins and secondary attacks. Only
        // a matching, current-life ATTACK packet establishes a melee sample.
        if (attack == null || attack.entityId() != victim.getEntityId()
                || attack.epoch() != plugin.environment().teleportGeneration(uuid)
                || now < attack.at() || now - attack.at() > 200
                || plugin.environment().movementSuppressed(uuid)
                || player.isInsideVehicle() || victim.isInsideVehicle()) return;
        if (attack.scene().view().expired()) {
            if (plugin.cancel(this, uuid)) event.setCancelled(true);
            return;
        }
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

        java.util.List<BoundingBox[]> segments;
        if (attack.scene().view().ready() && victim.getUniqueId().equals(attack.scene().target()))
            segments = attack.scene().view().boxes(relativeTargetShape(victim, now));
        else {
            if (attack.scene().target() != null) return;
            var stable = stableTargetBox(victim, now);
            if (stable == null || player.getPing() > 150) return;
            segments = java.util.Collections.singletonList(new BoundingBox[] {stable, stable});
        }
        // Euclidean distance to each swept union is a conservative lower bound.
        // A diagonal interpolation ray is evaluated exactly by KillAura; Reach
        // never rejects if this lower bound can explain the attack.
        Vector currentEye = player.getEyeLocation().toVector();
        double measuredDistance = Double.POSITIVE_INFINITY;
        for (var segment : segments) {
            var box = segment[0].clone().union(segment[1]);
            for (var origin : java.util.List.of(eye, currentEye)) {
                double x = clamp(origin.getX(), box.getMinX(), box.getMaxX()) - origin.getX();
                double y = clamp(origin.getY(), box.getMinY(), box.getMaxY()) - origin.getY();
                double z = clamp(origin.getZ(), box.getMinZ(), box.getMaxZ()) - origin.getZ();
                measuredDistance = Math.min(measuredDistance, Math.sqrt(x*x + y*y + z*z));
            }
        }
        var attribute = player.getAttribute(Attribute.ENTITY_INTERACTION_RANGE);
        double baseReach = attribute == null ? 3.0 : attribute.getValue();
        // Neither reach distance nor rewind time is derived from reported RTT.
        // This closes the remaining keepalive-only PingSpoof history-selection
        // bypass while preserving server-tick history at the ATTACK receive time.
        double maxDistance = baseReach + 0.1;
        if (measuredDistance <= maxDistance) return;
        final double reportedDistance = measuredDistance;
        flagLimited(uuid, () -> plugin.flag(uuid, this,
                "eye-to-hitbox=" + String.format(java.util.Locale.ROOT, "%.2f", reportedDistance)
                    + " allowed=" + String.format(java.util.Locale.ROOT, "%.2f", maxDistance)
                    + " rewind=" + rewindMillis + "ms"
                    + " reportedPing=" + Math.max(0, player.getPing()) + "ms"));
        if (plugin.cancel(this, uuid)) event.setCancelled(true);
    }
    @Override public void forget(UUID uuid) {
        super.forget(uuid);
        attacks.remove(uuid);
        targetHistory.forget(uuid);
        sceneTracker.forget(uuid);
    }
    private static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
}

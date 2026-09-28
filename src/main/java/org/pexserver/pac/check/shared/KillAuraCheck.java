package org.pexserver.pac.check.shared;

import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.pexserver.pac.PacPlugin;
import org.pexserver.pac.check.core.AbstractCheck;
import org.pexserver.pac.check.core.EventCheck;
import org.pexserver.pac.check.core.PacketCheck;
import org.pexserver.pac.check.core.PacketContext;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared Java/Bedrock melee detector. Both editions use combat-correlated
 * rotation and attack evidence; Java additionally retains MX aim analysis.
 */
public final class KillAuraCheck extends AbstractCheck implements PacketCheck, EventCheck, Listener {
    private static final long AIM_WINDOW_MILLIS = 3_500L;
    private static final long ATTACK_LOOK_MAX_AGE_MILLIS = 1_000L;

    private record Burst(int tick, Set<UUID> victims) { }
    private record PendingAttack(int entityId, long at, float yaw, float pitch, boolean hasLook) { }
    record ModelWindow(List<Float> yaw, List<Float> pitch) { }
    record ModelWindows(ModelWindow legacy, ModelWindow rnn) { }

    private static final class CombatState {
        MxAimSuite javaAim = new MxAimSuite();
        final CombatPatternMonitor combatPatterns = new CombatPatternMonitor();
        long analysisGeneration;
        long lastAttack;
        long aimConfirmedUntil;
        final RearAttackSequence rearAttacks = new RearAttackSequence();
        float latestYaw;
        float latestPitch;
        long latestLookAt;
        boolean hasLatestLook;
        PendingAttack pendingAttack;

        void resetAnalysis() {
            analysisGeneration++;
            lastAttack = 0L;
            aimConfirmedUntil = 0L;
            pendingAttack = null;
            rearAttacks.reset();
            combatPatterns.reset();
            javaAim = new MxAimSuite();
            if (hasLatestLook) javaAim.initialize(latestYaw, latestPitch);
        }
    }

    private final Map<UUID, Burst> bursts = new ConcurrentHashMap<>();
    private final Map<UUID, CombatState> combat = new ConcurrentHashMap<>();
    private final Set<UUID> deadPlayers = ConcurrentHashMap.newKeySet();
    private final PacPlugin plugin;
    private final MxAimModelRuntime mxModels;

    public KillAuraCheck(PacPlugin plugin) {
        this.plugin = plugin;
        this.mxModels = new MxAimModelRuntime(plugin.getLogger()::info,
                plugin.getLogger()::severe, plugin.getLogger()::warning);
    }

    @Override public String key() { return "kill-aura"; }
    @Override public boolean automaticKickEligible() { return true; }

    /** Java packets feed the shared combat patterns and the Java-only MX suite. */
    @Override public void inspect(PacketContext context) {
        // Geyser AuthInput is the authoritative Bedrock rotation stream. Do not
        // feed translated Bedrock packets into Java's MX model as well.
        if (context.event().isCancelled() || plugin.isBedrockPlayer(context.uuid())
                || !context.flying().hasRotationChanged()) return;
        processRotation(context.uuid(), context.location().getYaw(), context.location().getPitch(), context);
    }

    /** Bedrock AuthInput uses the Bedrock combat suite, never the Java-trained MX suite. */
    public void onBedrockRotation(UUID uuid, float yaw, float pitch) {
        if (deadPlayers.contains(uuid) || !plugin.enabled(uuid, this) || plugin.isExempt(uuid)) return;
        CombatState state = combat.computeIfAbsent(uuid, ignored -> new CombatState());
        CombatPatternMonitor.Finding finding;
        synchronized (state) {
            state.latestYaw = yaw;
            state.latestPitch = pitch;
            state.latestLookAt = System.currentTimeMillis();
            state.hasLatestLook = Float.isFinite(yaw) && Float.isFinite(pitch);
            finding = state.combatPatterns.sampleRotation(yaw, pitch, state.latestLookAt);
        }
        if (finding != null) reportCombatPattern(uuid, state, finding);
    }

    private void reportCombatPattern(UUID uuid, CombatState state, CombatPatternMonitor.Finding finding) {
        String edition = plugin.isBedrockPlayer(uuid) ? "Bedrock" : "Java";
        String detail = "Paradox " + edition + " combat " + finding.source() + ": " + finding.detail();
        synchronized (state) {
            state.aimConfirmedUntil = System.currentTimeMillis() + AIM_WINDOW_MILLIS;
        }
        flagLimited(uuid, () -> plugin.flag(uuid, this, detail, finding.metrics(), finding.weight()));
    }

    private void processRotation(UUID uuid, float yaw, float pitch, PacketContext packetContext) {
        if (deadPlayers.contains(uuid)) return;
        CombatState state = combat.computeIfAbsent(uuid, ignored -> new CombatState());
        long now = System.currentTimeMillis();

        synchronized (state) {
            if (state.lastAttack > 0L && now - state.lastAttack > AIM_WINDOW_MILLIS) {
                state.resetAnalysis();
                state.javaAim.initialize(yaw, pitch);
            }
            state.latestYaw = yaw;
            state.latestPitch = pitch;
            state.latestLookAt = now;
            state.hasLatestLook = Float.isFinite(yaw) && Float.isFinite(pitch);
            CombatPatternMonitor.Finding combatFinding = state.combatPatterns.sampleRotation(yaw, pitch, now);
            if (combatFinding != null) reportCombatPattern(uuid, state, combatFinding);
            MxAimSuite.Result result = state.javaAim.sample(yaw, pitch, now, state.lastAttack,
                    plugin.getConfig().getBoolean("killaura.ignore-cinematic", false), packetContext == null);
            if (result.modelWindows() != null) submitMxPredictions(uuid, state, result.modelWindows());
            if (!result.findings().isEmpty()) {
                List<String> details = result.findings().stream()
                        .map(finding -> finding.source() + "{" + finding.detail() + "}")
                        .distinct().toList();
                report(uuid, state, packetContext, "MX aim suite", String.join("; ", details));
            }
        }
    }

    /** Called for every decoded entity ATTACK packet, including attacks that do not land. */
    public void onAttackPacket(UUID uuid) { onAttackPacket(uuid, -1); }

    /** Saves the target id and the last client rotation in packet order. */
    public void onAttackPacket(UUID uuid, int entityId) {
        if (deadPlayers.contains(uuid) || !plugin.enabled(uuid, this) || plugin.isExempt(uuid)) return;
        CombatState state = combat.computeIfAbsent(uuid, ignored -> new CombatState());
        CombatPatternMonitor.Finding finding = null;
        synchronized (state) {
            long now = System.currentTimeMillis();
            markAttack(state, now);
            state.pendingAttack = new PendingAttack(entityId, now, state.latestYaw, state.latestPitch,
                    state.hasLatestLook);
            if (entityId >= 0)
                finding = state.combatPatterns.attackPacket(entityId, now);
        }
        if (finding != null) reportCombatPattern(uuid, state, finding);
    }

    private static void markAttack(CombatState state, long now) {
        if (state.lastAttack > 0L && now - state.lastAttack > AIM_WINDOW_MILLIS)
            state.resetAnalysis();
        state.lastAttack = now;
    }

    private void report(UUID uuid, CombatState state, PacketContext packetContext, String source, String detail) {
        String message = source + ": " + detail;
        if (packetContext == null) flagLimited(uuid, () -> plugin.flag(uuid, this, message));
        else flagLimited(packetContext, message);
        state.aimConfirmedUntil = System.currentTimeMillis() + AIM_WINDOW_MILLIS;
    }

    private void submitMxPredictions(UUID uuid, CombatState state, ModelWindows windows) {
        long generation = state.analysisGeneration;
        if (windows.rnn() != null) {
            mxModels.predictRnn(windows.rnn().yaw(), windows.rnn().pitch())
                    .thenAccept(prediction -> Bukkit.getScheduler().runTask(plugin,
                            () -> reportMxPrediction(uuid, state, generation, prediction)));
        }
        if (windows.legacy() != null) {
            mxModels.predictLegacy(windows.legacy().yaw(), windows.legacy().pitch())
                    .thenAccept(prediction -> Bukkit.getScheduler().runTask(plugin,
                            () -> reportMxPrediction(uuid, state, generation, prediction)));
        }
    }

    private void reportMxPrediction(UUID uuid, CombatState state, long generation,
                                    MxAimModelRuntime.Prediction prediction) {
        if (!prediction.flagged() || combat.get(uuid) != state) return;
        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline() || player.isDead() || deadPlayers.contains(uuid)) return;
        long now = System.currentTimeMillis();
        synchronized (state) {
            // Model inference runs asynchronously. Ignore windows that completed
            // after a death, damage reset, long combat gap, or newer session.
            if (state.analysisGeneration != generation || state.lastAttack <= 0L
                    || now - state.lastAttack > AIM_WINDOW_MILLIS) return;
            state.aimConfirmedUntil = now + AIM_WINDOW_MILLIS;
        }
        int weight = switch (prediction.severity()) {
            case UNUSUAL -> 1;
            case STRANGE -> 2;
            case SUSPECTED -> 4;
            case NORMAL -> 0;
        };
        String detail = String.format(Locale.ROOT,
                "%s pretrained aim models=%s class=%s priority=%d weight=%d result=%s",
                prediction.architecture(), prediction.models(), prediction.severity(),
                prediction.priority(), weight, prediction.detail());
        Map<String, Double> metrics = Map.of(
                "model_weight", (double) weight,
                "model_severity", (double) prediction.severity().getLevel(),
                "model_priority", (double) prediction.priority(),
                "model_count", (double) prediction.models().size());
        flagLimited(uuid, () -> plugin.flag(uuid, this, detail, metrics, weight));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (event.isCancelled() || event.getFinalDamage() <= 0
                || event.getCause() == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK
                || !(event.getDamager() instanceof Player player)) return;
        // Bukkit reports mobs, players and other attackable entities through
        // this event. Keep the combat pipeline target-agnostic so mob combat
        // cannot fall outside the same aim and hitbox checks.
        Entity victim = event.getEntity();
        UUID uuid = player.getUniqueId();
        if (player.isDead() || deadPlayers.contains(uuid) || !plugin.enabled(uuid, this)
                || plugin.isExempt(uuid) || !combatMode(player)) return;

        CombatState state = combat.computeIfAbsent(uuid, ignored -> new CombatState());
        long now = System.currentTimeMillis();
        int tick = Bukkit.getCurrentTick();
        float attackYaw;
        float attackPitch;
        boolean packetViewMatched = false;
        synchronized (state) {
            PendingAttack pending = state.pendingAttack;
            boolean matchedPacket = pending != null && pending.entityId() == victim.getEntityId()
                    && now >= pending.at() && now - pending.at() <= ATTACK_LOOK_MAX_AGE_MILLIS;
            if (matchedPacket && pending.hasLook()) {
                attackYaw = pending.yaw();
                attackPitch = pending.pitch();
                packetViewMatched = true;
            } else if (state.hasLatestLook && now - state.latestLookAt <= ATTACK_LOOK_MAX_AGE_MILLIS) {
                attackYaw = state.latestYaw;
                attackPitch = state.latestPitch;
            } else {
                attackYaw = player.getLocation().getYaw();
                attackPitch = player.getLocation().getPitch();
            }
            state.pendingAttack = null;
            // Fallback for Bedrock bridges which do not have a Java attack packet.
            if (!matchedPacket) markAttack(state, now);
            state.combatPatterns.confirmedHit(victim.getEntityId(), now);
            if (plugin.cancel(this, uuid) && now < state.aimConfirmedUntil) event.setCancelled(true);
        }

        // Wurst Killaura's optional Check line of sight defaults off. It aims at
        // the target AABB center with a look packet and immediately attacks.
        // Correlate that exact packet rotation with the successful hit, then
        // compare the ray against server block collision shapes on the main
        // thread. This avoids querying Bukkit world state from the packet thread.
        if (packetViewMatched && victim.isValid()) {
            var eyeLocation = player.getEyeLocation();
            var eye = eyeLocation.toVector();
            var targetBox = victim.getBoundingBox();
            double targetDistance = CombatViewRay.intersectionDistance(eye, attackYaw, attackPitch,
                    targetBox, 0.08, 6.0);
            if (Double.isFinite(targetDistance)) {
                var obstruction = player.getWorld().rayTraceBlocks(eyeLocation,
                        CombatViewRay.direction(attackYaw, attackPitch), targetDistance + 0.12,
                        FluidCollisionMode.NEVER, true);
                double blockDistance = obstruction == null ? Double.NaN
                        : obstruction.getHitPosition().distance(eye);
                if (CombatViewRay.blockedBeforeTarget(blockDistance, targetDistance, 0.12)) {
                    var hitBlock = obstruction.getHitBlock();
                    String detail = String.format(Locale.ROOT,
                            "attack packet ray was blocked before target AABB: block=%s block-distance=%.3f target-distance=%.3f yaw=%.2f pitch=%.2f",
                            hitBlock == null ? "unknown" : hitBlock.getType(), blockDistance, targetDistance,
                            attackYaw, attackPitch);
                    Map<String, Double> metrics = Map.of(
                            "block_distance", blockDistance,
                            "target_aabb_distance", targetDistance,
                            "attack_yaw", (double) attackYaw,
                            "attack_pitch", (double) attackPitch);
                    flagLimited(uuid, () -> plugin.flag(uuid, this, detail, metrics, 2));
                    if (plugin.cancel(this, uuid)) event.setCancelled(true);
                }
            }
        }

        Burst old = bursts.get(uuid);
        Set<UUID> targets = old != null && old.tick() == tick ? old.victims() : new HashSet<>();
        targets.add(victim.getUniqueId());
        bursts.put(uuid, new Burst(tick, targets));
        if (targets.size() >= 3) {
            flagLimited(uuid, () -> plugin.flag(uuid, this, "three distinct melee targets in one tick"));
            if (plugin.cancel(this, uuid)) event.setCancelled(true);
        }

        // Mobile clients can attack outside the camera ray in the forward
        // hemisphere. Only reject attacks where the complete target AABB is
        // behind the yaw captured with the actual attack packet.
        var eye = player.getEyeLocation().toVector();
        double maxForwardProjection = CombatViewRay.maximumHorizontalProjection(
                eye, attackYaw, victim.getBoundingBox());
        boolean rearHit = CombatViewRay.entirelyBehind(eye, attackYaw, victim.getBoundingBox(), 0.03);
        boolean confirmedRearHit;
        int rearStreak;
        synchronized (state) {
            confirmedRearHit = state.rearAttacks.record(rearHit, tick);
            rearStreak = state.rearAttacks.count();
        }
        if (confirmedRearHit) {
            String detail = String.format(Locale.ROOT,
                    "consecutive attacks behind facing hemisphere target=%s entityId=%d yaw=%.1f aabb-forward=%.3f streak=%d",
                    victim.getType(), victim.getEntityId(), attackYaw, maxForwardProjection, rearStreak);
            Map<String, Double> metrics = Map.of(
                    "target_aabb_forward_projection", maxForwardProjection,
                    "attack_yaw", (double) attackYaw,
                    "rear_attack_streak", (double) rearStreak);
            flagLimited(uuid, () -> plugin.flag(uuid, this, detail, metrics));
            if (plugin.cancel(this, uuid)) event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.getTo() == null) return;
        UUID uuid = event.getPlayer().getUniqueId();
        if (!plugin.enabled(uuid, this) || plugin.isExempt(uuid)) return;
        CombatState state = combat.computeIfAbsent(uuid, ignored -> new CombatState());
        long now = System.currentTimeMillis();
        synchronized (state) {
            boolean moved = event.getFrom().getX() != event.getTo().getX()
                    || event.getFrom().getY() != event.getTo().getY()
                    || event.getFrom().getZ() != event.getTo().getZ();
            boolean rotationChanged = event.getFrom().getYaw() != event.getTo().getYaw()
                    || event.getFrom().getPitch() != event.getTo().getPitch();
            if (!plugin.isBedrockPlayer(uuid) && moved && !rotationChanged) {
                for (MxAimSuite.Finding finding : state.javaAim.noRotation(now, state.lastAttack))
                    report(uuid, state, null, finding.source(), finding.detail());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        deadPlayers.remove(uuid);
        CombatState state = combat.computeIfAbsent(uuid, ignored -> new CombatState());
        synchronized (state) {
            state.latestYaw = event.getPlayer().getLocation().getYaw();
            state.latestPitch = event.getPlayer().getLocation().getPitch();
            state.latestLookAt = System.currentTimeMillis();
            state.hasLatestLook = true;
            state.resetAnalysis();
            state.javaAim.initialize(state.latestYaw, state.latestPitch);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onIncomingDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        resetForLifecycle(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        deadPlayers.add(event.getEntity().getUniqueId());
        resetForLifecycle(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        deadPlayers.remove(event.getPlayer().getUniqueId());
        resetForLifecycle(event.getPlayer());
    }

    private void resetForLifecycle(Player player) {
        UUID uuid = player.getUniqueId();
        CombatState state = combat.computeIfAbsent(uuid, ignored -> new CombatState());
        synchronized (state) {
            state.latestYaw = player.getLocation().getYaw();
            state.latestPitch = player.getLocation().getPitch();
            state.latestLookAt = System.currentTimeMillis();
            state.hasLatestLook = true;
            state.resetAnalysis();
            state.javaAim.initialize(state.latestYaw, state.latestPitch);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        CombatState state = combat.computeIfAbsent(uuid, ignored -> new CombatState());
        synchronized (state) {
            state.latestYaw = event.getTo().getYaw();
            state.latestPitch = event.getTo().getPitch();
            state.hasLatestLook = true;
            state.resetAnalysis();
            state.javaAim.initialize(state.latestYaw, state.latestPitch);
            state.javaAim.teleported(System.currentTimeMillis());
        }
    }

    private static boolean combatMode(Player player) {
        return player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE;
    }

    @Override public void forget(UUID uuid) {
        super.forget(uuid);
        bursts.remove(uuid);
        combat.remove(uuid);
        deadPlayers.remove(uuid);
    }

    public void close() { mxModels.close(); }

}


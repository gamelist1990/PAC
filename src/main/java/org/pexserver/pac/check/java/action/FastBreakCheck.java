package org.pexserver.pac.check.java.action;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockDamageAbortEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.pexserver.pac.PacPlugin;
import org.pexserver.pac.check.core.AbstractCheck;
import org.pexserver.pac.check.core.PacketCheck;
import org.pexserver.pac.check.core.PacketContext;

import java.util.Map;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/** Compares an early finish packet with the server's block damage speed. */
public final class FastBreakCheck extends AbstractCheck implements PacketCheck, Listener {
    private static final long MIN_REBREAK_DELAY_NANOS = 200_000_000L;
    static record PendingFinish(UUID worldId, int x, int y, int z,
                                long expiresAtNanos, String detail) {
        boolean matches(UUID candidateWorld, int candidateX, int candidateY,
                        int candidateZ, long nowNanos) {
            return nowNanos <= expiresAtNanos && worldId.equals(candidateWorld)
                    && x == candidateX && y == candidateY && z == candidateZ;
        }

        boolean expired(long nowNanos) { return nowNanos > expiresAtNanos; }
    }

    private static final class Dig {
        final int x, y, z, startTick;
        final long startedAtNanos;
        final UUID worldId;
        final BlockData blockState;
        final float initialProgress;
        final int minimumVanillaTicks;
        final BreakProgressEstimator progress;

        Dig(Block block, int startTick, float initialProgress, long nowNanos) {
            x = block.getX(); y = block.getY(); z = block.getZ();
            worldId = block.getWorld().getUID();
            blockState = block.getBlockData();
            this.startTick = startTick;
            startedAtNanos = nowNanos;
            this.initialProgress = initialProgress;
            minimumVanillaTicks = minimumVanillaElapsedTicks(initialProgress);
            progress = new BreakProgressEstimator(initialProgress, nowNanos);
        }

        boolean matches(Block block) {
            return worldId.equals(block.getWorld().getUID()) && x == block.getX()
                    && y == block.getY() && z == block.getZ()
                    && blockState.equals(block.getBlockData());
        }
    }
    private final PacPlugin plugin;
    private final Map<UUID, Dig> digs = new ConcurrentHashMap<>();
    private final Map<UUID, PendingFinish> pendingFinishes = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastAcceptedBreakNanos = new ConcurrentHashMap<>();
    /** Main-thread event identities awaiting the final cancellation state. */
    private final Set<BlockBreakEvent> clientBreakEvents =
            Collections.newSetFromMap(new WeakHashMap<>());
    public FastBreakCheck(PacPlugin plugin) { this.plugin = plugin; }
    @Override public String key() { return "fast-break"; }
    @Override public void inspect(PacketContext context) { }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(BlockDamageEvent event) {
        var player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        // A fresh server-side damage start is a new mining attempt. An early
        // finish from a previous attempt must never bleed into this one.
        pendingFinishes.remove(uuid);
        if (player.getGameMode() != GameMode.SURVIVAL || plugin.isBedrockPlayer(uuid)
                || plugin.isExempt(uuid) || !plugin.enabled(uuid, this)) return;
        // Haste 255 and high mining-speed attributes make individual blocks
        // instant, but vanilla still retains its next-block destroy delay.
        // Track the accepted break so Wurst's destroyDelay=0 remains visible.
        float speed = event.getBlock().getBreakSpeed(player);
        if (!Float.isFinite(speed) || speed <= 0) return;
        var block = event.getBlock();
        int tick = Bukkit.getCurrentTick();
        long now = System.nanoTime();
        digs.compute(uuid, (ignored, current) -> {
            if (current != null && current.matches(block)) {
                current.progress.sample(speed, now);
                return current;
            }
            return new Dig(block, tick, speed, now);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDamageAbort(BlockDamageAbortEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        Block block = event.getBlock();
        Dig dig = digs.get(uuid);
        if (dig != null && dig.matches(block)) digs.remove(uuid, dig);
        PendingFinish pending = pendingFinishes.get(uuid);
        if (pending != null && pending.matches(block.getWorld().getUID(),
                block.getX(), block.getY(), block.getZ(), System.nanoTime()))
            pendingFinishes.remove(uuid, pending);
    }

    /** Samples changing haste, tools, water state and other server-side break modifiers. */
    public void sampleActiveBreaks() {
        long now = System.nanoTime();
        pendingFinishes.entrySet().removeIf(entry -> entry.getValue().expired(now));
        for (var entry : digs.entrySet()) {
            UUID uuid = entry.getKey();
            Dig dig = entry.getValue();
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline() || player.getGameMode() != GameMode.SURVIVAL
                    || plugin.isBedrockPlayer(uuid) || plugin.isExempt(uuid)
                    || !plugin.enabled(uuid, this)) {
                digs.remove(uuid, dig);
                continue;
            }
            Block block = player.getWorld().getBlockAt(dig.x, dig.y, dig.z);
            if (!dig.matches(block)) {
                digs.remove(uuid, dig);
                continue;
            }
            dig.progress.sample(block.getBreakSpeed(player), now);
        }
    }

    /** Called on the packet thread; world reads stay in onDamage. */
    public void onDigging(PacketReceiveEvent event) {
        UUID uuid = event.getUser().getUUID();
        if (uuid == null || plugin.isBedrockPlayer(uuid)
                || plugin.isExempt(uuid, this) || !plugin.enabled(uuid, this)) return;
        WrapperPlayClientPlayerDigging packet = new WrapperPlayClientPlayerDigging(event);
        DiggingAction action = packet.getAction();
        if (action == DiggingAction.START_DIGGING) {
            long now = System.nanoTime();
            long previousBreak = lastAcceptedBreakNanos.getOrDefault(uuid, 0L);
            if (tooSoonAfterPreviousBreak(now, previousBreak)) {
                long elapsedMillis = (now - previousBreak) / 1_000_000L;
                if (plugin.cancel(this, uuid)) event.setCancelled(true);
                flagLimited(uuid, () -> plugin.flag(uuid, this,
                        "new block damage started " + elapsedMillis
                                + " ms after the previous accepted break; vanilla client delay is 250 ms"));
                return;
            }
            digs.remove(uuid);
            pendingFinishes.remove(uuid);
            return;
        }
        if (action == DiggingAction.CANCELLED_DIGGING) {
            digs.remove(uuid);
            pendingFinishes.remove(uuid);
            return;
        }
        if (action != DiggingAction.FINISHED_DIGGING) return;
        Vector3i position = packet.getBlockPosition();
        Dig current = digs.get(uuid);
        if (current != null && current.x == position.getX() && current.y == position.getY()
                && current.z == position.getZ()) {
            int elapsedTicks = Bukkit.getCurrentTick() - current.startTick;
            long finishAtNanos = System.nanoTime();
            var estimate = current.progress.estimate(finishAtNanos);
            long elapsedMillis = Math.max(0, finishAtNanos - current.startedAtNanos) / 1_000_000L;
            boolean tooEarly = tooEarly(estimate.progress(), current.initialProgress);
            if (tooEarly) {
                String detail = String.format(java.util.Locale.ROOT,
                        "finish after %d server ticks / %d ms; modeled vanilla client progress %.5f + first-tick %.5f < 1.0 (server stop grace is 0.7); initial destroy progress %.5f needs about %d server ticks to reach grace",
                        elapsedTicks, elapsedMillis, estimate.progress(), current.initialProgress,
                        current.initialProgress, current.minimumVanillaTicks);
                pendingFinishes.put(uuid, new PendingFinish(current.worldId, current.x, current.y,
                        current.z, pendingExpiry(finishAtNanos,
                                minimumVanillaDestroyTicks(current.initialProgress),
                                elapsedTicks), detail));
                // FastBreak sends STOP_DESTROY_BLOCK on each client progress
                // update. Keep the attempt so later stops refresh evidence until
                // vanilla accepts the break or the client starts/aborts mining.
            }
            // Keep an on-time attempt until BlockBreakEvent finishes. It proves
            // that an accepted break came from this client's mining session.
        }
    }

    /** Only adjudicate an early finish if the server would otherwise accept the block break. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        Player player = event.getPlayer();
        if (player.getGameMode() != GameMode.SURVIVAL || plugin.isBedrockPlayer(uuid)
                || plugin.isExempt(uuid, this) || !plugin.enabled(uuid, this)) return;
        Dig attempted = digs.get(uuid);
        if (attempted != null && attempted.matches(event.getBlock())) clientBreakEvents.add(event);
        PendingFinish pending = pendingFinishes.get(uuid);
        long now = System.nanoTime();
        if (pending != null && pending.expired(now)) {
            pendingFinishes.remove(uuid, pending);
            pending = null;
        }
        Block block = event.getBlock();
        if (pending != null && pending.matches(block.getWorld().getUID(),
                block.getX(), block.getY(), block.getZ(), now)) {
            PendingFinish earlyFinish = pending;
            Dig current = digs.get(uuid);
            if (current == null || !current.matches(block)) {
                pendingFinishes.remove(uuid, pending);
                return;
            }
            var estimate = current.progress.estimate(now);
            if (reachedVanillaFinish(estimate.progress(), current.initialProgress)) {
                // A STOP below 0.7 enters Paper's delayed-destroy path, which
                // later completes at 1.0. Do not mistake that normal wait for
                // a successful speedup merely because an early STOP was sent.
                pendingFinishes.remove(uuid, pending);
                digs.remove(uuid, current);
                return;
            }
            if (!pendingFinishes.remove(uuid, pending)) return;
            digs.remove(uuid, current);
            if (plugin.cancel(this, uuid)) event.setCancelled(true);
            flagLimited(uuid, () -> plugin.flag(uuid, this, earlyFinish.detail()));
            return;
        }
    }

    /** Only completed, uncancelled breaks start the client's next-target delay. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onAcceptedBreak(BlockBreakEvent event) {
        if (!clientBreakEvents.remove(event) || event.isCancelled()) return;
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (player.getGameMode() != GameMode.SURVIVAL || plugin.isBedrockPlayer(uuid)
                || plugin.isExempt(uuid, this) || !plugin.enabled(uuid, this)) return;
        lastAcceptedBreakNanos.put(uuid, System.nanoTime());
        Dig current = digs.get(uuid);
        if (current != null && current.matches(event.getBlock())) digs.remove(uuid, current);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTeleport(PlayerTeleportEvent event) {
        clearAttempt(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        clearAttempt(event.getPlayer().getUniqueId());
    }

    private void clearAttempt(UUID uuid) {
        digs.remove(uuid);
        pendingFinishes.remove(uuid);
        lastAcceptedBreakNanos.remove(uuid);
    }

    static boolean tooSoonAfterPreviousBreak(long nowNanos, long previousBreakNanos) {
        return previousBreakNanos > 0 && nowNanos >= previousBreakNanos
                && nowNanos - previousBreakNanos < MIN_REBREAK_DELAY_NANOS;
    }

    /** Keep early-finish evidence through the server's vanilla delayed-destroy window. */
    static long pendingExpiry(long finishAtNanos, int minimumDestroyTicks, int elapsedTicks) {
        long remainingTicks = minimumDestroyTicks == Integer.MAX_VALUE
                ? 6_000L : Math.max(0L, (long) minimumDestroyTicks - elapsedTicks);
        long remainingMillis = Math.max(2_000L, Math.min(300_000L, remainingTicks * 50L + 1_000L));
        return finishAtNanos + remainingMillis * 1_000_000L;
    }

    /** Paper's delayed-destroy tick path removes the block at progress 1.0, not the 0.7 stop threshold. */
    static int minimumVanillaDestroyTicks(float destroyProgress) {
        if (!Float.isFinite(destroyProgress) || destroyProgress <= 0) return Integer.MAX_VALUE;
        double estimate = Math.ceil(1.0 / destroyProgress) - 1;
        if (estimate >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
        int elapsed = Math.max(0, (int) estimate);
        while (elapsed > 0 && (float) (elapsed + 1) * destroyProgress >= 1.0f) elapsed--;
        while ((float) (elapsed + 1) * destroyProgress < 1.0f) {
            if (elapsed == Integer.MAX_VALUE - 1) return Integer.MAX_VALUE;
            elapsed++;
        }
        return elapsed;
    }

    /** The vanilla client sends STOP only after its own block progress reaches 1.0. */
    static boolean tooEarly(double estimatedProgress, float initialProgress) {
        if (!Double.isFinite(estimatedProgress) || !Float.isFinite(initialProgress)
                || initialProgress <= 0) return false;
        // The first client progress update may occur in the same tick as START.
        // Model that once, with a small numerical margin; a full destroy-speed
        // tick of tolerance would conceal the server's 0.7 early-stop exploit.
        return estimatedProgress + initialProgress + 0.02 < 0.98;
    }

    static boolean reachedVanillaFinish(double estimatedProgress, float initialProgress) {
        return !tooEarly(estimatedProgress, initialProgress);
    }

    /** Mirrors ServerPlayerGameMode's STOP_DESTROY_BLOCK test in the running server. */
    static int minimumVanillaElapsedTicks(float destroyProgress) {
        if (!Float.isFinite(destroyProgress) || destroyProgress <= 0) return Integer.MAX_VALUE;
        double estimate = Math.ceil((double) 0.7f / destroyProgress) - 1;
        if (estimate >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
        int elapsed = Math.max(0, (int) estimate);
        while (elapsed > 0 && (float) elapsed * destroyProgress >= 0.7f) elapsed--;
        while ((float) (elapsed + 1) * destroyProgress < 0.7f) {
            if (elapsed == Integer.MAX_VALUE - 1) return Integer.MAX_VALUE;
            elapsed++;
        }
        return elapsed;
    }

    @Override public void forget(UUID uuid) {
        super.forget(uuid);
        digs.remove(uuid);
        pendingFinishes.remove(uuid);
        lastAcceptedBreakNanos.remove(uuid);
    }
}

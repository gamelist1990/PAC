package org.pexserver.pac.packet;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClientStatus;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientCloseWindow;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerInput;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientTeleportConfirm;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientVehicleMove;
import org.pexserver.pac.PacPlugin;
import org.pexserver.pac.check.shared.KillAuraCheck;
import org.pexserver.pac.check.shared.VehicleMovementCheck;
import org.pexserver.pac.check.java.packet.CriticalPacketCheck;
import org.pexserver.pac.check.java.packet.PacketFloodCheck;
import org.pexserver.pac.check.java.movement.TimerPredictionCheck;
import org.pexserver.pac.check.java.movement.AirPredictionCheck;
import org.pexserver.pac.check.java.action.InventoryMoveCheck;
import org.pexserver.pac.check.java.action.InventoryMoveTracker;
import org.pexserver.pac.movement.MotionPredictor;
import org.pexserver.pac.movement.MotionCollisionSnapshot;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerRiptideEvent;
import org.bukkit.event.player.PlayerFishEvent;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Packet transport adapter; detector logic lives in independent modules. */
public final class PacketChecks implements PacketListener, Listener {
    public record ServerMotionGrant(long sequence, double horizontalSpeed,
                                    double velocityX, double velocityY, double velocityZ,
                                    long until) {
        public ServerMotionGrant(long sequence, double horizontalSpeed, long until) {
            this(sequence, horizontalSpeed, 0, 0, 0, until);
        }
    }
    private final PacPlugin plugin;
    private final ConcurrentHashMap<UUID, org.pexserver.pac.movement.MovementLatencyWindow> latencyWindows = new ConcurrentHashMap<>();
    private final JavaInputCapture inputs = new JavaInputCapture();
    private final ExternalMotionTracker externalMotion = new ExternalMotionTracker();
    private final ConcurrentHashMap<UUID, Long> motionSequencesDelivered = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, ServerMotionGrant> serverMotionGrants = new ConcurrentHashMap<>();
    private final AtomicLong nextServerMotionGrant = new AtomicLong();
    public PacketChecks(PacPlugin plugin) { this.plugin = plugin; }
    public OutgoingMotionPackets outgoingListener() {
        return new OutgoingMotionPackets(plugin, externalMotion, this);
    }
    public boolean recentExternalMotion(UUID uuid) {
        return externalMotion.current(uuid, System.currentTimeMillis()) != null;
    }
    public boolean recentPluginVelocity(UUID uuid) {
        return serverMotionGrant(uuid) != null;
    }
    public ServerMotionGrant serverMotionGrant(UUID uuid) {
        ServerMotionGrant grant = serverMotionGrants.get(uuid);
        if (grant == null) return null;
        if (System.currentTimeMillis() <= grant.until()) return grant;
        serverMotionGrants.remove(uuid, grant);
        return null;
    }
    static long serverMotionDuration(double horizontalSpeed, double verticalSpeed) {
        if (!Double.isFinite(horizontalSpeed) || horizontalSpeed < 0
                || !Double.isFinite(verticalSpeed)) return 0;
        // Follow the externally selected velocity until its remaining tail is
        // smaller than ordinary per-tick movement. A 0.3 cutoff discarded most
        // plugin velocities immediately, even though they still materially
        // alter the next several client physics steps (especially on ice).
        double ticks = horizontalSpeed <= 0.03 ? 0
                : Math.log(0.03 / horizontalSpeed) / Math.log(0.91);
        // Downward setVelocity is just as authoritative as an upward launch.
        double verticalTicks = Math.abs(verticalSpeed) / 0.08;
        return Math.max(300, Math.min(3_000,
                (long) Math.ceil(Math.max(ticks, verticalTicks) * 50 + 300)));
    }
    public long externalMotionSequence(UUID uuid) {
        var impulse = externalMotion.current(uuid, System.currentTimeMillis());
        return impulse == null ? 0 : impulse.sequence();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCombatDamage(EntityDamageByEntityEvent event) {
        if (event.getFinalDamage() <= 0 || !(event.getEntity() instanceof org.bukkit.entity.Player player)) return;
        externalMotion.markCombatDamage(player.getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFishingPull(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_ENTITY
                || !(event.getCaught() instanceof org.bukkit.entity.Player target)
                || event.getHook() == null) return;
        var owner = event.getPlayer().getLocation();
        var hook = event.getHook().getLocation();
        MotionPredictor.Motion impulse = fishingPull(owner.getX(), owner.getY(), owner.getZ(),
                hook.getX(), hook.getY(), hook.getZ());
        if (Math.abs(impulse.dx()) + Math.abs(impulse.dy()) + Math.abs(impulse.dz()) <= 1.0e-8)
            return;
        // Paper fires CAUGHT_ENTITY immediately before NMS FishingHook#pullEntity
        // and then broadcasts entity status 31 so the local target applies the
        // exact same additive pull. Tracking it as additive motion lets the
        // existing knockback-response replay distinguish the legitimate pull
        // from clients that suppress only the local entity-status effect.
        externalMotion.addImpulse(target.getUniqueId(),
                impulse.dx(), impulse.dy(), impulse.dz(), System.currentTimeMillis());
    }

    static MotionPredictor.Motion fishingPull(double ownerX, double ownerY, double ownerZ,
                                              double hookX, double hookY, double hookZ) {
        return new MotionPredictor.Motion((ownerX - hookX) * 0.1,
                (ownerY - hookY) * 0.1, (ownerZ - hookZ) * 0.1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRiptide(PlayerRiptideEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (!(plugin.checks().get("air-prediction") instanceof AirPredictionCheck airPrediction)
                || !plugin.enabled(uuid, airPrediction) || plugin.isExempt(uuid)) return;
        var origin = event.getPlayer().getLocation();
        var prior = event.getPlayer().getVelocity();
        var impulse = event.getVelocity();
        // Paper fires PlayerRiptideEvent immediately before Player#push with
        // this exact vanilla impulse. The client performs the riptide locally,
        // so retain the server's unmodified final velocity as the authority.
        airPrediction.onRiptide(uuid, origin.getX(), origin.getY(), origin.getZ(),
                prior.getX() + impulse.getX(),
                prior.getY() + impulse.getY(),
                prior.getZ() + impulse.getZ(),
                System.currentTimeMillis());
    }

    /**
     * Capture Bukkit API velocity changes as well as their eventual network packet.
     * Some plugins change velocity through the event pipeline, so the authoritative
     * vector should already be available when the next client movement arrives.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerVelocity(PlayerVelocityEvent event) {
        var velocity = event.getVelocity();
        UUID uuid = event.getPlayer().getUniqueId();
        long now = System.currentTimeMillis();
        externalMotion.velocity(uuid, velocity.getX(), velocity.getY(), velocity.getZ(), now);
        noteServerVelocity(uuid, velocity.getX(), velocity.getY(), velocity.getZ(), now, false);
    }

    void outgoingVelocity(UUID uuid, double x, double y, double z, long now) {
        noteServerVelocity(uuid, x, y, z, now, true);
    }

    private void noteServerVelocity(UUID uuid, double x, double y, double z,
                                    long now, boolean outbound) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return;
        double horizontal = Math.hypot(x, z);
        if (externalMotion.recentCombatDamage(uuid, now)) {
            serverMotionGrants.remove(uuid);
            return;
        }
        // Every velocity packet replaces client motion, including small vectors
        // and (0,0,0). Size thresholds turn legitimate plugin motion into an
        // apparent gravity/speed violation. Combat velocity remains handled by
        // the stricter knockback-response path above.
        ServerMotionGrant previous = serverMotionGrants.get(uuid);
        if (outbound && previous != null
                && Math.abs(previous.horizontalSpeed() - horizontal) < 0.001
                && previous.until() > now) return;
        serverMotionGrants.put(uuid, new ServerMotionGrant(nextServerMotionGrant.incrementAndGet(),
                horizontal, x, y, z, now + serverMotionDuration(horizontal, y)));
    }

    @Override public void onPacketReceive(PacketReceiveEvent event) {
        UUID eventUuid = event.getUser().getUUID();
        if (eventUuid != null && !plugin.isBedrockPlayer(eventUuid) && plugin.environment() != null) {
            ClientVersion version = event.getUser().getClientVersion();
            if (version != null && version != ClientVersion.UNKNOWN) {
                plugin.environment().stepProfile(eventUuid, stepProfile(version));
            }
        }
        if (eventUuid != null && event.getPacketType() == PacketType.Play.Client.CLIENT_STATUS) {
            var status = new WrapperPlayClientClientStatus(event);
            if (status.getAction() == WrapperPlayClientClientStatus.Action.OPEN_INVENTORY_ACHIEVEMENT
                    && plugin.inventoryMove() != null)
                plugin.inventoryMove().playerInventoryOpened(eventUuid);
            return;
        }
        if (eventUuid != null && event.getPacketType() == PacketType.Play.Client.CLICK_WINDOW) {
            // Current Java clients do not always announce opening their own
            // inventory. A click in window 0 proves that screen is open.
            var click = new WrapperPlayClientClickWindow(event);
            if (click.getWindowId() == 0 && plugin.inventoryMove() != null)
                plugin.inventoryMove().playerInventoryOpened(eventUuid);
        }
        if (eventUuid != null && event.getPacketType() == PacketType.Play.Client.CLOSE_WINDOW) {
            var close = new WrapperPlayClientCloseWindow(event);
            if (close.getWindowId() == 0 && plugin.inventoryMove() != null)
                plugin.inventoryMove().clientWindowClosed(eventUuid);
            return;
        }
        if (event.getPacketType() == PacketType.Play.Client.INTERACT_ENTITY) {
            UUID uuid = event.getUser().getUUID();
            if (uuid == null) return;
            WrapperPlayClientInteractEntity interaction = new WrapperPlayClientInteractEntity(event);
            if (interaction.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                if (plugin.checks().get("critical-packet") instanceof CriticalPacketCheck critical)
                    critical.onAttackPacket(uuid, event);
                if (!event.isCancelled()
                        && plugin.checks().get("kill-aura") instanceof KillAuraCheck killAura)
                    killAura.onAttackPacket(uuid, interaction.getEntityId());
                if (!event.isCancelled()
                        && plugin.checks().get("reach") instanceof org.pexserver.pac.check.shared.ReachCheck reach)
                    reach.onAttackPacket(uuid, interaction.getEntityId());
                if (!event.isCancelled()
                        && plugin.checks().get("air-prediction") instanceof AirPredictionCheck airPrediction)
                    airPrediction.onAttackPacket(uuid);
            }
            return;
        }
        if (event.getPacketType() == PacketType.Play.Client.VEHICLE_MOVE) {
            UUID uuid = eventUuid;
            if (uuid == null || plugin.isExempt(uuid)) return;
            if (plugin.checks().get("vehicle-movement") instanceof VehicleMovementCheck vehicleMovement
                    && plugin.enabled(uuid, vehicleMovement)) {
                var packet = new WrapperPlayClientVehicleMove(event);
                var position = packet.getPosition();
                if (vehicleMovement.onVehicleMovePacket(uuid,
                        position.getX(), position.getY(), position.getZ(),
                        System.currentTimeMillis())) {
                    event.setCancelled(true);
                }
            }
            return;
        }
        if (event.getPacketType() == PacketType.Play.Client.TELEPORT_CONFIRM) {
            UUID uuid = event.getUser().getUUID();
            if (uuid != null) plugin.environment().teleportConfirmed(uuid,
                    new WrapperPlayClientTeleportConfirm(event).getTeleportId());
            return;
        }
        if (event.getPacketType() == PacketType.Play.Client.PLAYER_INPUT) {
            if (event.isCancelled()) return;
            UUID uuid = eventUuid;
            if (uuid == null) return;
            var packet = new WrapperPlayClientPlayerInput(event);
            InventoryMoveCheck inventoryMove = plugin.inventoryMove();
            InventoryMoveTracker.Input filtered = inventoryMove == null
                    ? new InventoryMoveTracker.Input(packet.isForward(), packet.isBackward(),
                    packet.isLeft(), packet.isRight(), packet.isJump(), packet.isShift(), packet.isSprint())
                    : inventoryMove.onInput(uuid, event, packet, System.currentTimeMillis());
            inputs.update(uuid, new MotionPredictor.Input(filtered.forward(), filtered.backward(),
                    filtered.left(), filtered.right(), filtered.jump(), filtered.shift(), filtered.sprint()));
            return;
        }
        if (event.getPacketType() == PacketType.Play.Client.PLAYER_DIGGING) {
            if (plugin.nuker() != null) plugin.nuker().onDigging(event);
            if (!event.isCancelled()) plugin.fastBreak().onDigging(event);
            return;
        }
        if (!WrapperPlayClientPlayerFlying.isFlying(event.getPacketType())) return;
        UUID uuid = eventUuid;
        if (uuid == null || plugin.isExempt(uuid)) return;
        WrapperPlayClientPlayerFlying flying = new WrapperPlayClientPlayerFlying(event);
        long now = System.currentTimeMillis();
        var externalUpdates = externalMotion.since(uuid,
                motionSequencesDelivered.getOrDefault(uuid, 0L), now);
        var inputWindow = inputs.nextMovement(uuid);
        var serverTiming = plugin.environment().serverTiming().snapshot(System.nanoTime());
        var context = new org.pexserver.pac.check.core.PacketContext(plugin, uuid, event, flying,
                flying.getLocation(), inputWindow,
                externalUpdates, plugin.environment().teleportGeneration(uuid),
                latencyWindows.computeIfAbsent(uuid, ignored -> new org.pexserver.pac.movement.MovementLatencyWindow())
                        .uncertain(now)
                        || serverTiming.recovering()
                        || plugin.environment().poseTransitionUncertain(uuid, now), serverTiming);
        plugin.checks().dispatch(context);
        if (flying.hasPositionChanged() && !event.isCancelled()) {
            inputs.acceptedPosition(uuid, inputWindow);
            if (externalUpdates != null)
                motionSequencesDelivered.merge(uuid, externalUpdates.sequence(), Math::max);
            if (plugin.checks().get("packet-flood") instanceof PacketFloodCheck flood) {
                var location = context.location();
                flood.acceptedPosition(uuid, location.getX(), location.getY(), location.getZ());
            }
            if (plugin.checks().get("timer-prediction") instanceof TimerPredictionCheck timer) {
                var location = context.location();
                timer.acceptedPosition(uuid, context.movementEpoch(),
                        location.getX(), location.getY(), location.getZ());
            }
        }
    }

    public void forget(UUID uuid) {
        inputs.forget(uuid);
        latencyWindows.remove(uuid);
        externalMotion.forget(uuid);
        motionSequencesDelivered.remove(uuid);
        serverMotionGrants.remove(uuid);
    }

    static MotionCollisionSnapshot.StepProfile stepProfile(ClientVersion version) {
        if (version.isNewerThanOrEquals(ClientVersion.V_1_21))
            return MotionCollisionSnapshot.StepProfile.V1_21_PLUS;
        if (version.isNewerThanOrEquals(ClientVersion.V_1_14))
            return MotionCollisionSnapshot.StepProfile.V1_14_TO_1_20;
        if (version.isNewerThanOrEquals(ClientVersion.V_1_8))
            return MotionCollisionSnapshot.StepProfile.V1_8_TO_1_13;
        return MotionCollisionSnapshot.StepProfile.PRE_1_8;
    }
}

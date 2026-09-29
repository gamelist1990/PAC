package org.pexserver.pac.check.shared;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.util.Vector;
import org.pexserver.pac.PacPlugin;
import org.pexserver.pac.check.core.AbstractCheck;
import org.pexserver.pac.check.core.EventCheck;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Conservative vehicle-transition protection. The dismount branch blocks the
 * large self-velocity used by VehicleBoost; generic non-boat vehicle flight is
 * alert-only because server plugins may intentionally implement custom vehicles.
 */
public final class VehicleMovementCheck extends AbstractCheck implements EventCheck, Listener {
    private static final long DISMOUNT_WINDOW_MILLIS = 300;
    private static final double MIN_DISMOUNT_HORIZONTAL_LIMIT = 1.25;
    private static final double MIN_DISMOUNT_UP_LIMIT = 0.80;
    private static final double GENERIC_HORIZONTAL_LIMIT = 1.50;
    private static final double GENERIC_VERTICAL_LIMIT = 0.90;
    private static final int GENERIC_REPEAT_TICKS = 2;
    private static final long PACKET_SNAPSHOT_MAX_AGE_MILLIS = 175;
    private static final double PACKET_MIN_HORIZONTAL_LIMIT = 2.0;
    private static final double PACKET_MIN_VERTICAL_LIMIT = 1.25;

    static final class DismountBoostWindow {
        private long expiresAt;
        private double vehicleHorizontal;
        private double vehicleUp;

        void arm(long now, double vehicleDx, double vehicleDy, double vehicleDz) {
            expiresAt = now + DISMOUNT_WINDOW_MILLIS;
            vehicleHorizontal = Math.hypot(vehicleDx, vehicleDz);
            vehicleUp = Math.max(0, vehicleDy);
        }

        Finding sample(long now, double dx, double dy, double dz, boolean invalidated) {
            if (invalidated || now > expiresAt || expiresAt == 0) {
                reset();
                return Finding.valid();
            }
            double horizontal = Math.hypot(dx, dz);
            double legalHorizontal = Math.max(MIN_DISMOUNT_HORIZONTAL_LIMIT,
                    vehicleHorizontal * 1.5 + 0.45);
            double legalUp = Math.max(MIN_DISMOUNT_UP_LIMIT, vehicleUp * 1.5 + 0.35);
            boolean impossible = horizontal > legalHorizontal || dy > legalUp;
            // Only the first actual movement step after dismount is relevant.
            reset();
            return new Finding(impossible, horizontal, dy, legalHorizontal, legalUp);
        }

        void reset() {
            expiresAt = 0;
            vehicleHorizontal = vehicleUp = 0;
        }
    }

    static final class GenericVehicleWindow {
        private int lastTick = Integer.MIN_VALUE;
        private double x, y, z;
        private boolean previousAirborne;
        private int extremeHorizontalTicks;
        private int extremeVerticalTicks;

        Finding sample(int tick, double nx, double ny, double nz, boolean airborne,
                       boolean invalidated) {
            if (invalidated || lastTick == Integer.MIN_VALUE || tick != lastTick + 1
                    || !Double.isFinite(nx) || !Double.isFinite(ny) || !Double.isFinite(nz)) {
                seed(tick, nx, ny, nz, airborne);
                return Finding.valid();
            }
            double dx = nx - x, dy = ny - y, dz = nz - z;
            double horizontal = Math.hypot(dx, dz);
            extremeHorizontalTicks = horizontal > GENERIC_HORIZONTAL_LIMIT
                    ? extremeHorizontalTicks + 1 : 0;
            extremeVerticalTicks = airborne && previousAirborne && Math.abs(dy) > GENERIC_VERTICAL_LIMIT
                    ? extremeVerticalTicks + 1 : 0;
            x = nx; y = ny; z = nz; lastTick = tick; previousAirborne = airborne;
            boolean impossible = extremeHorizontalTicks >= GENERIC_REPEAT_TICKS
                    || extremeVerticalTicks >= GENERIC_REPEAT_TICKS;
            return impossible
                    ? new Finding(true, horizontal, dy, GENERIC_HORIZONTAL_LIMIT, GENERIC_VERTICAL_LIMIT)
                    : Finding.valid();
        }

        private void seed(int tick, double nx, double ny, double nz, boolean airborne) {
            x = nx; y = ny; z = nz; lastTick = tick; previousAirborne = airborne;
            extremeHorizontalTicks = extremeVerticalTicks = 0;
        }
    }

    record Finding(boolean impossible, double horizontal, double dy,
                   double legalHorizontal, double legalVertical) {
        static Finding valid() { return new Finding(false, 0, 0, 0, 0); }
    }

    private record PacketVehicleSnapshot(UUID vehicleId, String type,
                                         double x, double y, double z,
                                         double velocityX, double velocityY, double velocityZ,
                                         boolean gravity, boolean airborne, boolean inWater,
                                         long sampledAt) { }

    record VehiclePacketFinding(boolean evaluated, boolean impossible,
                                double horizontal, double vertical,
                                double legalHorizontal, double legalVertical,
                                String type) {
        static VehiclePacketFinding skipped() {
            return new VehiclePacketFinding(false, false, 0, 0, 0, 0, "");
        }
    }

    private record VehicleState(UUID driver, GenericVehicleWindow window) { }

    private final PacPlugin plugin;
    private final Map<UUID, DismountBoostWindow> dismounts = new HashMap<>();
    private final Map<UUID, VehicleState> vehicles = new HashMap<>();
    private final ConcurrentHashMap<UUID, PacketVehicleSnapshot> packetVehicles =
            new ConcurrentHashMap<>();

    public VehicleMovementCheck(PacPlugin plugin) {
        this.plugin = plugin;
    }

    @Override public String key() { return "vehicle-movement"; }
    @Override public boolean automaticBanEligible() { return false; }
    @Override public boolean automaticKickEligible() { return false; }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExit(VehicleExitEvent event) {
        if (!(event.getExited() instanceof Player player)) return;
        UUID uuid = player.getUniqueId();
        if (!plugin.enabled(uuid, this) || plugin.isExempt(uuid)) return;
        Vector velocity = event.getVehicle().getVelocity();
        dismounts.computeIfAbsent(uuid, ignored -> new DismountBoostWindow())
                .arm(System.currentTimeMillis(), velocity.getX(), velocity.getY(), velocity.getZ());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        DismountBoostWindow window = dismounts.get(uuid);
        if (window == null || event.getTo() == null) return;

        boolean invalidated = player.isInsideVehicle()
                || plugin.recentExternalMotion(uuid)
                || plugin.recentPluginVelocity(uuid)
                || plugin.environment() != null && plugin.environment().movementSuppressed(uuid);
        double dx = event.getTo().getX() - event.getFrom().getX();
        double dy = event.getTo().getY() - event.getFrom().getY();
        double dz = event.getTo().getZ() - event.getFrom().getZ();
        Finding finding = window.sample(System.currentTimeMillis(), dx, dy, dz, invalidated);
        if (!finding.impossible()) return;

        flagLimited(uuid, () -> plugin.flag(uuid, this, String.format(java.util.Locale.ROOT,
                "post-dismount boost: horizontal=%.3f legal=%.3f dy=%.3f legalUp=%.3f",
                finding.horizontal(), finding.legalHorizontal(),
                finding.dy(), finding.legalVertical())));
        if (plugin.cancel(this, uuid)) event.setTo(event.getFrom());
    }

    /**
     * Packet-thread safe prefilter for ServerboundMoveVehiclePacket. It only
     * rejects displacement far outside the most recent server vehicle motion;
     * smaller vehicle-specific anomalies remain telemetry until they have an
     * exact vanilla model.
     */
    public boolean onVehicleMovePacket(UUID uuid, double targetX, double targetY,
                                       double targetZ, long now) {
        PacketVehicleSnapshot state = packetVehicles.get(uuid);
        if (state == null || now < state.sampledAt()
                || now - state.sampledAt() > PACKET_SNAPSHOT_MAX_AGE_MILLIS
                || !Double.isFinite(targetX) || !Double.isFinite(targetY)
                || !Double.isFinite(targetZ))
            return false;

        VehiclePacketFinding finding = packetFinding(
                state.x(), state.y(), state.z(),
                state.velocityX(), state.velocityY(), state.velocityZ(),
                targetX, targetY, targetZ, state.type());
        if (!finding.impossible()) return false;

        flagLimited(uuid, () -> plugin.flag(uuid, this, String.format(java.util.Locale.ROOT,
                "vehicle packet displacement: type=%s horizontal=%.3f legal=%.3f dy=%.3f legalY=%.3f",
                finding.type(), finding.horizontal(), finding.legalHorizontal(),
                finding.vertical(), finding.legalVertical())));
        return plugin.cancel(this, uuid);
    }

    static VehiclePacketFinding packetFinding(double serverX, double serverY, double serverZ,
                                              double velocityX, double velocityY, double velocityZ,
                                              double targetX, double targetY, double targetZ,
                                              String type) {
        if (!Double.isFinite(serverX) || !Double.isFinite(serverY) || !Double.isFinite(serverZ)
                || !Double.isFinite(velocityX) || !Double.isFinite(velocityY) || !Double.isFinite(velocityZ)
                || !Double.isFinite(targetX) || !Double.isFinite(targetY) || !Double.isFinite(targetZ))
            return VehiclePacketFinding.skipped();
        double dx = targetX - serverX;
        double dy = targetY - serverY;
        double dz = targetZ - serverZ;
        double horizontal = Math.hypot(dx, dz);
        double velocityHorizontal = Math.hypot(velocityX, velocityZ);
        double legalHorizontal = Math.max(PACKET_MIN_HORIZONTAL_LIMIT,
                velocityHorizontal * 3.0 + 0.75);
        double legalVertical = Math.max(PACKET_MIN_VERTICAL_LIMIT,
                Math.abs(velocityY) * 3.0 + 0.55);
        return new VehiclePacketFinding(true,
                horizontal > legalHorizontal || Math.abs(dy) > legalVertical,
                horizontal, dy, legalHorizontal, legalVertical, type == null ? "" : type);
    }

    /** Poll controlled non-boat vehicles because not every impossible state emits VehicleMoveEvent. */
    public void sampleOnlineVehicles() {
        int tick = Bukkit.getCurrentTick();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Entity vehicle = player.getVehicle();
            UUID uuid = player.getUniqueId();
            if (vehicle == null || vehicle.getPassengers().isEmpty()
                    || !vehicle.getPassengers().get(0).getUniqueId().equals(uuid)) {
                packetVehicles.remove(uuid);
                continue;
            }

            Location sampled = vehicle.getLocation();
            Vector sampledVelocity = vehicle.getVelocity();
            packetVehicles.put(uuid, new PacketVehicleSnapshot(
                    vehicle.getUniqueId(), vehicle.getType().name(),
                    sampled.getX(), sampled.getY(), sampled.getZ(),
                    sampledVelocity.getX(), sampledVelocity.getY(), sampledVelocity.getZ(),
                    vehicle.hasGravity(),
                    vehicle.hasGravity() && !vehicle.isOnGround() && !vehicle.isInWater(),
                    vehicle.isInWater(), System.currentTimeMillis()));

            if (vehicle instanceof Boat) continue;
            UUID vehicleId = vehicle.getUniqueId();
            if (!plugin.enabled(uuid, this) || plugin.isExempt(uuid)) {
                vehicles.remove(vehicleId);
                continue;
            }

            VehicleState state = vehicles.get(vehicleId);
            if (state == null || !state.driver().equals(uuid)) {
                state = new VehicleState(uuid, new GenericVehicleWindow());
                vehicles.put(vehicleId, state);
            }
            Location location = sampled;
            boolean airborne = vehicle.hasGravity() && !vehicle.isOnGround() && !vehicle.isInWater();
            Finding finding = state.window().sample(tick, location.getX(), location.getY(), location.getZ(),
                    airborne, false);
            if (finding.impossible()) {
                // This branch is deliberately alert-only. Custom server vehicles can exceed
                // vanilla transport speeds, so it must not become an automatic setback/BAN signal.
                flagLimited(uuid, () -> plugin.flag(uuid, this, String.format(java.util.Locale.ROOT,
                        "non-boat vehicle motion: type=%s horizontal=%.3f dy=%.3f limitH=%.3f limitY=%.3f",
                        vehicle.getType(), finding.horizontal(), finding.dy(),
                        finding.legalHorizontal(), finding.legalVertical())));
            }
        }

        Iterator<Map.Entry<UUID, VehicleState>> iterator = vehicles.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, VehicleState> entry = iterator.next();
            Entity entity = Bukkit.getEntity(entry.getKey());
            if (entity == null || entity.getPassengers().isEmpty()) iterator.remove();
        }
    }

    @Override public void forget(UUID uuid) {
        super.forget(uuid);
        dismounts.remove(uuid);
        packetVehicles.remove(uuid);
        vehicles.entrySet().removeIf(entry -> entry.getValue().driver().equals(uuid));
    }
}

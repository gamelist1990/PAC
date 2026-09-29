package org.pexserver.pac.check.shared;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.util.Vector;
import org.pexserver.pac.PacPlugin;
import org.pexserver.pac.check.core.AbstractCheck;
import org.pexserver.pac.check.core.EventCheck;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/** Shared Java/Bedrock boat-flight check. Samples ridden boats even when they stop moving. */
public final class BoatFlightCheck extends AbstractCheck implements EventCheck, Listener {
    private static final int ROLLBACK_TICKS = 80;
    // Keep evidence across the short dismount/remount sequence used by
    // vehicle "rehook" bypasses. Longer gaps are treated as a new ride.
    private static final int REHOOK_PRESERVE_TICKS = 4;
    private static final double HOVER_FALL_LIMIT = -0.025;
    private static final double FAST_BOAT_SPEED_SQUARED = 0.49;
    // AbstractBoat.floatBoat() applies 0.9 air friction, then controlBoat()
    // can add at most 0.04 horizontal speed from the rider's input.
    private static final double AIR_FRICTION = 0.9;
    private static final double MAX_AIR_INPUT = 0.04;
    private static final double AIR_SPEED_TOLERANCE = 0.02;
    private static final double AIR_VECTOR_TOLERANCE = 0.025;
    private static final int AIR_STEERING_REPEATS = 3;
    // AbstractBoat.floatBoat() subtracts 0.04 from Y velocity every air tick.
    // Wurst's BoatFly replaces Y velocity before this step when jump is pressed.
    private static final double AIR_GRAVITY = 0.04;
    private static final double AIR_VERTICAL_TOLERANCE = 0.015;
    private static final double AIR_VERTICAL_EVIDENCE = 0.10;
    private static final double AIR_VERTICAL_EVIDENCE_DECAY = 0.02;
    private static final int AIR_VERTICAL_REPEATS = 3;

    static final class AirSpeedWindow {
        private double previousHorizontal;
        private boolean previousAirborne;
        private int fastTicks;

        boolean sample(boolean airborne, double horizontal) {
            boolean excessive = airborne && previousAirborne && previousHorizontal > 0.05
                    && horizontal * horizontal > FAST_BOAT_SPEED_SQUARED
                    && horizontal > legalHorizontal();
            fastTicks = excessive ? fastTicks + 1 : 0;
            previousHorizontal = horizontal;
            previousAirborne = airborne;
            return excessive;
        }

        double legalHorizontal() {
            return previousHorizontal * AIR_FRICTION + MAX_AIR_INPUT + AIR_SPEED_TOLERANCE;
        }

        int fastTicks() { return fastTicks; }

        void reset() {
            previousHorizontal = 0.0;
            previousAirborne = false;
            fastTicks = 0;
        }
    }

    static final class AirVerticalWindow {
        private double previousDy;
        private boolean previousAirborne;
        private int airTicks;
        private int accelerationTicks;
        private double excess;

        boolean sample(boolean airborne, double dy) {
            if (!airborne) {
                reset();
                return false;
            }
            if (!previousAirborne) {
                previousAirborne = true;
                previousDy = dy;
                airTicks = 1;
                return false;
            }

            airTicks++;
            double acceleration = dy - (previousDy - AIR_GRAVITY);
            if (dy > 0.005 && acceleration > AIR_VERTICAL_TOLERANCE) {
                accelerationTicks++;
                excess = Math.min(1.0, excess + acceleration);
            } else {
                excess = Math.max(0.0, excess - AIR_VERTICAL_EVIDENCE_DECAY);
                if (excess < AIR_VERTICAL_TOLERANCE) accelerationTicks = 0;
            }
            previousDy = dy;
            return airTicks >= 4 && accelerationTicks >= AIR_VERTICAL_REPEATS
                    && excess >= AIR_VERTICAL_EVIDENCE;
        }

        int accelerationTicks() { return accelerationTicks; }
        double excess() { return excess; }

        void reset() {
            previousDy = 0.0;
            previousAirborne = false;
            airTicks = 0;
            accelerationTicks = 0;
            excess = 0.0;
        }
    }

    /** Compare the complete horizontal velocity, so a turn cannot hide behind legal speed. */
    static final class AirVectorWindow {
        private double previousDx, previousDz;
        private boolean previousAirborne;
        private int steeringTicks;
        private double excess;

        boolean sample(boolean airborne, double dx, double dz) {
            if (!airborne || !Double.isFinite(dx) || !Double.isFinite(dz)) {
                reset();
                return false;
            }
            if (!previousAirborne) {
                previousAirborne = true;
                previousDx = dx;
                previousDz = dz;
                return false;
            }
            double requiredInput = Math.hypot(dx - previousDx * AIR_FRICTION,
                    dz - previousDz * AIR_FRICTION);
            excess = Math.max(0.0, requiredInput - MAX_AIR_INPUT - AIR_VECTOR_TOLERANCE);
            // A single packet or vehicle interpolation jump must not become a violation.
            steeringTicks = excess > 0.035 ? steeringTicks + 1 : 0;
            previousDx = dx;
            previousDz = dz;
            return steeringTicks >= AIR_STEERING_REPEATS;
        }

        int steeringTicks() { return steeringTicks; }
        double excess() { return excess; }
        void reset() {
            previousDx = previousDz = 0.0;
            previousAirborne = false;
            steeringTicks = 0;
            excess = 0.0;
        }
    }

    private static final class State {
        int lastTick = Integer.MIN_VALUE;
        Location lastLocation;
        Location lastSafeLocation;
        Location fastAnchor;
        Location rollbackAnchor;
        int hoverTicks;
        final AirSpeedWindow airSpeed = new AirSpeedWindow();
        final AirVerticalWindow airVertical = new AirVerticalWindow();
        final AirVectorWindow airVector = new AirVectorWindow();
        int correctionUntilTick;
        UUID driver;
    }

    private final Map<UUID, State> samples = new HashMap<>();
    private final PacPlugin plugin;

    public BoatFlightCheck(PacPlugin plugin) { this.plugin = plugin; }
    @Override public String key() { return "boat-flight"; }

    /**
     * A VehicleMoveEvent is not fired for a boat that Wurst has frozen in the
     * air. Poll only boats currently ridden by online players, once per tick.
     */
    public void sampleOnlineVehicles() {
        int tick = Bukkit.getCurrentTick();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Entity vehicle = player.getVehicle();
            if (vehicle instanceof Boat boat && !boat.getPassengers().isEmpty()
                    && boat.getPassengers().get(0).getUniqueId().equals(player.getUniqueId())) {
                sample(boat, player, tick);
            }
        }
        Iterator<Map.Entry<UUID, State>> iterator = samples.entrySet().iterator();
        while (iterator.hasNext()) {
            State state = iterator.next().getValue();
            if (tick - state.lastTick > REHOOK_PRESERVE_TICKS) iterator.remove();
        }
    }

    @EventHandler public void onMove(VehicleMoveEvent event) {
        if (!(event.getVehicle() instanceof Boat boat)) return;
        Player driver = controllingPlayer(boat);
        if (driver != null) sample(boat, driver, Bukkit.getCurrentTick());
    }

    private Player controllingPlayer(Boat boat) {
        if (boat.getPassengers().isEmpty() || !(boat.getPassengers().get(0) instanceof Player player))
            return null;
        return player;
    }

    private void sample(Boat boat, Player driver, int tick) {
        UUID boatId = boat.getUniqueId();
        UUID uuid = driver.getUniqueId();
        if (!plugin.enabled(uuid, this) || plugin.isExempt(uuid)) {
            samples.remove(boatId);
            return;
        }
        State state = samples.computeIfAbsent(boatId, ignored -> new State());
        if (state.lastTick == tick) return;

        Location current = boat.getLocation();
        boolean airborne = boat.getStatus() == Boat.Status.IN_AIR && boat.hasGravity();
        boolean sameDriver = state.driver == null || state.driver.equals(uuid);
        if (!sameDriver) reset(state);
        state.driver = uuid;

        // While a confirmed boat is being corrected, keep enforcing the first
        // grounded/water anchor. A one-shot teleport lets the client resume BoatFly.
        if (tick < state.correctionUntilTick && plugin.cancel(this, uuid)) {
            enforceCorrection(boat, state);
            state.lastLocation = state.lastSafeLocation == null ? current.clone() : state.lastSafeLocation.clone();
            state.lastTick = tick;
            state.airSpeed.reset();
            state.airVertical.reset();
            state.airVector.reset();
            return;
        }

        int tickGap = state.lastTick == Integer.MIN_VALUE
                ? Integer.MAX_VALUE : tick - state.lastTick;
        boolean sameWorld = state.lastLocation != null
                && state.lastLocation.getWorld().equals(current.getWorld());
        boolean preserveRehook = preserveShortRehookGap(
                tickGap, sameDriver, sameWorld);
        if (state.lastLocation == null || state.lastTick == Integer.MIN_VALUE
                || tickGap != 1 || !sameWorld) {
            state.lastLocation = current.clone();
            if (!airborne) state.lastSafeLocation = current.clone();
            state.lastTick = tick;
            if (!preserveRehook) {
                state.hoverTicks = 0;
                state.airSpeed.reset();
                state.airVertical.reset();
                state.airVector.reset();
                state.fastAnchor = null;
            }
            return;
        }

        double dy = current.getY() - state.lastLocation.getY();
        double dx = current.getX() - state.lastLocation.getX();
        double dz = current.getZ() - state.lastLocation.getZ();
        double horizontalSquared = dx * dx + dz * dz;
        double horizontal = Math.sqrt(horizontalSquared);
        double legalHorizontal = state.airSpeed.legalHorizontal();
        boolean fastHorizontal = state.airSpeed.sample(airborne, horizontal);
        boolean acceleratedUpward = state.airVertical.sample(airborne, dy);
        boolean impossibleSteering = state.airVector.sample(airborne, dx, dz);
        if (fastHorizontal && state.airSpeed.fastTicks() == 1) state.fastAnchor = state.lastLocation.clone();
        if (!fastHorizontal) state.fastAnchor = null;
        if (!airborne) {
            state.hoverTicks = 0;
            state.lastSafeLocation = current.clone();
        } else {
            state.hoverTicks = dy >= HOVER_FALL_LIMIT ? state.hoverTicks + 1 : 0;
        }
        state.lastLocation = current.clone();
        state.lastTick = tick;

        int hoverThreshold = Math.max(5, plugin.getConfig().getInt("detectors.boat-flight.hover-ticks", 8));
        int fastThreshold = Math.max(2, plugin.getConfig().getInt("detectors.boat-flight.fast-ticks", 4));
        boolean hover = airborne && state.hoverTicks >= hoverThreshold;
        boolean fast = airborne && state.airSpeed.fastTicks() >= fastThreshold;
        if (!hover && !fast && !acceleratedUpward && !impossibleSteering) return;

        String detail = String.format(java.util.Locale.ROOT,
                "boat flight: dy=%.3f horizontal=%.3f legalHorizontal=%.3f hoverTicks=%d fastTicks=%d verticalTicks=%d verticalExcess=%.3f steeringTicks=%d steeringExcess=%.3f status=%s",
                dy, horizontal, legalHorizontal, state.hoverTicks, state.airSpeed.fastTicks(),
                state.airVertical.accelerationTicks(), state.airVertical.excess(),
                state.airVector.steeringTicks(), state.airVector.excess(), boat.getStatus());
        flagLimited(uuid, () -> plugin.flag(uuid, this, detail));
        if (plugin.cancel(this, uuid)) {
            state.rollbackAnchor = fast && state.fastAnchor != null ? state.fastAnchor.clone()
                    : state.lastSafeLocation == null ? current.clone() : state.lastSafeLocation.clone();
            state.correctionUntilTick = tick + ROLLBACK_TICKS;
            enforceCorrection(boat, state);
        }
    }

    private void enforceCorrection(Boat boat, State state) {
        Location anchor = state.rollbackAnchor == null ? state.lastSafeLocation : state.rollbackAnchor;
        if (anchor == null || !anchor.getWorld().equals(boat.getWorld())) return;
        boat.teleport(anchor);
        boat.setVelocity(new Vector(0, 0, 0));
    }

    private static void reset(State state) {
        state.lastTick = Integer.MIN_VALUE;
        state.lastLocation = null;
        state.lastSafeLocation = null;
        state.fastAnchor = null;
        state.rollbackAnchor = null;
        state.hoverTicks = 0;
        state.airSpeed.reset();
        state.airVertical.reset();
        state.airVector.reset();
        state.correctionUntilTick = 0;
    }

    static boolean preserveShortRehookGap(int tickGap, boolean sameDriver, boolean sameWorld) {
        return tickGap > 1 && tickGap <= REHOOK_PRESERVE_TICKS
                && sameDriver && sameWorld;
    }
}

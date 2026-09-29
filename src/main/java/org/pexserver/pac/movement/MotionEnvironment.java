package org.pexserver.pac.movement;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.block.CraftBlock;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import io.papermc.paper.event.entity.EntityCollideWithEntityEvent;
import com.destroystokyo.paper.event.player.PlayerElytraBoostEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.block.data.Openable;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.util.BoundingBox;
import org.pexserver.pac.check.shared.EntityCollisionPolicy;
import org.pexserver.pac.movement.ground.GroundStateService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.component.UseEffects;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Main-thread world sampling. Packet listeners consume immutable snapshots only. */
public final class MotionEnvironment implements Listener {
    private static final double BLOCK_CHANGE_MARGIN = 1.25;
    private static final int MAX_COLLISION_SHAPE_CACHE_ENTRIES = 32_768;
    private record BlockCoordinate(UUID world, int x, int y, int z) { }

    public record Snapshot(boolean ordinaryGround, boolean ordinaryAir,
                           boolean sprinting, boolean sneaking, boolean usingItem,
                           float yaw, double movementSpeed,
                           double x, double y, double z,
                           int tick, long capturedAt,
                           boolean wallAdjacent, boolean waterSurface,
                           float groundFriction,
                           double gravity, float horizontalDrag, float verticalDrag,
                           float jumpStrength, boolean slowFalling,
                           int levitationAmplifier, boolean verticalAir,
                           float sneakingSpeed, float itemUseMultiplier,
                           float stuckHorizontalMultiplier, float stuckVerticalMultiplier,
                           boolean gravityAirborne, float blockSpeedFactor,
                           boolean specialVerticalSurface) {
        public Snapshot(boolean ordinaryGround, boolean ordinaryAir,
                        boolean sprinting, boolean sneaking, boolean usingItem,
                        float yaw, double movementSpeed,
                        double x, double y, double z,
                        int tick, long capturedAt,
                        boolean wallAdjacent, boolean waterSurface,
                        float groundFriction,
                        double gravity, float horizontalDrag, float verticalDrag,
                        float jumpStrength, boolean slowFalling,
                        int levitationAmplifier, boolean verticalAir,
                        float sneakingSpeed, float itemUseMultiplier,
                        float stuckHorizontalMultiplier, float stuckVerticalMultiplier,
                        boolean gravityAirborne) {
            this(ordinaryGround, ordinaryAir, sprinting, sneaking, usingItem,
                    yaw, movementSpeed, x, y, z, tick, capturedAt,
                    wallAdjacent, waterSurface, groundFriction,
                    gravity, horizontalDrag, verticalDrag, jumpStrength,
                    slowFalling, levitationAmplifier, verticalAir,
                    sneakingSpeed, itemUseMultiplier,
                    stuckHorizontalMultiplier, stuckVerticalMultiplier,
                    gravityAirborne, 1.0f, false);
        }
        public Snapshot(boolean ordinaryGround, boolean ordinaryAir,
                        boolean sprinting, boolean sneaking, boolean usingItem,
                        float yaw, double movementSpeed,
                        double x, double y, double z,
                        int tick, long capturedAt,
                        boolean wallAdjacent, boolean waterSurface,
                        float groundFriction,
                        double gravity, float horizontalDrag, float verticalDrag,
                        float jumpStrength, boolean slowFalling,
                        int levitationAmplifier, boolean verticalAir,
                        float sneakingSpeed, float itemUseMultiplier,
                        float stuckHorizontalMultiplier, float stuckVerticalMultiplier) {
            this(ordinaryGround, ordinaryAir, sprinting, sneaking, usingItem,
                    yaw, movementSpeed, x, y, z, tick, capturedAt,
                    wallAdjacent, waterSurface, groundFriction,
                    gravity, horizontalDrag, verticalDrag, jumpStrength,
                    slowFalling, levitationAmplifier, verticalAir,
                    sneakingSpeed, itemUseMultiplier,
                    stuckHorizontalMultiplier, stuckVerticalMultiplier, verticalAir);
        }
        public Snapshot withMovementSpeed(double speed) {
            return new Snapshot(ordinaryGround, ordinaryAir, sprinting, sneaking, usingItem,
                    yaw, speed, x, y, z, tick, capturedAt, wallAdjacent, waterSurface, groundFriction,
                    gravity, horizontalDrag, verticalDrag, jumpStrength, slowFalling,
                    levitationAmplifier, verticalAir, sneakingSpeed, itemUseMultiplier,
                    stuckHorizontalMultiplier, stuckVerticalMultiplier, gravityAirborne,
                    blockSpeedFactor, specialVerticalSurface);
        }
                public Snapshot withSprinting(boolean sprinting, double speed) {
                    return new Snapshot(ordinaryGround, ordinaryAir, sprinting, sneaking, usingItem,
                        yaw, speed, x, y, z, tick, capturedAt, wallAdjacent, waterSurface, groundFriction,
                        gravity, horizontalDrag, verticalDrag, jumpStrength, slowFalling,
                        levitationAmplifier, verticalAir, sneakingSpeed, itemUseMultiplier,
                        stuckHorizontalMultiplier, stuckVerticalMultiplier, gravityAirborne,
                        blockSpeedFactor, specialVerticalSurface);
                }
        public Snapshot withBlockSpeedFactor(float factor) {
            return new Snapshot(ordinaryGround, ordinaryAir, sprinting, sneaking, usingItem,
                    yaw, movementSpeed, x, y, z, tick, capturedAt, wallAdjacent, waterSurface, groundFriction,
                    gravity, horizontalDrag, verticalDrag, jumpStrength, slowFalling,
                    levitationAmplifier, verticalAir, sneakingSpeed, itemUseMultiplier,
                    stuckHorizontalMultiplier, stuckVerticalMultiplier, gravityAirborne,
                    factor, specialVerticalSurface);
        }
        public Snapshot withSpecialVerticalSurface(boolean special) {
            return new Snapshot(ordinaryGround, ordinaryAir, sprinting, sneaking, usingItem,
                    yaw, movementSpeed, x, y, z, tick, capturedAt, wallAdjacent, waterSurface, groundFriction,
                    gravity, horizontalDrag, verticalDrag, jumpStrength, slowFalling,
                    levitationAmplifier, verticalAir, sneakingSpeed, itemUseMultiplier,
                    stuckHorizontalMultiplier, stuckVerticalMultiplier, gravityAirborne,
                    blockSpeedFactor, special);
        }
        public Snapshot(boolean ordinaryGround, boolean ordinaryAir,
                        boolean sprinting, boolean sneaking, boolean usingItem,
                        float yaw, double movementSpeed,
                        double x, double y, double z,
                        int tick, long capturedAt,
                        boolean wallAdjacent, boolean waterSurface,
                        float groundFriction,
                        double gravity, float horizontalDrag, float verticalDrag,
                        float jumpStrength, boolean slowFalling,
                        int levitationAmplifier, boolean verticalAir,
                        float sneakingSpeed, float itemUseMultiplier) {
            this(ordinaryGround, ordinaryAir, sprinting, sneaking, usingItem,
                    yaw, movementSpeed, x, y, z, tick, capturedAt,
                    wallAdjacent, waterSurface, groundFriction,
                    gravity, horizontalDrag, verticalDrag, jumpStrength,
                    slowFalling, levitationAmplifier, verticalAir,
                    sneakingSpeed, itemUseMultiplier, 1.0f, 1.0f);
        }
        public Snapshot(boolean ordinaryGround, boolean ordinaryAir,
                        boolean sprinting, boolean sneaking, boolean usingItem,
                        float yaw, double movementSpeed,
                        double x, double y, double z,
                        int tick, long capturedAt,
                        boolean wallAdjacent, boolean waterSurface,
                        float groundFriction,
                        double gravity, float horizontalDrag, float verticalDrag,
                        float jumpStrength, boolean slowFalling,
                        int levitationAmplifier, boolean verticalAir) {
            this(ordinaryGround, ordinaryAir, sprinting, sneaking, usingItem,
                    yaw, movementSpeed, x, y, z, tick, capturedAt,
                    wallAdjacent, waterSurface, groundFriction,
                    gravity, horizontalDrag, verticalDrag, jumpStrength,
                    slowFalling, levitationAmplifier, verticalAir,
                    0.3f, usingItem ? 0.2f : 1.0f, 1.0f, 1.0f);
        }
        public Snapshot(boolean ordinaryGround, boolean ordinaryAir,
                        boolean sprinting, boolean sneaking, boolean usingItem,
                        float yaw, double movementSpeed,
                        double x, double y, double z,
                        int tick, long capturedAt,
                        boolean wallAdjacent, boolean waterSurface,
                        float groundFriction,
                        double gravity, float horizontalDrag, float verticalDrag,
                        float jumpStrength, boolean slowFalling,
                        int levitationAmplifier) {
            this(ordinaryGround, ordinaryAir, sprinting, sneaking, usingItem,
                    yaw, movementSpeed, x, y, z, tick, capturedAt,
                    wallAdjacent, waterSurface, groundFriction,
                    gravity, horizontalDrag, verticalDrag, jumpStrength,
                    slowFalling, levitationAmplifier, ordinaryAir, 0.3f,
                    usingItem ? 0.2f : 1.0f, 1.0f, 1.0f);
        }
        public Snapshot(boolean ordinaryGround, boolean ordinaryAir,
                        boolean sprinting, boolean sneaking, boolean usingItem,
                        float yaw, double movementSpeed,
                        double x, double y, double z,
                        int tick, long capturedAt,
                        boolean wallAdjacent, boolean waterSurface,
                        float groundFriction,
                        double gravity, float horizontalDrag, float verticalDrag,
                        float jumpStrength, boolean slowFalling) {
            this(ordinaryGround, ordinaryAir, sprinting, sneaking, usingItem,
                    yaw, movementSpeed, x, y, z, tick, capturedAt,
                    wallAdjacent, waterSurface, groundFriction,
                    gravity, horizontalDrag, verticalDrag, jumpStrength, slowFalling, -1, ordinaryAir,
                    0.3f, usingItem ? 0.2f : 1.0f, 1.0f, 1.0f);
        }
        public Snapshot(boolean ordinaryGround, boolean ordinaryAir,
                        boolean sprinting, boolean sneaking, boolean usingItem,
                        float yaw, double movementSpeed,
                        double x, double y, double z,
                        int tick, long capturedAt,
                        boolean wallAdjacent, boolean waterSurface,
                        float groundFriction,
                        double gravity, float horizontalDrag, float verticalDrag,
                        float jumpStrength) {
            this(ordinaryGround, ordinaryAir, sprinting, sneaking, usingItem,
                    yaw, movementSpeed, x, y, z, tick, capturedAt,
                    wallAdjacent, waterSurface, groundFriction,
                    gravity, horizontalDrag, verticalDrag, jumpStrength, false, -1, ordinaryAir,
                    0.3f, usingItem ? 0.2f : 1.0f, 1.0f, 1.0f);
        }
        public Snapshot(boolean ordinaryGround, boolean ordinaryAir,
                        boolean sprinting, boolean sneaking, boolean usingItem,
                        float yaw, double movementSpeed,
                        double x, double y, double z,
                        int tick, long capturedAt,
                        boolean wallAdjacent, boolean waterSurface,
                        float groundFriction,
                        double gravity, float horizontalDrag, float verticalDrag) {
            this(ordinaryGround, ordinaryAir, sprinting, sneaking, usingItem,
                    yaw, movementSpeed, x, y, z, tick, capturedAt,
                    wallAdjacent, waterSurface, groundFriction,
                    gravity, horizontalDrag, verticalDrag, 0.42f, false, -1, ordinaryAir,
                    0.3f, usingItem ? 0.2f : 1.0f, 1.0f, 1.0f);
        }
        public Snapshot(boolean ordinaryGround, boolean ordinaryAir,
                        boolean sprinting, boolean sneaking, boolean usingItem,
                        float yaw, double movementSpeed,
                        double x, double y, double z,
                        int tick, long capturedAt,
                        boolean wallAdjacent, boolean waterSurface,
                        float groundFriction) {
            this(ordinaryGround, ordinaryAir, sprinting, sneaking, usingItem,
                    yaw, movementSpeed, x, y, z, tick, capturedAt,
                    wallAdjacent, waterSurface, groundFriction,
                    0.08, 0.91f, 0.98f, 0.42f, false, -1, ordinaryAir,
                    0.3f, usingItem ? 0.2f : 1.0f, 1.0f, 1.0f);
        }
        public Snapshot(boolean ordinaryGround, boolean ordinaryAir,
                        boolean sprinting, boolean sneaking, boolean usingItem,
                        float yaw, double movementSpeed,
                        double x, double y, double z,
                        int tick, long capturedAt,
                        boolean wallAdjacent, boolean waterSurface) {
            this(ordinaryGround, ordinaryAir, sprinting, sneaking, usingItem,
                    yaw, movementSpeed, x, y, z, tick, capturedAt,
                    wallAdjacent, waterSurface, 0.6f,
                    0.08, 0.91f, 0.98f, 0.42f, false, -1, ordinaryAir,
                    0.3f, 1.0f, 1.0f, 1.0f);
        }
        public Snapshot(boolean ordinaryGround, boolean ordinaryAir,
                        boolean sprinting, boolean sneaking,
                        float yaw, double movementSpeed,
                        double x, double y, double z,
                        int tick, long capturedAt) {
            this(ordinaryGround, ordinaryAir, sprinting, sneaking, false,
                    yaw, movementSpeed, x, y, z, tick, capturedAt,
                    false, false, 0.6f, 0.08, 0.91f, 0.98f, 0.42f, false, -1, ordinaryAir,
                    0.3f, 1.0f, 1.0f, 1.0f);
        }
        public boolean near(double x, double y, double z) {
            return Math.abs(this.x - x) < 1.5 && Math.abs(this.y - y) < 1.5
                    && Math.abs(this.z - z) < 1.5;
        }
    }
    private final Map<UUID, Snapshot> snapshots = new ConcurrentHashMap<>();
    /** Reused across ticks; each hit is validated against the current block data. */
    private final BoundedStateCache<BlockCoordinate, BlockState,
            List<MotionCollisionSnapshot.Box>> collisionShapeCache =
            new BoundedStateCache<>(MAX_COLLISION_SHAPE_CACHE_ENTRIES);
    private final Map<UUID, MotionCollisionSnapshot> collisionSnapshots = new ConcurrentHashMap<>();
    private final ClientWorldHistory worldHistory = new ClientWorldHistory();
    private final FlightPermissionTracker flightPermissions = new FlightPermissionTracker();
    private final Map<UUID, MotionCollisionSnapshot.StepProfile> stepProfiles = new ConcurrentHashMap<>();
    private record CollisionSampleContinuity(UUID world, long movementGeneration, int serverTick) { }
    private final Map<UUID, CollisionSampleContinuity> collisionSampleContinuity = new ConcurrentHashMap<>();
    private final Map<UUID, BlockChangeWindow> blockChanges = new ConcurrentHashMap<>();
    private final Map<UUID, EntityPushContactWindow> entityPushWindows = new ConcurrentHashMap<>();
    private final Map<UUID, PoseMotionPolicy.Transition> poseTransitions = new ConcurrentHashMap<>();
    public boolean poseTransitionUncertain(UUID uuid, long now) {
        var transition = poseTransitions.get(uuid);
        if (transition == null) return false;
        synchronized (transition) { return transition.uncertain(now); }
    }
    private final Map<UUID, GlideTransitionWindow> glideTransitions = new ConcurrentHashMap<>();
    public GlideTransitionWindow.State glideTransition(UUID uuid) {
        var window = glideTransitions.get(uuid);
        return window == null ? null : window.get();
    }
    private void observeGlide(Player player, boolean gliding) {
        var velocity = player.getVelocity();
        glideTransitions.computeIfAbsent(player.getUniqueId(), ignored -> new GlideTransitionWindow())
                .observe(gliding, Math.hypot(velocity.getX(), velocity.getZ()),
                        System.currentTimeMillis(), player.getPing());
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGlideToggle(org.bukkit.event.entity.EntityToggleGlideEvent event) {
        if (event.getEntity() instanceof Player player) observeGlide(player, event.isGliding());
    }
    public record SafeGround(UUID world, double x, double y, double z,
                             float yaw, float pitch, long capturedAt) { }
    public record ElytraSnapshot(boolean gliding, double x, double y, double z,
                                 double velocityX, double velocityY, double velocityZ,
                                 double gravity, boolean slowFalling,
                                 boolean fireworkBoost, long capturedAt) {
        public boolean near(double x, double y, double z, double radius) {
            return Math.abs(this.x - x) <= radius && Math.abs(this.y - y) <= radius
                    && Math.abs(this.z - z) <= radius;
        }
    }
    public record PowderSnowSnapshot(boolean onPowderSnow, boolean hasLeatherBoots,
                                     double x, double y, double z, long capturedAt) {
        public boolean unauthorized() { return onPowderSnow && !hasLeatherBoots; }
        public boolean near(double x, double y, double z, long now) {
            return now >= capturedAt && now - capturedAt <= 200
                    && Math.abs(this.x - x) <= 0.25 && Math.abs(this.y - y) <= 0.12
                    && Math.abs(this.z - z) <= 0.25;
        }
    }
    public record ClimbSnapshot(boolean climbing, double x, double y, double z, long capturedAt) {
        public boolean fresh(long now) {
            return now >= capturedAt && now - capturedAt <= 200;
        }
        public boolean near(double x, double y, double z, long now) {
            return fresh(now)
                    && Math.abs(this.x - x) <= 0.40 && Math.abs(this.y - y) <= 0.50
                    && Math.abs(this.z - z) <= 0.40;
        }
    }
    private final ServerTickTiming serverTiming = new ServerTickTiming();
    public ServerTickTiming serverTiming() { return serverTiming; }
    private final Map<UUID, Integer> pingMillis = new ConcurrentHashMap<>();
    public int pingMillis(UUID uuid) { return pingMillis.getOrDefault(uuid, 0); }
    private final Map<UUID, ElytraSnapshot> elytraSnapshots = new ConcurrentHashMap<>();
    private final Map<UUID, PowderSnowSnapshot> powderSnowSnapshots = new ConcurrentHashMap<>();
    private final Map<UUID, ClimbSnapshot> climbSnapshots = new ConcurrentHashMap<>();
    private final Map<UUID, Long> elytraBoostUntil = new ConcurrentHashMap<>();
    private final Map<UUID, SafeGround> safeGround = new ConcurrentHashMap<>();
    private final Map<UUID, Long> graceUntil = new ConcurrentHashMap<>();
    private final Map<UUID, TeleportSyncWindow> teleports = new ConcurrentHashMap<>();
    private final Map<UUID, Long> teleportGeneration = new ConcurrentHashMap<>();
    private record ExpectedPacCorrection(UUID world, double x, double y, double z, long expiresAt) { }
    private final Map<UUID, ExpectedPacCorrection> expectedPacCorrections = new ConcurrentHashMap<>();
    private record PendingEntityPush(long at, MotionCollisionSnapshot.EntityPush push) { }
    private final Map<UUID, PendingEntityPush> pendingAttackPushes = new ConcurrentHashMap<>();
    // PlayerMoveEvent is synchronous; retain its original destination only until MONITOR.
    private final Map<PlayerMoveEvent, Location> moveEventTargets = new WeakHashMap<>();
    private final GroundStateService ground;
    public MotionEnvironment(GroundStateService ground) { this.ground = ground; }
    public Snapshot get(UUID uuid) {
        return suppressed(uuid, System.currentTimeMillis()) ? null : snapshots.get(uuid);
    }
    public MotionCollisionSnapshot collisions(UUID uuid) { return collisionSnapshots.get(uuid); }
    public ClientWorldHistory.Frame predictionFrame(UUID uuid, long now, int pingMillis) {
        return worldHistory.atOrBefore(uuid, now - Math.max(0, pingMillis) / 2L);
    }
    public boolean authorizedFlightMovement(UUID uuid) {
        return flightPermissions.authorizedMovement(uuid, System.currentTimeMillis());
    }
    public void sentFlightAbilities(UUID uuid, boolean allowed, boolean flying, float flySpeed) {
        FlightPermissionTracker.State previous = flightPermissions.get(uuid);
        flightPermissions.update(uuid, allowed, flying, flySpeed,
                previous == null ? 0.05 : previous.attributeSpeed(), System.currentTimeMillis());
    }
    /** PacketEvents supplies the client step-height profile once the protocol is known. */
    public void stepProfile(UUID uuid, MotionCollisionSnapshot.StepProfile profile) {
        if (uuid == null || profile == null) return;
        MotionCollisionSnapshot.StepProfile current = stepProfiles.get(uuid);
        if (current != profile) stepProfiles.put(uuid, profile);
    }
    public boolean collisionChangeNear(UUID uuid, double fromX, double fromY, double fromZ,
                                       double toX, double toY, double toZ, long now) {
        if (uuid == null) return false;
        CollisionSampleContinuity continuity = collisionSampleContinuity.get(uuid);
        BlockChangeWindow changes = blockChanges.get(uuid);
        return continuity != null && changes != null && changes.affects(continuity.world(),
                fromX, fromY, fromZ, toX, toY, toZ, now);
    }
    public ElytraSnapshot elytra(UUID uuid) {
        return suppressed(uuid, System.currentTimeMillis()) ? null : elytraSnapshots.get(uuid);
    }
    public PowderSnowSnapshot powderSnow(UUID uuid) { return powderSnowSnapshots.get(uuid); }
    public ClimbSnapshot climb(UUID uuid) {
        return suppressed(uuid, System.currentTimeMillis()) ? null : climbSnapshots.get(uuid);
    }
    public SafeGround safeGround(UUID uuid) { return safeGround.get(uuid); }
    /** Drop a partially written tick without losing teleport synchronization or the last safe ground. */
    public void discardFailedSample(UUID uuid) {
        snapshots.remove(uuid);
        collisionSnapshots.remove(uuid);
        elytraSnapshots.remove(uuid);
        powderSnowSnapshots.remove(uuid);
        climbSnapshots.remove(uuid);
        collisionSampleContinuity.remove(uuid);
        entityPushWindows.remove(uuid);
        pendingAttackPushes.remove(uuid);
        worldHistory.forget(uuid);
    }
    public void forget(UUID uuid) {
        poseTransitions.remove(uuid);
        glideTransitions.remove(uuid);
        pingMillis.remove(uuid);
        discardFailedSample(uuid);
        flightPermissions.forget(uuid);
        stepProfiles.remove(uuid);
        blockChanges.remove(uuid);
        elytraBoostUntil.remove(uuid); safeGround.remove(uuid);
        graceUntil.remove(uuid); teleports.remove(uuid); expectedPacCorrections.remove(uuid);
        teleportGeneration.remove(uuid);
    }
    public long teleportGeneration(UUID uuid) { return teleportGeneration.getOrDefault(uuid, 0L); }
    public void markTeleport(UUID uuid) {
        teleportGeneration.merge(uuid, 1L, Long::sum);
        glideTransitions.remove(uuid);
    }
    public void grace(UUID uuid, long millis) {
        graceUntil.merge(uuid, System.currentTimeMillis() + millis, Math::max);
    }
    /** True while movement should not be attributed to the client's normal input. */
    public boolean movementSuppressed(UUID uuid) {
        return uuid != null && suppressed(uuid, System.currentTimeMillis());
    }
    public void expectPacCorrection(UUID uuid, Location target) {
        if (uuid == null || target == null || target.getWorld() == null) return;
        expectedPacCorrections.put(uuid, new ExpectedPacCorrection(target.getWorld().getUID(),
                target.getX(), target.getY(), target.getZ(), System.currentTimeMillis() + 500));
    }
    public void cancelExpectedPacCorrection(UUID uuid) { expectedPacCorrections.remove(uuid); }
    public void teleportSent(UUID uuid, int id) {
        markTeleport(uuid);
        teleports.computeIfAbsent(uuid, ignored -> new TeleportSyncWindow())
                .sent(id, System.currentTimeMillis());
    }
    public void teleportConfirmed(UUID uuid, int id) {
        TeleportSyncWindow window = teleports.get(uuid);
        if (window != null) window.confirm(id, System.currentTimeMillis());
    }
    private boolean suppressed(UUID uuid, long now) {
        if (now < graceUntil.getOrDefault(uuid, 0L)) return true;
        TeleportSyncWindow window = teleports.get(uuid);
        // Keep the window until logout. Removing it here races with a new
        // outgoing correction packet and can discard that packet's state.
        return window != null && window.suppressed(now);
    }

    public void sampleAll() { sampleAll(Bukkit.getOnlinePlayers()); }

    public void sampleAll(Iterable<? extends Player> players) {
        for (Player player : players) samplePlayer(player);
    }

    public void samplePlayer(Player player) {
        var bodyPose = player.getBoundingBox();
        var poseTransition = poseTransitions.computeIfAbsent(player.getUniqueId(),
                ignored -> new PoseMotionPolicy.Transition());
        synchronized (poseTransition) {
            poseTransition.sample(bodyPose.getWidthX(), bodyPose.getHeight(),
                    System.currentTimeMillis(), player.getPing());
        }
        pingMillis.put(player.getUniqueId(), Math.max(0, player.getPing()));
        var flyingSpeed = player.getAttribute(Attribute.FLYING_SPEED);
        flightPermissions.update(player.getUniqueId(), player.getAllowFlight(), player.isFlying(),
                player.getFlySpeed(), flyingSpeed == null ? 0.05 : flyingSpeed.getValue(),
                System.currentTimeMillis());
        Snapshot snapshot = sample(player);
        UUID uuid = player.getUniqueId();
        Snapshot previous = snapshots.put(uuid, snapshot);
        UUID worldId = player.getWorld().getUID();
        long generation = teleportGeneration.getOrDefault(uuid, 0L);
        int serverTick = Bukkit.getCurrentTick();
        CollisionSampleContinuity previousContinuity = collisionSampleContinuity.put(uuid,
                new CollisionSampleContinuity(worldId, generation, serverTick));
        boolean continuous = continuousCollisionSample(previous, previousContinuity,
                worldId, generation, serverTick, snapshot);
        elytraSnapshots.put(uuid, sampleElytra(player));
        observeGlide(player, player.isGliding());
        powderSnowSnapshots.put(uuid, samplePowderSnow(player));
        Location sampledLocation = player.getLocation();
        climbSnapshots.put(uuid, new ClimbSnapshot(player.isClimbing(),
                sampledLocation.getX(), sampledLocation.getY(), sampledLocation.getZ(),
                System.currentTimeMillis()));
        if (snapshot.ordinaryGround() || snapshot.verticalAir()
                || player.isGliding() || player.isInWater() && !player.isInLava())
            collisionSnapshots.put(uuid, sampleCollisions(player,
                    previous, continuous, stepProfiles.getOrDefault(uuid,
                            MotionCollisionSnapshot.StepProfile.V1_21_PLUS)));
        else {
            collisionSnapshots.remove(uuid);
            entityPushWindows.remove(uuid);
        }
        worldHistory.add(uuid, new ClientWorldHistory.Frame(serverTick,
                snapshot.capturedAt(), snapshot, collisionSnapshots.get(uuid)));
        GroundStateService.GroundState state = ground.state(uuid);
        if (state != null && state.known() && state.onGround() && !player.isInsideVehicle()
                && (player.getGameMode() == GameMode.SURVIVAL
                    || player.getGameMode() == GameMode.ADVENTURE)) {
            Location location = player.getLocation();
            safeGround.put(uuid, new SafeGround(worldId,
                    location.getX(), location.getY(), location.getZ(),
                    location.getYaw(), location.getPitch(), System.currentTimeMillis()));
        }
    }

    private static boolean continuousCollisionSample(Snapshot previous,
                                                    CollisionSampleContinuity previousContinuity,
                                                    UUID worldId, long generation, int serverTick,
                                                    Snapshot current) {
        if (previous == null || previousContinuity == null
                || !worldId.equals(previousContinuity.world())
                || generation != previousContinuity.movementGeneration()) return false;
        return serverTick - previousContinuity.serverTick() == 1
                && Math.abs(current.x() - previous.x()) <= 3
                && Math.abs(current.y() - previous.y()) <= 3
                && Math.abs(current.z() - previous.z()) <= 3;
    }

    private ElytraSnapshot sampleElytra(Player player) {
        Location location = player.getLocation();
        var velocity = player.getVelocity();
        var gravity = player.getAttribute(Attribute.GRAVITY);
        long now = System.currentTimeMillis();
        long boostUntil = elytraBoostUntil.getOrDefault(player.getUniqueId(), 0L);
        if (boostUntil <= now) elytraBoostUntil.remove(player.getUniqueId(), boostUntil);
        double gravityValue = gravity == null ? 0.08 : gravity.getValue();
        if (!Double.isFinite(gravityValue) || gravityValue < 0 || gravityValue > 0.2)
            gravityValue = 0.08;
        return new ElytraSnapshot(player.isGliding(), location.getX(), location.getY(), location.getZ(),
                velocity.getX(), velocity.getY(), velocity.getZ(), gravityValue,
                player.hasPotionEffect(org.bukkit.potion.PotionEffectType.SLOW_FALLING),
                boostUntil > now, now);
    }

    private PowderSnowSnapshot samplePowderSnow(Player player) {
        Location location = player.getLocation();
        BoundingBox body = player.getBoundingBox();
        var boots = player.getInventory().getBoots();
        boolean leatherBoots = boots != null && boots.getType() == Material.LEATHER_BOOTS;
        boolean eligible = (player.getGameMode() == GameMode.SURVIVAL
                || player.getGameMode() == GameMode.ADVENTURE)
                && !player.isInsideVehicle() && !player.isFlying() && !player.isGliding()
                && !player.isSwimming() && !player.isInWater() && !player.isInLava();
        boolean onPowderSnow = eligible && coversPowderSnowTop(player, body);
        return new PowderSnowSnapshot(onPowderSnow, leatherBoots,
                location.getX(), location.getY(), location.getZ(), System.currentTimeMillis());
    }

    private boolean coversPowderSnowTop(Player player, BoundingBox body) {
        double feetY = body.getMinY();
        double area = (body.getMaxX() - body.getMinX()) * (body.getMaxZ() - body.getMinZ());
        if (!(area > 0) || !Double.isFinite(area)) return false;
        int minX = (int) Math.floor(body.getMinX());
        int maxX = (int) Math.floor(Math.nextDown(body.getMaxX()));
        int minZ = (int) Math.floor(body.getMinZ());
        int maxZ = (int) Math.floor(Math.nextDown(body.getMaxZ()));
        int floorY = (int) Math.floor(feetY);
        World world = player.getWorld();
        for (int blockY : new int[] {floorY - 1, floorY}) {
            if (blockY < world.getMinHeight() || blockY >= world.getMaxHeight()) continue;
            if (Math.abs(feetY - (blockY + 1.0)) > 0.08) continue;
            double coveredArea = 0;
            boolean loaded = true;
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                        loaded = false;
                        continue;
                    }
                    if (world.getBlockAt(x, blockY, z).getType() != Material.POWDER_SNOW) continue;
                    double overlapX = Math.max(0, Math.min(body.getMaxX(), x + 1.0)
                            - Math.max(body.getMinX(), x));
                    double overlapZ = Math.max(0, Math.min(body.getMaxZ(), z + 1.0)
                            - Math.max(body.getMinZ(), z));
                    coveredArea += overlapX * overlapZ;
                }
            }
            if (loaded && coveredArea >= area * 0.95) return true;
        }
        return false;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onElytraBoost(PlayerElytraBoostEvent event) {
        elytraBoostUntil.put(event.getPlayer().getUniqueId(), System.currentTimeMillis() + 2500);
    }

    private MotionCollisionSnapshot sampleCollisions(Player player,
                                                      Snapshot previous, boolean continuous,
                                                      MotionCollisionSnapshot.StepProfile stepProfile) {
        long now = System.currentTimeMillis();
        UUID uuid = player.getUniqueId();
        BoundingBox body = player.getBoundingBox();
        double maxStep = ((CraftPlayer) player).getHandle().maxUpStep();
        var velocity = player.getVelocity();
        double horizontalMargin = player.isGliding()
                ? Math.min(3.0, Math.max(1.5, Math.hypot(velocity.getX(), velocity.getZ()) + 0.75)) : 1.5;
        double verticalMargin = player.isGliding()
                ? Math.min(3.0, Math.max(1.25, Math.abs(velocity.getY()) + 0.75)) : 1.25;
        double minX = body.getMinX() - horizontalMargin, minY = body.getMinY() - verticalMargin,
                minZ = body.getMinZ() - horizontalMargin;
        double maxX = body.getMaxX() + horizontalMargin,
                maxY = body.getMaxY() + maxStep + verticalMargin,
                maxZ = body.getMaxZ() + horizontalMargin;
        World world = player.getWorld();
        boolean complete = Double.isFinite(maxStep) && maxStep >= 0 && maxStep <= 2;
        int minBlockY = (int) Math.floor(minY), maxBlockY = (int) Math.floor(maxY);
        if (minBlockY < world.getMinHeight() || maxBlockY >= world.getMaxHeight()) complete = false;
        minBlockY = Math.max(world.getMinHeight(), minBlockY);
        maxBlockY = Math.min(world.getMaxHeight() - 1, maxBlockY);
        var level = ((CraftWorld) world).getHandle();
        var worldBorder = level.getWorldBorder();
        // The collision shape is the exterior of the floored/ceiled border box.
        // Chunks beyond that wall cannot contribute block collisions to a path
        // that the border itself has already stopped.
        int minBlockX = firstBlockWithinWorldBorder(minX, worldBorder.getMinX());
        int maxBlockX = lastBlockWithinWorldBorder(maxX, worldBorder.getMaxX());
        int minBlockZ = firstBlockWithinWorldBorder(minZ, worldBorder.getMinZ());
        int maxBlockZ = lastBlockWithinWorldBorder(maxZ, worldBorder.getMaxZ());
        List<MotionCollisionSnapshot.Box> shapes = new ArrayList<>();
        UUID worldId = world.getUID();
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        scanBlocks:
        for (int x = minBlockX; x <= maxBlockX; x++) {
            for (int z = minBlockZ; z <= maxBlockZ; z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    complete = false;
                    continue;
                }
                for (int y = minBlockY; y <= maxBlockY; y++) {
                    position.set(x, y, z);
                    for (MotionCollisionSnapshot.Box shape : collisionShapes(
                            level, worldId, position, x, y, z)) {
                        shapes.add(shape);
                        if (shapes.size() > 768) {
                            complete = false;
                            break scanBlocks;
                        }
                    }
                }
            }
        }
        double borderDistance = worldBorder.getDistanceToBorder(player.getX(), player.getZ());
        double borderCaptureRange = horizontalMargin + Math.max(body.getWidthX(), body.getWidthZ()) * 0.5;
        if (borderDistance <= borderCaptureRange) {
            for (net.minecraft.world.phys.AABB borderBox : worldBorder.getCollisionShape().toAabbs()) {
                MotionCollisionSnapshot.Box clipped = clipWorldBorderBox(
                        borderBox.minX, borderBox.minY, borderBox.minZ,
                        borderBox.maxX, borderBox.maxY, borderBox.maxZ,
                        minX, minY, minZ, maxX, maxY, maxZ);
                if (clipped != null) shapes.add(clipped);
            }
        }
        if (shapes.size() > 768) {
            shapes.clear();
            complete = false;
        }
        boolean blockGeometryComplete = complete;
        // Vanilla hard entity collision shapes use world-space AABBs. Capture
        // their exact server geometry near the player's swept body; client
        // interpolation still makes strict prediction unsafe while they move.
        boolean hardEntityCollisionPossible = false;
        if (complete) {
            var handle = ((CraftPlayer) player).getHandle();
            var entityQuery = new net.minecraft.world.phys.AABB(
                    body.getMinX() - 0.35, body.getMinY() - 0.8, body.getMinZ() - 0.35,
                    body.getMaxX() + 0.35, body.getMaxY() + 0.35, body.getMaxZ() + 0.35);
            for (var entityShape : level.getEntityCollisions(handle, entityQuery)) {
                for (var box : entityShape.toAabbs()) {
                    hardEntityCollisionPossible = true;
                    shapes.add(new MotionCollisionSnapshot.Box(box.minX, box.minY, box.minZ,
                            box.maxX, box.maxY, box.maxZ));
                    if (shapes.size() > 768) {
                        shapes.clear();
                        complete = false;
                        break;
                    }
                }
                if (!complete) break;
            }
        }
        List<MotionCollisionSnapshot.EntityPush> entityPushes = new ArrayList<>();
        for (Entity nearby : world.getNearbyEntities(body.clone().expand(0.2, 0, 0.2))) {
            if (nearby.getUniqueId().equals(uuid)
                    || !EntityCollisionPolicy.canCollide(player, nearby)
                    || !(nearby instanceof CraftEntity craftEntity)) continue;
            var other = craftEntity.getHandle();
            if (!other.isPushable() || other.isVehicle()) continue;
            var push = MotionCollisionSnapshot.officialEntityPush(
                    player.getX(), player.getZ(), nearby.getX(), nearby.getZ());
            if (push != null) entityPushes.add(push);
        }
        PendingEntityPush pending = pendingAttackPushes.remove(uuid);
        if (pending != null && now - pending.at() <= 250) entityPushes.add(pending.push());
        // Collision events identify possible client pushes, including cancelled
        // server pushes: Paper documents that clients can still predict those.
        // Retain the contacts briefly for the resulting movement tail.
        // Client player interpolation can produce contact before Paper emits
        // the collision event. Capture this small overlap uncertainty on the
        // main thread as well, using the same collision/team policy.
        EntityPushContactWindow previousPushWindow = entityPushWindows.get(uuid);
        if (!continuous && previousPushWindow != null) previousPushWindow.clear();
        for (Entity nearby : world.getNearbyEntities(body.clone().expand(0.2, 0, 0.2))) {
            if (!nearby.getUniqueId().equals(uuid)) recordEntityPushContact(player, nearby);
        }
        EntityPushContactWindow pushWindow = entityPushWindows.get(uuid);
        int recentPushContacts = 0;
        if (pushWindow != null) {
            recentPushContacts = pushWindow.sample();
            if (recentPushContacts == 0) entityPushWindows.remove(uuid, pushWindow);
        }
        return new MotionCollisionSnapshot(minX, minY, minZ, maxX, maxY, maxZ,
                body.getWidthX(), body.getHeight(), body.getWidthZ(), maxStep,
                shapes, complete, blockGeometryComplete,
                recentPushContacts, hardEntityCollisionPossible, stepProfile, now, entityPushes);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityCollision(EntityCollideWithEntityEvent event) {
        if (event.getEntities().size() < 2) return;
        Entity first = event.getEntities().get(0);
        Entity second = event.getEntities().get(1);
        if (first instanceof Player player) recordEntityPushContact(player, second);
        if (second instanceof Player player) recordEntityPushContact(player, first);
    }

        @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
        public void onSprintAttackContact(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player) || !player.isSprinting()) return;
        Entity target = event.getEntity();
        if (!(target instanceof CraftEntity craftEntity)
            || !EntityCollisionPolicy.canCollide(player, target)) return;
        var handle = craftEntity.getHandle();
        if (!handle.isPushable() || handle.isVehicle()) return;
        if (!player.getBoundingBox().clone().expand(0.2, 0, 0.2)
            .overlaps(target.getBoundingBox())) return;
        var push = MotionCollisionSnapshot.officialEntityPush(
            player.getX(), player.getZ(), target.getX(), target.getZ());
        if (push != null) pendingAttackPushes.put(player.getUniqueId(),
            new PendingEntityPush(System.currentTimeMillis(), push));
        }

    private void recordEntityPushContact(Player player, Entity other) {
        if (player.isInsideVehicle() || player.getGameMode() == GameMode.SPECTATOR
                || !(other instanceof CraftEntity craftEntity)
                || !craftEntity.getHandle().isPushable()
                || !EntityCollisionPolicy.canCollide(player, other)) return;
        entityPushWindows.computeIfAbsent(player.getUniqueId(), ignored -> new EntityPushContactWindow())
                .recordContact(other.getUniqueId());
    }

    private List<MotionCollisionSnapshot.Box> collisionShapes(
            net.minecraft.server.level.ServerLevel level, UUID worldId,
            BlockPos.MutableBlockPos position, int x, int y, int z) {
        BlockState state = level.getBlockState(position);
        if (state.isAir()) return List.of();
        BlockCoordinate coordinate = new BlockCoordinate(worldId, x, y, z);
        return collisionShapeCache.get(coordinate, state, () -> {
            var collisionBoxes = state.getCollisionShape(level, position).toAabbs();
            List<MotionCollisionSnapshot.Box> shapes = new ArrayList<>(collisionBoxes.size());
            for (net.minecraft.world.phys.AABB shape : collisionBoxes) {
                shapes.add(new MotionCollisionSnapshot.Box(
                        shape.minX + x, shape.minY + y, shape.minZ + z,
                        shape.maxX + x, shape.maxY + y, shape.maxZ + z));
            }
            return List.copyOf(shapes);
        });
    }

    static MotionCollisionSnapshot.Box clipWorldBorderBox(double boxMinX, double boxMinY, double boxMinZ,
                                                           double boxMaxX, double boxMaxY, double boxMaxZ,
                                                           double minX, double minY, double minZ,
                                                           double maxX, double maxY, double maxZ) {
        double clippedMinX = Math.max(boxMinX, minX);
        double clippedMinY = Math.max(boxMinY, minY);
        double clippedMinZ = Math.max(boxMinZ, minZ);
        double clippedMaxX = Math.min(boxMaxX, maxX);
        double clippedMaxY = Math.min(boxMaxY, maxY);
        double clippedMaxZ = Math.min(boxMaxZ, maxZ);
        if (!Double.isFinite(clippedMinX) || !Double.isFinite(clippedMinY) || !Double.isFinite(clippedMinZ)
                || !Double.isFinite(clippedMaxX) || !Double.isFinite(clippedMaxY) || !Double.isFinite(clippedMaxZ)
                || clippedMaxX <= clippedMinX || clippedMaxY <= clippedMinY || clippedMaxZ <= clippedMinZ) {
            return null;
        }
        return new MotionCollisionSnapshot.Box(clippedMinX, clippedMinY, clippedMinZ,
                clippedMaxX, clippedMaxY, clippedMaxZ);
    }

    static int firstBlockWithinWorldBorder(double sampledMin, double borderMin) {
        return Math.max((int) Math.floor(sampledMin), (int) Math.floor(borderMin));
    }

    static int lastBlockWithinWorldBorder(double sampledMax, double borderMax) {
        return Math.min((int) Math.floor(sampledMax), (int) Math.ceil(borderMax) - 1);
    }

    static boolean movementAttributesChanged(Snapshot previous, Snapshot current) {
        return Math.abs(previous.movementSpeed() - current.movementSpeed()) > 1.0e-6
                || Math.abs(previous.gravity() - current.gravity()) > 1.0e-6
                || Math.abs(previous.horizontalDrag() - current.horizontalDrag()) > 1.0e-6
                || Math.abs(previous.verticalDrag() - current.verticalDrag()) > 1.0e-6
                || Math.abs(previous.jumpStrength() - current.jumpStrength()) > 1.0e-6
                || previous.slowFalling() != current.slowFalling()
                || previous.levitationAmplifier() != current.levitationAmplifier()
                || Math.abs(previous.sneakingSpeed() - current.sneakingSpeed()) > 1.0e-6
                || Math.abs(previous.itemUseMultiplier() - current.itemUseMultiplier()) > 1.0e-6
                || Math.abs(previous.stuckHorizontalMultiplier() - current.stuckHorizontalMultiplier()) > 1.0e-6
                || Math.abs(previous.stuckVerticalMultiplier() - current.stuckVerticalMultiplier()) > 1.0e-6
                || Math.abs(previous.blockSpeedFactor() - current.blockSpeedFactor()) > 1.0e-6
                || previous.specialVerticalSurface() != current.specialVerticalSurface();
    }

    private Snapshot sample(Player player) {
        long now = System.currentTimeMillis();
        GroundStateService.GroundState state = ground.state(player.getUniqueId());
        var gravity = player.getAttribute(Attribute.GRAVITY);
        var movementSpeed = player.getAttribute(Attribute.MOVEMENT_SPEED);
        var sneakingSpeed = player.getAttribute(Attribute.SNEAKING_SPEED);
        var frictionModifier = player.getAttribute(Attribute.FRICTION_MODIFIER);
        var airDragModifier = player.getAttribute(Attribute.AIR_DRAG_MODIFIER);
        var jumpStrength = player.getAttribute(Attribute.JUMP_STRENGTH);
        double gravityValue = gravity == null ? Double.NaN : gravity.getValue();
        double airDragValue = airDragModifier == null ? Double.NaN : airDragModifier.getValue();
        double jumpValue = jumpStrength == null ? Double.NaN : jumpStrength.getValue();
        double sneakValue = sneakingSpeed == null ? Double.NaN : sneakingSpeed.getValue();
        var handle = ((CraftPlayer) player).getHandle();
        boolean usingItem = handle.isUsingItem();
        float useMultiplier = usingItem
                ? handle.getUseItem()
                        .getOrDefault(DataComponents.USE_EFFECTS, UseEffects.DEFAULT).speedMultiplier()
                : 1.0f;
        boolean insideCobweb = insideCobweb(player);
        float stuckHorizontalMultiplier = insideCobweb
                ? player.hasPotionEffect(org.bukkit.potion.PotionEffectType.WEAVING) ? 0.5f : 0.25f
                : 1.0f;
        float stuckVerticalMultiplier = insideCobweb
                ? player.hasPotionEffect(org.bukkit.potion.PotionEffectType.WEAVING) ? 0.25f : 0.05f
                : 1.0f;
        var jumpBoost = player.getPotionEffect(org.bukkit.potion.PotionEffectType.JUMP_BOOST);
        var levitation = player.getPotionEffect(org.bukkit.potion.PotionEffectType.LEVITATION);
        int levitationAmplifier = levitation == null ? -1 : levitation.getAmplifier();
        float effectiveJumpStrength = effectiveJumpStrength(jumpValue,
                jumpBoost == null ? -1 : jumpBoost.getAmplifier());
        boolean supported = !suppressed(player.getUniqueId(), now)
                && (player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE)
                && !player.isFlying() && !player.isGliding()
                && !player.isInsideVehicle() && !player.isRiptiding()
                && !player.isInWater() && !player.isInLava() && !player.isClimbing()
                && player.hasGravity() && Double.isFinite(gravityValue)
                && gravityValue >= -1 && gravityValue <= 1
                && Double.isFinite(airDragValue) && airDragValue >= 0 && airDragValue <= 16
                && Float.isFinite(effectiveJumpStrength)
                && effectiveJumpStrength >= 0 && effectiveJumpStrength <= 32
                && Double.isFinite(sneakValue) && sneakValue >= 0 && sneakValue <= 1
                && Float.isFinite(useMultiplier) && useMultiplier >= 0 && useMultiplier <= 4
                && levitationAmplifier >= -1 && levitationAmplifier <= 20;
        boolean knownGround = state != null && state.known();
        // Hover detection only needs to know that normal gravity applies while
        // the player has no support. verticalAir stays stricter because the
        // free-fall predictor requires extra collision-free room.
        boolean gravityAirborne = supported && knownGround && !state.onGround();
        boolean usableGroundSpeed = movementSpeed != null
                && Double.isFinite(movementSpeed.getValue())
                && movementSpeed.getValue() >= 0 && movementSpeed.getValue() <= 1024;
        Location location = player.getLocation();
        float blockSpeedFactor = effectiveBlockSpeedFactor(player);
        boolean specialVerticalSurface = specialVerticalSurface(player);
        double frictionValue = frictionModifier == null ? Double.NaN : frictionModifier.getValue();
        float groundFriction = supported && usableGroundSpeed && knownGround && state.onGround()
                ? groundFriction(player, frictionValue, state.supportY()) : Float.NaN;
        // A moving entity's collision box is a valid floor, but its client-side
        // interpolation is not the stationary block-friction model.
        boolean ordinaryGround = Float.isFinite(groundFriction) && !state.entitySupport();
        // A full-block floor immediately below the feet is still a safe air
        // simulation volume. Requiring the floor itself to be air leaves low
        // hops (including SpeedHack's +0.1Y hop) outside both predictors.
        double floorGap = location.getY() - Math.floor(location.getY());
        boolean nearUniformFloor = supported && knownGround && !state.onGround()
                && floorGap > 0.001 && floorGap <= 0.75 && usableGroundSpeed
                && Float.isFinite(groundFriction(location, frictionValue));
        boolean ordinaryAir = supported && knownGround && !state.onGround()
                && (clearAir(player) || nearUniformFloor);
        boolean wallAdjacent = supported && knownGround && !state.onGround()
                && !player.isClimbing() && !player.isInWater() && !player.isInLava()
                && adjacentWall(player);
        // Vertical prediction only needs an unobstructed player AABB and a
        // small collision-free margin around it. Requiring the surrounding
        // 3x3 blocks to be air (clearAir) incorrectly disables the air model
        // over ordinary terrain, so slow-fall cheats such as Wurst Glide can
        // evade gravity prediction simply by being above a non-air floor.
        boolean verticalAir = supported && knownGround && !state.onGround()
                && clearVerticalColumn(player);
        // Surface spoofing checks must still run when Bukkit already considers
        // the player wet; Jesus clients intentionally collide with liquid on
        // their side and send forged on-ground movement packets.
        boolean waterSurface = knownGround
                && (player.getGameMode() == GameMode.SURVIVAL
                    || player.getGameMode() == GameMode.ADVENTURE)
                && !player.isInsideVehicle() && waterSurface(player);
        return new Snapshot(ordinaryGround, ordinaryAir, player.isSprinting(),
                PoseMotionPolicy.slowInput(player.isSneaking(), player.getPose(), player.isGliding(), player.isInWater()),
                usingItem,
                location.getYaw(), movementSpeed == null ? 0 : movementSpeed.getValue(),
                location.getX(), location.getY(), location.getZ(),
                state == null ? -1 : state.tick(), now, wallAdjacent, waterSurface,
                ordinaryGround ? groundFriction : 0.6f,
                Double.isFinite(gravityValue) ? gravityValue : 0.08,
                Double.isFinite(airDragValue) ? modifiedFriction(0.91f, airDragValue) : 0.91f,
                Double.isFinite(airDragValue) ? modifiedFriction(0.98f, airDragValue) : 0.98f,
                Float.isFinite(effectiveJumpStrength) ? effectiveJumpStrength : 0.42f,
                player.hasPotionEffect(org.bukkit.potion.PotionEffectType.SLOW_FALLING),
                levitationAmplifier, verticalAir,
                Double.isFinite(sneakValue) ? (float) sneakValue : 0.3f, useMultiplier,
                stuckHorizontalMultiplier, stuckVerticalMultiplier, gravityAirborne,
                blockSpeedFactor, specialVerticalSurface);
    }

    private boolean specialVerticalSurface(Player player) {
        var handle = ((CraftPlayer) player).getHandle();
        BlockPos support = handle.getBlockPosBelowThatAffectsMyMovement();
        World world = player.getWorld();
        if (!world.isChunkLoaded(support.getX() >> 4, support.getZ() >> 4)) return true;
        var supportBlock = ((CraftBlock) world.getBlockAt(support.getX(), support.getY(), support.getZ()))
                .getBlockState().getBlock();
        var feetBlock = ((CraftBlock) player.getLocation().getBlock()).getBlockState().getBlock();
        return supportBlock.getBounceRestitution() != 0.0f || supportBlock.getJumpFactor() != 1.0f
                || feetBlock.getBounceRestitution() != 0.0f || feetBlock.getJumpFactor() != 1.0f;
    }

    private float effectiveBlockSpeedFactor(Player player) {
        var efficiency = player.getAttribute(Attribute.MOVEMENT_EFFICIENCY);
        double efficiencyValue = efficiency == null ? 0.0 : efficiency.getValue();
        if (!Double.isFinite(efficiencyValue)) return 1.0f;
        efficiencyValue = Math.max(0.0, Math.min(1.0, efficiencyValue));

        Location location = player.getLocation();
        World world = location.getWorld();
        Block feet = location.getBlock();
        var feetBlock = ((CraftBlock) feet).getBlockState().getBlock();
        float factor = feetBlock.getSpeedFactor();
        if (!Float.isFinite(factor)) return 1.0f;

        if (factor == 1.0f) {
            BlockPos support = ((CraftPlayer) player).getHandle().getBlockPosBelowThatAffectsMyMovement();
            if (!world.isChunkLoaded(support.getX() >> 4, support.getZ() >> 4)) return 1.0f;
            factor = ((CraftBlock) world.getBlockAt(support.getX(), support.getY(), support.getZ()))
                    .getBlockState().getBlock().getSpeedFactor();
        }
        if (!Float.isFinite(factor) || factor < 0 || factor > 4) return 1.0f;
        // LivingEntity#getBlockSpeedFactor lerps the raw block factor toward
        // 1.0 using the server-authoritative MOVEMENT_EFFICIENCY attribute.
        return (float) (factor + efficiencyValue * (1.0 - factor));
    }

    private boolean insideCobweb(Player player) {
        BoundingBox body = player.getBoundingBox();
        World world = player.getWorld();
        int minX = (int) Math.floor(body.getMinX());
        int minY = (int) Math.floor(body.getMinY());
        int minZ = (int) Math.floor(body.getMinZ());
        int maxX = (int) Math.floor(Math.nextDown(body.getMaxX()));
        int maxY = (int) Math.floor(Math.nextDown(body.getMaxY()));
        int maxZ = (int) Math.floor(Math.nextDown(body.getMaxZ()));
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int y = minY; y <= maxY; y++) {
                    if (y >= world.getMinHeight() && y < world.getMaxHeight()
                            && world.getBlockAt(x, y, z).getType() == Material.COBWEB) return true;
                }
            }
        }
        return false;
    }

    static float effectiveJumpStrength(double attribute, int jumpBoostAmplifier) {
        float base = (float) attribute;
        return jumpBoostAmplifier < 0 ? base : base + 0.1f * (jumpBoostAmplifier + 1);
    }

    static float modifiedFriction(float vanilla, double modifier) {
        return Math.max(0, Math.min(1, 1 - (1 - vanilla) * (float) modifier));
    }

    private boolean adjacentWall(Player player) {
        Location location = player.getLocation();
        World world = location.getWorld();
        int y = location.getBlockY() + 1;
        if (y < world.getMinHeight() || y >= world.getMaxHeight()) return false;
        if (!location.getBlock().getType().isAir() || !world.getBlockAt(location.getBlockX(), y, location.getBlockZ()).getType().isAir())
            return false;
        var body = player.getBoundingBox();
        double cx = location.getX(), cz = location.getZ();
        return solidOrUnloaded(world, (int) Math.floor(body.getMinX() - 0.06), y, (int) Math.floor(cz))
                || solidOrUnloaded(world, (int) Math.floor(body.getMaxX() + 0.06), y, (int) Math.floor(cz))
                || solidOrUnloaded(world, (int) Math.floor(cx), y, (int) Math.floor(body.getMinZ() - 0.06))
                || solidOrUnloaded(world, (int) Math.floor(cx), y, (int) Math.floor(body.getMaxZ() + 0.06));
    }

    private static boolean solidOrUnloaded(World world, int x, int y, int z) {
        // Unknown neighboring geometry must not become a Spider violation or
        // synchronously load a chunk on the movement sampling hot path.
        return !world.isChunkLoaded(x >> 4, z >> 4)
                || world.getBlockAt(x, y, z).getType().isSolid();
    }

    private boolean waterSurface(Player player) {
        BoundingBox body = player.getBoundingBox();
        // Only inspect the narrow band immediately below the feet. The former
        // half-block-deep probe covered most of the player's body and treated
        // adjacent water as support during ordinary jumps near a shoreline.
        double feetY = body.getMinY();
        double probeMinY = feetY - 0.15;
        double probeMaxY = feetY - 1.0E-5;
        World world = player.getWorld();
        int minX = (int) Math.floor(body.getMinX());
        int minY = (int) Math.floor(probeMinY);
        int minZ = (int) Math.floor(body.getMinZ());
        int maxX = (int) Math.floor(Math.nextDown(body.getMaxX()));
        int maxY = (int) Math.floor(Math.nextDown(probeMaxY));
        int maxZ = (int) Math.floor(Math.nextDown(body.getMaxZ()));
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) return false;
                boolean liquidColumn = false;
                for (int y = minY; y <= maxY; y++) {
                    if (y < world.getMinHeight() || y >= world.getMaxHeight()) return false;
                    Block block = world.getBlockAt(x, y, z);
                    if (block.getType() == Material.BUBBLE_COLUMN) return false;
                    if (block.isLiquid()) {
                        liquidColumn = true;
                        continue;
                    }
                    if (block.getType().isAir()) continue;

                    // A real solid collision touching any part of the feet
                    // footprint means this is supported ground (for example,
                    // a jump at a water/land edge), not a liquid-ground claim.
                    for (BoundingBox shape : block.getBlockData()
                            .getCollisionShape(block.getLocation()).getBoundingBoxes()) {
                        double shapeMinX = shape.getMinX() + x;
                        double shapeMaxX = shape.getMaxX() + x;
                        double shapeMinY = shape.getMinY() + y;
                        double shapeMaxY = shape.getMaxY() + y;
                        double shapeMinZ = shape.getMinZ() + z;
                        double shapeMaxZ = shape.getMaxZ() + z;
                        if (shapeMaxX > body.getMinX() && shapeMinX < body.getMaxX()
                                && shapeMaxY > probeMinY && shapeMinY < probeMaxY
                                && shapeMaxZ > body.getMinZ() && shapeMinZ < body.getMaxZ())
                            return false;
                    }
                }
                // Require liquid under every footprint column. Water merely
                // beside the player must not classify a land-edge jump as
                // standing on a liquid surface.
                if (!liquidColumn) return false;
            }
        }
        return true;
    }

    private boolean clearAir(Player player) {
        Location location = player.getLocation();
        World world = location.getWorld();
        int footY = location.getBlockY();
        int topY = (int) Math.floor(player.getBoundingBox().getMaxY()) + 1;
        if (footY - 1 < world.getMinHeight() || topY >= world.getMaxHeight()) return false;
        for (int x = location.getBlockX() - 1; x <= location.getBlockX() + 1; x++) {
            for (int z = location.getBlockZ() - 1; z <= location.getBlockZ() + 1; z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) return false;
                for (int y = footY - 1; y <= topY; y++) {
                    if (!world.getBlockAt(x, y, z).getType().isAir()) return false;
                }
            }
        }
        return true;
    }

    private boolean clearVerticalColumn(Player player) {
        BoundingBox body = player.getBoundingBox();
        BoundingBox column = new BoundingBox(body.getMinX() + 0.01, body.getMinY() - 0.5,
                body.getMinZ() + 0.01, body.getMaxX() - 0.01, body.getMaxY() + 0.5,
                body.getMaxZ() - 0.01);
        World world = player.getWorld();
        if (column.getMinY() < world.getMinHeight()
                || column.getMaxY() >= world.getMaxHeight()) return false;
        for (int x = (int) Math.floor(column.getMinX()); x <= (int) Math.floor(column.getMaxX()); x++) {
            for (int z = (int) Math.floor(column.getMinZ()); z <= (int) Math.floor(column.getMaxZ()); z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) return false;
                for (int y = (int) Math.floor(column.getMinY()); y <= (int) Math.floor(column.getMaxY()); y++) {
                    Block block = world.getBlockAt(x, y, z);
                    for (BoundingBox shape : block.getBlockData().getCollisionShape(block.getLocation()).getBoundingBoxes()) {
                        if (overlapsTranslated(column, shape, x, y, z)) return false;
                    }
                }
            }
        }
        return true;
    }

    static boolean overlapsTranslated(BoundingBox worldBox, BoundingBox localShape,
                                      int blockX, int blockY, int blockZ) {
        return worldBox.getMaxX() > localShape.getMinX() + blockX
                && worldBox.getMinX() < localShape.getMaxX() + blockX
                && worldBox.getMaxY() > localShape.getMinY() + blockY
                && worldBox.getMinY() < localShape.getMaxY() + blockY
                && worldBox.getMaxZ() > localShape.getMinZ() + blockZ
                && worldBox.getMinZ() < localShape.getMaxZ() + blockZ;
    }

    private float groundFriction(Location location, double modifier) {
        // A clear 3x3 tunnel around the player; edges, steps and collision uncertainty are skipped.
        World world = location.getWorld();
        int footY = location.getBlockY();
        if (!Double.isFinite(modifier) || modifier < 0 || modifier > 4) return Float.NaN;
        if (footY - 1 < world.getMinHeight() || footY + 2 >= world.getMaxHeight()) return Float.NaN;
        float friction = Float.NaN;
        for (int x = location.getBlockX() - 1; x <= location.getBlockX() + 1; x++) {
            for (int z = location.getBlockZ() - 1; z <= location.getBlockZ() + 1; z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) return Float.NaN;
                Block floor = world.getBlockAt(x, footY - 1, z);
                Material type = floor.getType();
                if (!type.isSolid()) return Float.NaN;
                boolean fullBlockCollision = false;
                for (BoundingBox box : floor.getCollisionShape().getBoundingBoxes()) {
                    if (box.getWidthX() == 1.0 && box.getHeight() == 1.0 && box.getWidthZ() == 1.0) {
                        fullBlockCollision = true;
                        break;
                    }
                }
                if (!fullBlockCollision) return Float.NaN;
                var nmsBlock = ((CraftBlock) floor).getBlockState().getBlock();
                // Speed and bounce factors change motion outside the simple ground recurrence.
                float speedFactor = nmsBlock.getSpeedFactor();
                if (!Float.isFinite(speedFactor) || speedFactor < 0 || speedFactor > 4)
                    return Float.NaN;
                float blockFriction = modifiedFriction(nmsBlock.getFriction(), modifier);
                if (Float.isNaN(friction)) friction = blockFriction;
                else if (friction != blockFriction) return Float.NaN;
                if (!world.getBlockAt(x, footY, z).getType().isAir()
                        || !world.getBlockAt(x, footY + 1, z).getType().isAir()
                        || !world.getBlockAt(x, footY + 2, z).getType().isAir()) return Float.NaN;
            }
        }
        return friction;
    }

    /** Friction from the exact NMS support block; collision snapshots model its shape. */
    private float groundFriction(Player player, double modifier, double supportY) {
        if (!Double.isFinite(supportY) || !Double.isFinite(modifier) || modifier < 0 || modifier > 4)
            return Float.NaN;
        var handle = ((CraftPlayer) player).getHandle();
        var support = handle.getBlockPosBelowThatAffectsMyMovement();
        World world = player.getWorld();
        if (!world.isChunkLoaded(support.getX() >> 4, support.getZ() >> 4)) return Float.NaN;
        Block floor = world.getBlockAt(support.getX(), support.getY(), support.getZ());
        var nmsBlock = ((CraftBlock) floor).getBlockState().getBlock();
        float blockSpeed = nmsBlock.getSpeedFactor();
        float friction = modifiedFriction(nmsBlock.getFriction(), modifier);
        if (!Float.isFinite(blockSpeed) || blockSpeed < 0 || blockSpeed > 4
                || !Float.isFinite(friction))
            return Float.NaN;

        BoundingBox body = player.getBoundingBox();
        BoundingBox feet = new BoundingBox(body.getMinX() + 0.01, supportY - 0.006,
                body.getMinZ() + 0.01, body.getMaxX() - 0.01, supportY + 0.002,
                body.getMaxZ() - 0.01);
        boolean hasSupport = false;
        for (BoundingBox shape : floor.getBlockData().getCollisionShape(floor.getLocation()).getBoundingBoxes()) {
            if (GroundStateService.supportsTranslated(feet, shape,
                    support.getX(), support.getY(), support.getZ(), supportY)) {
                hasSupport = true;
                break;
            }
        }
        return hasSupport ? friction : Float.NaN;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        markTeleport(uuid);
        blockChanges.remove(uuid);
        if (!matchesExpectedPacCorrection(uuid, event.getTo())) grace(uuid, 250);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onToggleFlight(PlayerToggleFlightEvent event) {
        // The Java client flips its local flying flag before it sends the
        // abilities packet. If another plugin cancels this toggle, Paper only
        // sends the corrective abilities after the event returns. Movement in
        // that short transition therefore must not be judged as ordinary air
        // gravity, even though the server never committed flying=true.
        Player player = event.getPlayer();
        var flyingSpeed = player.getAttribute(Attribute.FLYING_SPEED);
        flightPermissions.update(player.getUniqueId(), player.getAllowFlight(), event.isFlying(),
                player.getFlySpeed(), flyingSpeed == null ? 0.05 : flyingSpeed.getValue(),
                System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void captureMoveEventTarget(PlayerMoveEvent event) {
        Location target = event.getTo();
        if (target != null) moveEventTargets.put(event, target.clone());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMoveEvent(PlayerMoveEvent event) {
        Location originalTarget = moveEventTargets.remove(event);
        Location finalTarget = event.getTo();
        if (event.isCancelled() || !positionChanged(originalTarget, finalTarget)) return;

        // A plugin rewrote this movement after the client packet was received. Its
        // destination is now authoritative, so discard any pending PAC rollback and
        // let the next packet establish a fresh physics baseline.
        UUID uuid = event.getPlayer().getUniqueId();
        if (matchesExpectedPacCorrection(uuid, finalTarget)) {
            expectedPacCorrections.remove(uuid);
            return;
        }
        markTeleport(uuid);
        grace(uuid, 100);
    }

    private boolean matchesExpectedPacCorrection(UUID uuid, Location location) {
        ExpectedPacCorrection expected = expectedPacCorrections.get(uuid);
        if (expected == null) return false;
        if (System.currentTimeMillis() > expected.expiresAt()) {
            expectedPacCorrections.remove(uuid, expected);
            return false;
        }
        if (location == null || location.getWorld() == null
                || !expected.world().equals(location.getWorld().getUID())) return false;
        double dx = location.getX() - expected.x();
        double dy = location.getY() - expected.y();
        double dz = location.getZ() - expected.z();
        return dx * dx + dy * dy + dz * dz < 1.0E-6;
    }

    static boolean positionChanged(Location first, Location second) {
        if (first == null || second == null) return false;
        if (!java.util.Objects.equals(first.getWorld(), second.getWorld())) return true;
        double dx = first.getX() - second.getX();
        double dy = first.getY() - second.getY();
        double dz = first.getZ() - second.getZ();
        return dx * dx + dy * dy + dz * dz > 1.0E-8;
    }

    @EventHandler public void onGameMode(PlayerGameModeChangeEvent event) {
        grace(event.getPlayer().getUniqueId(), 2000);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        invalidateCollisionShapesAround(event.getBlock());
        graceNearby(event.getBlock().getLocation());
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        invalidateCollisionShapesAround(event.getBlock());
        graceNearby(event.getBlock().getLocation());
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        invalidateCollisionShapesAround(event.getBlock());
        recordPistonSweep(event.getBlock(), event.getBlock().getRelative(event.getDirection()));
        event.getBlocks().forEach(block -> {
            invalidateCollisionShapesAround(block);
            recordPistonSweep(block, block.getRelative(event.getDirection()));
        });
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        invalidateCollisionShapesAround(event.getBlock());
        recordPistonSweep(event.getBlock(), event.getBlock().getRelative(event.getDirection()));
        event.getBlocks().forEach(block -> {
            invalidateCollisionShapesAround(block);
            recordPistonSweep(block,
                    block.getRelative(event.getDirection().getOppositeFace()));
        });
    }

    private void recordPistonSweep(Block source, Block destination) {
        // A moving piston block has an interpolated collision shape absent
        // from ordinary block-state snapshots. Cover both cells, including the
        // head when no blocks are carried, for the motion and packet delay.
        invalidateCollisionShapesAround(destination);
        long graceMillis = pistonMotionGraceMillis(0);
        graceNearby(source.getLocation(), graceMillis);
        graceNearby(destination.getLocation(), graceMillis);
    }

    static long pistonMotionGraceMillis(int pingMillis) {
        return Math.max(300L, Math.min(750L, (long) pingMillis + 250L));
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPhysics(BlockPhysicsEvent event) {
        invalidateCollisionShape(event.getBlock());
        invalidateCollisionShape(event.getSourceBlock());
        if (event.getBlock().getBlockData() instanceof Openable) {
            graceNearby(event.getBlock().getLocation());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpenableInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || block == null
                || !(block.getBlockData() instanceof Openable)) return;
        invalidateCollisionShapesAround(block);
        graceNearby(block.getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRedstoneChange(BlockRedstoneEvent event) {
        if (!(event.getBlock().getBlockData() instanceof Openable)) return;
        invalidateCollisionShapesAround(event.getBlock());
        graceNearby(event.getBlock().getLocation());
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        UUID world = event.getWorld().getUID();
        int chunkX = event.getChunk().getX(), chunkZ = event.getChunk().getZ();
        collisionShapeCache.invalidateIf(key -> key.world().equals(world)
                && (key.x() >> 4) == chunkX && (key.z() >> 4) == chunkZ);
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldUnload(WorldUnloadEvent event) {
        UUID world = event.getWorld().getUID();
        collisionShapeCache.invalidateIf(key -> key.world().equals(world));
    }

    private void invalidateCollisionShapesAround(Block center) {
        World world = center.getWorld();
        UUID worldId = world.getUID();
        int blockX = center.getX(), blockY = center.getY(), blockZ = center.getZ();
        for (int x = blockX - 1; x <= blockX + 1; x++) {
            for (int y = blockY - 1; y <= blockY + 1; y++) {
                for (int z = blockZ - 1; z <= blockZ + 1; z++)
                    collisionShapeCache.invalidate(new BlockCoordinate(worldId, x, y, z));
            }
        }
    }

    private void invalidateCollisionShape(Block block) {
        collisionShapeCache.invalidate(new BlockCoordinate(block.getWorld().getUID(),
                block.getX(), block.getY(), block.getZ()));
    }

    private void graceNearby(Location changed) {
        graceNearby(changed, -1);
    }

    private void graceNearby(Location changed, long fixedGraceMillis) {
        int blockX = changed.getBlockX(), blockY = changed.getBlockY(), blockZ = changed.getBlockZ();
        BoundingBox affectedArea = blockChangeRegion(blockX, blockY, blockZ);
        long now = System.currentTimeMillis();
        UUID changedWorld = changed.getWorld().getUID();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getWorld().equals(changed.getWorld())
                    && player.getBoundingBox().overlaps(affectedArea)) {
                UUID uuid = player.getUniqueId();
                long graceMillis = fixedGraceMillis < 0
                        ? blockChangeGraceMillis(player.getPing())
                        : Math.max(fixedGraceMillis, pistonMotionGraceMillis(player.getPing()));
                Snapshot snapshot = snapshots.get(uuid);
                if (snapshot != null && snapshot.ordinaryGround()) {
                    blockChanges.computeIfAbsent(uuid, ignored -> new BlockChangeWindow())
                            .record(changedWorld, blockX, blockY, blockZ, now, now + graceMillis);
                } else {
                    grace(uuid, graceMillis);
                }
            }
        }
    }

    static BoundingBox blockChangeRegion(int x, int y, int z) {
        return new BoundingBox(x - BLOCK_CHANGE_MARGIN, y - BLOCK_CHANGE_MARGIN,
                z - BLOCK_CHANGE_MARGIN, x + 1 + BLOCK_CHANGE_MARGIN,
                y + 1 + BLOCK_CHANGE_MARGIN, z + 1 + BLOCK_CHANGE_MARGIN);
    }

    static long blockChangeGraceMillis(int pingMillis) {
        return Math.max(100L, Math.min(500L, (long) pingMillis + 50L));
    }
}

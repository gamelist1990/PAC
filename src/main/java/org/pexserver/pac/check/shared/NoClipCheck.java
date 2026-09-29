package org.pexserver.pac.check.shared;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.VoxelShape;
import org.pexserver.pac.PacPlugin;
import org.pexserver.pac.check.core.AbstractCheck;
import org.pexserver.pac.check.core.EventCheck;
import org.pexserver.pac.movement.BlockPushSuppressionWindow;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/** Experimental, server-side swept-AABB check for movement through solid collision shapes. */
public final class NoClipCheck extends AbstractCheck implements EventCheck, Listener {
    private static final int MAX_SCANNED_BLOCKS = 4096;

    private record MoveSample(Location from, Location to) { }
    private record PhaseBuffer(int count, long lastAt) { }
    private record CollisionScan(List<NoClipGeometry.Aabb> shapes, int blocks) { }

    private final PacPlugin plugin;
    private final Map<UUID, PhaseBuffer> buffers = new HashMap<>();
    private final Map<UUID, BlockPushSuppressionWindow> blockPushWindows = new HashMap<>();
    private final Map<PlayerMoveEvent, MoveSample> moveSamples = new WeakHashMap<>();

    public NoClipCheck(PacPlugin plugin) { this.plugin = plugin; }

    @Override public String key() { return "noclip"; }

    @EventHandler(priority = EventPriority.LOWEST)
    public void captureMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from != null && to != null) moveSamples.put(event, new MoveSample(from.clone(), to.clone()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void inspectMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (event instanceof PlayerTeleportEvent) {
            buffers.remove(uuid);
            blockPushWindows.remove(uuid);
            return;
        }
        MoveSample sample = moveSamples.get(event);
        Location from = event.getFrom();
        Location to = event.getTo();
        if (!plugin.enabled(uuid, this) || plugin.isExempt(uuid) || sample == null
                || from == null || to == null || changedPosition(sample.to(), to)
                || !sameWorld(from, to) || excluded(player)) {
            decay(uuid, System.currentTimeMillis());
            return;
        }

        long now = System.currentTimeMillis();
        boolean movementInvalidated = (plugin.environment() != null
                && plugin.environment().movementSuppressed(uuid))
                || plugin.recentExternalMotion(uuid)
                || plugin.recentPluginVelocity(uuid);
        if (movementInvalidated) {
            decay(uuid, now);
            blockPushWindows.remove(uuid);
            return;
        }

        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);

        // LiquidBounce NoPush BLOCKS cancels LocalPlayer#moveTowardsClosestSpace.
        // Reconstruct the same client-side suffocating-block geometry and only
        // score repeated frames where an outward direction exists but the
        // client remains embedded without making meaningful outward progress.
        int pushDirections = blockPushDirections(player, from);
        int targetPushDirections = blockPushDirections(player, to);
        BlockPushSuppressionWindow pushWindow = blockPushWindows.computeIfAbsent(
                uuid, ignored -> new BlockPushSuppressionWindow());
        BlockPushSuppressionWindow.Finding pushFinding = pushWindow.sample(
                Bukkit.getCurrentTick(), pushDirections, dx, dz,
                targetPushDirections != 0, false);
        if (pushFinding.suspicious()) {
            Map<String, Double> metrics = Map.of(
                    "block_push_outward", pushFinding.bestOutward(),
                    "block_push_horizontal", pushFinding.horizontal(),
                    "block_push_directions", (double) pushFinding.directions(),
                    "block_push_streak", (double) pushFinding.streak());
            flagLimited(uuid, () -> plugin.flag(uuid, this, String.format(java.util.Locale.ROOT,
                    "client block-push suppression: outward=%.4f horizontal=%.4f directions=%d streak=%d",
                    pushFinding.bestOutward(), pushFinding.horizontal(),
                    pushFinding.directions(), pushFinding.streak()), metrics, 2));
        }

        double maxDistance = bounded("max-distance", 10.0, 1.0, 10.0);
        if (distance < 0.01 || distance > maxDistance) {
            decay(uuid, now);
            return;
        }

        NoClipGeometry.Aabb body = playerBody(player, from);
        if (body == null) {
            decay(uuid, now);
            return;
        }
        PhaseBuffer previous = buffers.get(uuid);
        boolean alreadyPhasing = previous != null && now - previous.lastAt() <= 2_000;
        CollisionScan scan = scanCollisionShapes(from.getWorld(), body, dx, dy, dz, alreadyPhasing);
        if (scan == null || scan.shapes().isEmpty()) {
            decay(uuid, now);
            return;
        }

        double tolerance = bounded("collision-tolerance", 0.03, 0.0, 0.30);
        NoClipGeometry.Hit hit = NoClipGeometry.unavoidableHit(body, dx, dy, dz,
                scan.shapes(), tolerance, alreadyPhasing);
        if (hit == null) {
            decay(uuid, now);
            return;
        }

        int count = previous == null || now - previous.lastAt() > 2_000
                ? 1 : Math.min(100, previous.count() + 1);
        buffers.put(uuid, new PhaseBuffer(count, now));

        // When enabled, reject each movement segment that penetrates a collision
        // shape. This prevents the player from continuing through the wall while
        // the repeated-evidence threshold is being collected.
        if (plugin.cancel(this, uuid)) {
            event.setTo(from.clone());
            event.setCancelled(true);
        }

        int required = boundedInt("phase-flags-required", 5, 3, 20);
        if (count < required) return;

        NoClipGeometry.Aabb shape = hit.shape();
        String detail = String.format(java.util.Locale.ROOT,
                "experimental swept collision bypass: block=%d,%d,%d movement=(%.3f,%.3f,%.3f) "
                        + "distance=%.3f path-entry=%.3f buffer=%d/%d tolerance=%.3f",
                floor(shape.minX()), floor(shape.minY()), floor(shape.minZ()),
                dx, dy, dz, distance, hit.time(), count, required, tolerance);
        Map<String, Double> metrics = Map.ofEntries(
                Map.entry("noclip_dx", dx), Map.entry("noclip_dy", dy), Map.entry("noclip_dz", dz),
                Map.entry("noclip_distance", distance), Map.entry("noclip_path_entry", hit.time()),
                Map.entry("noclip_buffer", (double) count), Map.entry("noclip_required", (double) required),
                Map.entry("noclip_tolerance", tolerance), Map.entry("noclip_scanned_blocks", (double) scan.blocks()),
                Map.entry("noclip_shape_min_x", shape.minX()), Map.entry("noclip_shape_min_y", shape.minY()),
                Map.entry("noclip_shape_min_z", shape.minZ()));
        flagLimited(uuid, () -> plugin.flag(uuid, this, detail, metrics, 3));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void finishMove(PlayerMoveEvent event) {
        MoveSample sample = moveSamples.remove(event);
        if (sample == null || event.isCancelled()) return;
        Location finalTarget = event.getTo();
        if (changedPosition(sample.to(), finalTarget))
            buffers.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTeleport(PlayerTeleportEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        buffers.remove(uuid);
        blockPushWindows.remove(uuid);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        buffers.remove(uuid);
        blockPushWindows.remove(uuid);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        buffers.remove(uuid);
        blockPushWindows.remove(uuid);
    }

    private CollisionScan scanCollisionShapes(World world, NoClipGeometry.Aabb body,
                                               double dx, double dy, double dz,
                                               boolean includeInitialOverlaps) {
        double endMinX = body.minX() + dx, endMaxX = body.maxX() + dx;
        double endMinY = body.minY() + dy, endMaxY = body.maxY() + dy;
        double endMinZ = body.minZ() + dz, endMaxZ = body.maxZ() + dz;
        int minX = floor(Math.min(body.minX(), endMinX));
        int maxX = floor(Math.max(body.maxX(), endMaxX));
        int minY = floor(Math.min(body.minY(), endMinY));
        int maxY = floor(Math.max(body.maxY(), endMaxY));
        int minZ = floor(Math.min(body.minZ(), endMinZ));
        int maxZ = floor(Math.max(body.maxZ(), endMaxZ));
        long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (volume <= 0 || volume > MAX_SCANNED_BLOCKS) return null;

        List<NoClipGeometry.Aabb> shapes = new ArrayList<>();
        Set<Long> checkedChunks = new HashSet<>();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                int chunkX = x >> 4, chunkZ = z >> 4;
                long chunkKey = ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
                if (checkedChunks.add(chunkKey) && !world.isChunkLoaded(chunkX, chunkZ)) return null;
                for (int y = minY; y <= maxY; y++) {
                    Block block = world.getBlockAt(x, y, z);
                    VoxelShape collision;
                    try {
                        collision = block.getBlockData().getCollisionShape(block.getLocation());
                    } catch (RuntimeException unavailableShape) {
                        return null;
                    }
                    for (BoundingBox local : collision.getBoundingBoxes()) {
                        NoClipGeometry.Aabb shape = new NoClipGeometry.Aabb(
                                local.getMinX() + x, local.getMinY() + y, local.getMinZ() + z,
                                local.getMaxX() + x, local.getMaxY() + y, local.getMaxZ() + z);
                        // Already being inside a block (block placement, login, or a
                        // previous server correction) is not evidence of phasing.
                        if (includeInitialOverlaps || !body.overlaps(shape)) shapes.add(shape);
                    }
                }
            }
        }
        return new CollisionScan(shapes, (int) volume);
    }

    private static int blockPushDirections(Player player, Location at) {
        if (player == null || at == null || at.getWorld() == null) return 0;
        var handle = ((CraftPlayer) player).getHandle();
        double width = player.getBoundingBox().getWidthX();
        double height = player.getBoundingBox().getHeight();
        if (!Double.isFinite(width) || !Double.isFinite(height)
                || width < 0.2 || width > 1.5 || height < 0.5 || height > 3.0)
            return 0;

        AABB body = new AABB(at.getX() - width * 0.5, at.getY(),
                at.getZ() - width * 0.5, at.getX() + width * 0.5,
                at.getY() + height, at.getZ() + width * 0.5);
        double offset = width * 0.35;
        double[][] corners = {
                {at.getX() - offset, at.getZ() + offset},
                {at.getX() - offset, at.getZ() - offset},
                {at.getX() + offset, at.getZ() - offset},
                {at.getX() + offset, at.getZ() + offset}
        };
        int directions = 0;
        for (double[] corner : corners)
            directions |= blockPushDirection(handle, body, corner[0], corner[1]);
        return directions;
    }

    private static int blockPushDirection(net.minecraft.server.level.ServerPlayer handle,
                                          AABB body, double x, double z) {
        BlockPos pos = BlockPos.containing(x, body.minY, z);
        if (!suffocatesAt(handle, body, pos)) return 0;

        double xd = x - pos.getX();
        double zd = z - pos.getZ();
        double closest = Double.MAX_VALUE;
        int best = 0;

        if (xd < closest && !suffocatesAt(handle, body, pos.west())) {
            closest = xd;
            best = BlockPushSuppressionWindow.WEST;
        }
        double east = 1.0 - xd;
        if (east < closest && !suffocatesAt(handle, body, pos.east())) {
            closest = east;
            best = BlockPushSuppressionWindow.EAST;
        }
        if (zd < closest && !suffocatesAt(handle, body, pos.north())) {
            closest = zd;
            best = BlockPushSuppressionWindow.NORTH;
        }
        double south = 1.0 - zd;
        if (south < closest && !suffocatesAt(handle, body, pos.south()))
            best = BlockPushSuppressionWindow.SOUTH;
        return best;
    }

    private static boolean suffocatesAt(net.minecraft.server.level.ServerPlayer handle,
                                         AABB body, BlockPos pos) {
        AABB testArea = new AABB(pos.getX(), body.minY, pos.getZ(),
                pos.getX() + 1.0, body.maxY, pos.getZ() + 1.0).deflate(1.0E-7);
        return handle.level().collidesWithSuffocatingBlock(handle, testArea);
    }

    private static NoClipGeometry.Aabb playerBody(Player player, Location from) {
        BoundingBox current = player.getBoundingBox();
        double widthX = current.getMaxX() - current.getMinX();
        double widthZ = current.getMaxZ() - current.getMinZ();
        double height = current.getMaxY() - current.getMinY();
        if (!(Double.isFinite(widthX) && Double.isFinite(widthZ) && Double.isFinite(height))
                || widthX < 0.2 || widthX > 1.5 || widthZ < 0.2 || widthZ > 1.5
                || height < 0.5 || height > 3.0) return null;
        return new NoClipGeometry.Aabb(from.getX() - widthX * 0.5, from.getY(),
                from.getZ() - widthZ * 0.5, from.getX() + widthX * 0.5,
                from.getY() + height, from.getZ() + widthZ * 0.5);
    }

    private boolean excluded(Player player) {
        return player.isDead() || player.isInsideVehicle() || player.isFlying()
                || player.getGameMode() == GameMode.CREATIVE
                || player.getGameMode() == GameMode.SPECTATOR;
    }

    private void decay(UUID uuid, long now) {
        PhaseBuffer previous = buffers.get(uuid);
        if (previous == null) return;
        if (now - previous.lastAt() > 2_000 || previous.count() <= 1) buffers.remove(uuid);
        else buffers.put(uuid, new PhaseBuffer(previous.count() - 1, now));
    }

    private double bounded(String path, double fallback, double min, double max) {
        double value = plugin.getConfig().getDouble("detectors.noclip." + path, fallback);
        return Double.isFinite(value) ? Math.max(min, Math.min(max, value)) : fallback;
    }

    private int boundedInt(String path, int fallback, int min, int max) {
        return Math.max(min, Math.min(max, plugin.getConfig().getInt("detectors.noclip." + path, fallback)));
    }

    private static boolean sameWorld(Location first, Location second) {
        return first.getWorld() != null && first.getWorld().equals(second.getWorld());
    }

    private static boolean changedPosition(Location first, Location second) {
        if (first == null || second == null || !java.util.Objects.equals(first.getWorld(), second.getWorld()))
            return true;
        double dx = first.getX() - second.getX();
        double dy = first.getY() - second.getY();
        double dz = first.getZ() - second.getZ();
        return dx * dx + dy * dy + dz * dz > 1.0E-8;
    }

    private static int floor(double value) { return (int) Math.floor(value); }

    @Override public void forget(UUID uuid) {
        super.forget(uuid);
        buffers.remove(uuid);
        blockPushWindows.remove(uuid);
    }
}

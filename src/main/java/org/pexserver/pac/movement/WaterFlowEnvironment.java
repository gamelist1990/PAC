package org.pexserver.pac.movement;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.util.BoundingBox;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Main-thread water-current sampling for packets that lack block data. */
public final class WaterFlowEnvironment {
    public record Snapshot(double x, double y, double z, double flowX, double flowZ,
                           long capturedAt) {
        public boolean near(double x, double y, double z) {
            return Math.abs(this.x - x) < 1.5 && Math.abs(this.y - y) < 1.5
                    && Math.abs(this.z - z) < 1.5;
        }
    }
    private final Map<UUID, Snapshot> snapshots = new ConcurrentHashMap<>();

    public Snapshot get(UUID uuid) { return snapshots.get(uuid); }
    public void forget(UUID uuid) { snapshots.remove(uuid); }

    public void sampleAll() { sampleAll(Bukkit.getOnlinePlayers()); }

    public void sampleAll(Iterable<? extends Player> players) {
        for (Player player : players) samplePlayer(player);
    }

    public void samplePlayer(Player player) {
        Snapshot snapshot = sample(player);
        if (snapshot == null) snapshots.remove(player.getUniqueId());
        else snapshots.put(player.getUniqueId(), snapshot);
    }

    private Snapshot sample(Player player) {
        if ((player.getGameMode() != GameMode.SURVIVAL
                && player.getGameMode() != GameMode.ADVENTURE)
                || player.isInsideVehicle() || player.isFlying() || player.isGliding()
                || player.isRiptiding() || !player.hasGravity()) return null;
        var location = player.getLocation();
        var world = location.getWorld();
        var body = ((CraftPlayer) player).getHandle().getFluidInteractionBox();
        if (body == null) return null;
        int minX = (int) Math.floor(body.minX), maxX = (int) Math.ceil(body.maxX) - 1;
        int minY = (int) Math.floor(body.minY), maxY = (int) Math.ceil(body.maxY) - 1;
        int minZ = (int) Math.floor(body.minZ), maxZ = (int) Math.ceil(body.maxZ) - 1;
        if (minY < world.getMinHeight() || maxY >= world.getMaxHeight()) return null;
        for (int x = minX - 1; x <= maxX + 1; x++) {
            for (int z = minZ - 1; z <= maxZ + 1; z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) return null;
            }
        }
        var level = ((CraftWorld) world).getHandle();
        double flowX = 0, flowZ = 0, overlap = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (world.getBlockAt(x, y, z).getType() == Material.BUBBLE_COLUMN) return null;
                    BlockPos pos = new BlockPos(x, y, z);
                    var fluid = level.getFluidState(pos);
                    if (!fluid.is(FluidTags.WATER)) continue;
                    double height = Math.min(body.maxY, y + fluid.getHeight(level, pos))
                            - Math.max(body.minY, y);
                    if (height <= 0.015) continue;
                    Vec3 flow = fluid.getFlow(level, pos);
                    if (!Double.isFinite(flow.x) || !Double.isFinite(flow.z)) return null;
                    // Shallow water still pushes the feet. Weigh each intersected
                    // cell by the actual height inside the fluid interaction box.
                    flowX += flow.x * height;
                    flowZ += flow.z * height;
                    overlap += height;
                }
            }
        }
        if (overlap < 0.02) return null;
        flowX /= overlap;
        flowZ /= overlap;
        double length = Math.hypot(flowX, flowZ);
        if (length < 0.35) return null;
        BoundingBox path = player.getBoundingBox().clone()
                .shift(flowX / length * 0.06, 0, flowZ / length * 0.06);
        for (int x = (int) Math.floor(path.getMinX()); x <= (int) Math.floor(path.getMaxX()); x++) {
            for (int y = (int) Math.floor(path.getMinY()); y <= (int) Math.floor(path.getMaxY()); y++) {
                for (int z = (int) Math.floor(path.getMinZ()); z <= (int) Math.floor(path.getMaxZ()); z++) {
                    var block = world.getBlockAt(x, y, z);
                    for (BoundingBox shape : block.getCollisionShape().getBoundingBoxes()) {
                        if (path.overlaps(shape.shift(x, y, z))) return null;
                    }
                }
            }
        }
        return new Snapshot(location.getX(), location.getY(), location.getZ(),
                flowX, flowZ, System.currentTimeMillis());
    }
}

package org.pexserver.pac.movement.ground;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.pexserver.pac.platform.NmsClock;
import net.minecraft.world.phys.AABB;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Computes support from server collision geometry on the primary thread each tick. */
public final class GroundStateService {
    public record GroundState(boolean known, boolean onGround, double supportY,
                              int tick, int consecutiveTicks, boolean entitySupport) { }

    private final Map<UUID, GroundState> states = new ConcurrentHashMap<>();
    private final NmsClock clock;

    public GroundStateService(NmsClock clock) { this.clock = clock; }
    public GroundState state(UUID uuid) { return states.get(uuid); }
    public void forget(UUID uuid) { states.remove(uuid); }

    public void sampleAll() { sampleAll(Bukkit.getOnlinePlayers()); }

    public void sampleAll(Iterable<? extends Player> players) {
        int tick = clock.tick();
        for (Player player : players) samplePlayer(player, tick);
    }

    public int currentTick() { return clock.tick(); }

    public void samplePlayer(Player player, int tick) {
        states.put(player.getUniqueId(), sample(player, tick));
    }

    private GroundState sample(Player player, int tick) {
        BoundingBox body = player.getBoundingBox();
        BoundingBox feet = new BoundingBox(body.getMinX() + 0.01, body.getMinY() - 0.006, body.getMinZ() + 0.01,
                body.getMaxX() - 0.01, body.getMinY() + 0.002, body.getMaxZ() - 0.01);
        World world = player.getWorld();
        if (feet.getMinY() < world.getMinHeight() || feet.getMaxY() >= world.getMaxHeight())
            return new GroundState(false, false, Double.NaN, tick, 0, false);
        double bestSupport = Double.NEGATIVE_INFINITY;
        for (int x = floor(feet.getMinX()); x <= floor(feet.getMaxX()); x++) {
            for (int z = floor(feet.getMinZ()); z <= floor(feet.getMaxZ()); z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4))
                    return new GroundState(false, false, Double.NaN, tick, 0, false);
                for (int y = floor(feet.getMinY()); y <= floor(feet.getMaxY()); y++) {
                    Block block = world.getBlockAt(x, y, z);
                    for (BoundingBox shape : block.getBlockData().getCollisionShape(block.getLocation()).getBoundingBoxes()) {
                        if (supportsTranslated(feet, shape, x, y, z, body.getMinY()))
                            bestSupport = Math.max(bestSupport, shape.getMaxY() + y);
                    }
                }
            }
        }
        boolean entitySupport = false;
        if (bestSupport == Double.NEGATIVE_INFINITY) {
            // Vanilla also resolves player movement against hard entity collision
            // shapes (boats, minecarts, etc.). Query that same server geometry.
            var handle = ((CraftPlayer) player).getHandle();
            var search = new AABB(feet.getMinX(), body.getMinY() - 0.08, feet.getMinZ(),
                    feet.getMaxX(), body.getMinY() + 0.08, feet.getMaxZ());
            for (var shape : handle.level().getEntityCollisions(handle, search)) {
                for (AABB box : shape.toAabbs()) {
                    if (supportsEntity(feet, box, body.getMinY())) {
                        bestSupport = Math.max(bestSupport, box.maxY);
                        entitySupport = true;
                    }
                }
            }
        }
        boolean grounded = bestSupport != Double.NEGATIVE_INFINITY;
        GroundState previous = states.get(player.getUniqueId());
        int consecutive = grounded ? (previous != null && previous.onGround() ? previous.consecutiveTicks() + 1 : 1) : 0;
        return new GroundState(true, grounded, bestSupport, tick, consecutive, entitySupport);
    }

    static boolean supportsEntity(BoundingBox feet, AABB box, double footY) {
        return box.maxX > feet.getMinX() && box.minX < feet.getMaxX()
                && box.maxZ > feet.getMinZ() && box.minZ < feet.getMaxZ()
                && box.maxY >= footY - 0.08 && box.maxY <= footY + 0.08
                && box.minY < footY;
    }

    public static boolean supports(BoundingBox feet, BoundingBox collision, double footY) {
        return supports(feet.getMinX(), feet.getMaxX(), feet.getMinY(), feet.getMaxY(),
                feet.getMinZ(), feet.getMaxZ(), collision.getMinX(), collision.getMaxX(),
                collision.getMinY(), collision.getMaxY(), collision.getMinZ(), collision.getMaxZ(), footY);
    }

    /** Tests a block-local collision AABB without allocating a translated BoundingBox. */
    public static boolean supportsTranslated(BoundingBox feet, BoundingBox localCollision,
                                             int blockX, int blockY, int blockZ, double footY) {
        return supports(feet.getMinX(), feet.getMaxX(), feet.getMinY(), feet.getMaxY(),
                feet.getMinZ(), feet.getMaxZ(),
                localCollision.getMinX() + blockX, localCollision.getMaxX() + blockX,
                localCollision.getMinY() + blockY, localCollision.getMaxY() + blockY,
                localCollision.getMinZ() + blockZ, localCollision.getMaxZ() + blockZ, footY);
    }

    private static boolean supports(double feetMinX, double feetMaxX,
                                    double feetMinY, double feetMaxY,
                                    double feetMinZ, double feetMaxZ,
                                    double collisionMinX, double collisionMaxX,
                                    double collisionMinY, double collisionMaxY,
                                    double collisionMinZ, double collisionMaxZ,
                                    double footY) {
        return collisionMaxX > feetMinX && collisionMinX < feetMaxX
                && collisionMaxZ > feetMinZ && collisionMinZ < feetMaxZ
                && collisionMaxY >= footY - 0.006 && collisionMaxY <= footY + 0.002
                && collisionMinY < footY;
    }

    private static int floor(double value) { return (int) Math.floor(value); }
}

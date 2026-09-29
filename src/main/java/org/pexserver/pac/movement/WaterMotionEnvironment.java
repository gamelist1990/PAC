package org.pexserver.pac.movement;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Main-thread snapshots for submerged Java movement and Paper's fluid-current simulation. */
public final class WaterMotionEnvironment {
    public record Contact(double x, double y, double z, boolean wet, long capturedAt) {
        boolean near(double x, double y, double z) {
            return Math.abs(this.x - x) < 1.5 && Math.abs(this.y - y) < 1.5
                    && Math.abs(this.z - z) < 1.5;
        }
    }

    public record Snapshot(double x, double y, double z, float yaw,
                           double movementSpeed, double movementEfficiency,
                           double gravity, boolean slowFalling, boolean dolphinsGrace,
                           boolean sprinting, boolean sneaking, float sneakingSpeed,
                           float itemUseMultiplier, double currentX, double currentY,
                           double currentZ, long tick, long capturedAt,
                           boolean submerged) {
        public Snapshot(double x, double y, double z, float yaw,
                        double movementSpeed, double movementEfficiency,
                        double gravity, boolean slowFalling, boolean dolphinsGrace,
                        boolean sprinting, boolean sneaking, float sneakingSpeed,
                        float itemUseMultiplier, double currentX, double currentY,
                        double currentZ, long tick, long capturedAt) {
            this(x, y, z, yaw, movementSpeed, movementEfficiency, gravity,
                    slowFalling, dolphinsGrace, sprinting, sneaking, sneakingSpeed,
                    itemUseMultiplier, currentX, currentY, currentZ, tick, capturedAt, true);
        }
        public boolean near(double x, double y, double z) {
            return Math.abs(this.x - x) < 1.5 && Math.abs(this.y - y) < 1.5
                    && Math.abs(this.z - z) < 1.5;
        }

        public boolean samePhysics(Snapshot other) {
            return other != null
                    && Double.compare(movementSpeed, other.movementSpeed) == 0
                    && Double.compare(movementEfficiency, other.movementEfficiency) == 0
                    && Double.compare(gravity, other.gravity) == 0
                    && slowFalling == other.slowFalling
                    && dolphinsGrace == other.dolphinsGrace
                    && sneaking == other.sneaking
                    && sneakingSpeed == other.sneakingSpeed
                    && itemUseMultiplier == other.itemUseMultiplier
                    && submerged == other.submerged
                    && Double.compare(currentX, other.currentX) == 0
                    && Double.compare(currentY, other.currentY) == 0
                    && Double.compare(currentZ, other.currentZ) == 0;
        }

        public Snapshot withoutCurrent() {
            return new Snapshot(x, y, z, yaw, movementSpeed, movementEfficiency,
                    gravity, slowFalling, dolphinsGrace, sprinting, sneaking,
                    sneakingSpeed, itemUseMultiplier, 0, 0, 0, tick, capturedAt, submerged);
        }
    }

    private final Map<UUID, Snapshot> snapshots = new ConcurrentHashMap<>();
    private final Map<UUID, Contact> contacts = new ConcurrentHashMap<>();

    public Snapshot get(UUID uuid) { return snapshots.get(uuid); }
    public void forget(UUID uuid) {
        snapshots.remove(uuid);
        contacts.remove(uuid);
    }

    public boolean wetNear(UUID uuid, double x, double y, double z, long now) {
        Contact contact = contacts.get(uuid);
        return contact != null && contact.wet() && now - contact.capturedAt() <= 200
                && contact.near(x, y, z);
    }

    void recordContact(UUID uuid, double x, double y, double z, boolean wet, long now) {
        contacts.put(uuid, new Contact(x, y, z, wet, now));
    }

    public void sampleAll() { sampleAll(Bukkit.getOnlinePlayers()); }

    public void sampleAll(Iterable<? extends Player> players) {
        for (Player player : players) samplePlayer(player);
    }

    public void samplePlayer(Player player) {
        var location = player.getLocation();
        var handle = ((CraftPlayer) player).getHandle();
        Snapshot snapshot = sample(player);
        boolean wet = player.isInWater()
                || handle.getFluidHeight(FluidTags.WATER) > 0 || snapshot != null;
        recordContact(player.getUniqueId(), location.getX(), location.getY(), location.getZ(),
                wet, System.currentTimeMillis());
        UUID uuid = player.getUniqueId();
        if (snapshot == null) snapshots.remove(uuid);
        else {
            snapshots.put(uuid, snapshot);
        }
    }

    private Snapshot sample(Player player) {
        // The current predictor models upright liquid travel only. Swimming
        // needs pitch-directed vertical steering and pose transitions; applying
        // the upright recurrence to that pose reports legitimate swimming.
        if ((player.getGameMode() != GameMode.SURVIVAL && player.getGameMode() != GameMode.ADVENTURE)
                || player.isInsideVehicle() || player.isFlying() || player.isGliding()
                || player.isRiptiding() || player.isClimbing() || !player.hasGravity()
                || player.isInLava() || player.isSwimming()) return null;

        var handle = ((CraftPlayer) player).getHandle();
        if (player.hasPotionEffect(org.bukkit.potion.PotionEffectType.LEVITATION)) return null;

        var body = handle.getFluidInteractionBox();
        if (body == null) return null;
        int minX = (int) Math.floor(body.minX), maxX = (int) Math.ceil(body.maxX) - 1;
        int minY = (int) Math.floor(body.minY), maxY = (int) Math.ceil(body.maxY) - 1;
        int minZ = (int) Math.floor(body.minZ), maxZ = (int) Math.ceil(body.maxZ) - 1;
        var world = player.getWorld();
        // FluidState#getFlow reads adjacent cells. Do not load chunks to obtain them.
        for (int x = minX - 1; x <= maxX + 1; x++) {
            for (int z = minZ - 1; z <= maxZ + 1; z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) return null;
            }
        }

        var level = ((CraftWorld) world).getHandle();
        double waterHeight = 0, sumX = 0, sumY = 0, sumZ = 0;
        int flowCount = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    var fluid = level.getFluidState(pos);
                    if (world.getBlockAt(x, y, z).getType() == Material.BUBBLE_COLUMN) return null;
                    if (fluid.isEmpty() || !fluid.is(FluidTags.WATER)) continue;
                    double surface = y + fluid.getHeight(level, pos);
                    if (surface < body.minY) continue;
                    waterHeight = Math.max(waterHeight, surface - body.minY);
                    var flow = fluid.getFlow(level, pos);
                    if (!Double.isFinite(flow.x) || !Double.isFinite(flow.y) || !Double.isFinite(flow.z)) return null;
                    double weight = waterHeight < 0.4 ? waterHeight : 1.0;
                    sumX += flow.x * weight;
                    sumY += flow.y * weight;
                    sumZ += flow.z * weight;
                    flowCount++;
                }
            }
        }
        if (flowCount == 0 || waterHeight < 0.02) return null;

        double currentX = 0, currentY = 0, currentZ = 0;
        double currentLengthSquared = sumX * sumX + sumY * sumY + sumZ * sumZ;
        if (flowCount > 0 && currentLengthSquared >= 9.999999747378752E-6) {
            currentX = sumX / flowCount * 0.014;
            currentY = sumY / flowCount * 0.014;
            currentZ = sumZ / flowCount * 0.014;
        }

        var speed = player.getAttribute(Attribute.MOVEMENT_SPEED);
        var efficiency = player.getAttribute(Attribute.WATER_MOVEMENT_EFFICIENCY);
        var gravity = player.getAttribute(Attribute.GRAVITY);
        var sneakingSpeed = player.getAttribute(Attribute.SNEAKING_SPEED);
        if (speed == null || efficiency == null || gravity == null || sneakingSpeed == null) return null;
        double speedValue = speed.getValue();
        double efficiencyValue = efficiency.getValue();
        double gravityValue = gravity.getValue();
        double sneakingValue = sneakingSpeed.getValue();
        if (!Double.isFinite(speedValue) || speedValue < 0 || speedValue > 1024
                || !Double.isFinite(efficiencyValue) || efficiencyValue < 0 || efficiencyValue > 1
                || !Double.isFinite(gravityValue) || gravityValue < -1 || gravityValue > 1
                || !Double.isFinite(sneakingValue) || sneakingValue < 0 || sneakingValue > 1) return null;

        boolean usingItem = handle.isUsingItem();
        float useMultiplier = usingItem
                ? handle.getUseItem().getOrDefault(net.minecraft.core.component.DataComponents.USE_EFFECTS,
                        net.minecraft.world.item.component.UseEffects.DEFAULT).speedMultiplier()
                : 1.0f;
        if (!Float.isFinite(useMultiplier) || useMultiplier < 0 || useMultiplier > 4) return null;

        // LivingEntity#travelInWater halves this attribute while airborne.
        if (!handle.onGround()) efficiencyValue *= 0.5;
        var location = player.getLocation();
        return new Snapshot(location.getX(), location.getY(), location.getZ(), location.getYaw(),
                speedValue, efficiencyValue, gravityValue,
                player.hasPotionEffect(org.bukkit.potion.PotionEffectType.SLOW_FALLING),
                player.hasPotionEffect(org.bukkit.potion.PotionEffectType.DOLPHINS_GRACE),
                player.isSprinting(), player.isSneaking(), (float) sneakingValue,
                useMultiplier, currentX, currentY, currentZ, level.getGameTime(),
                System.currentTimeMillis(), handle.isUnderWater());
    }
}

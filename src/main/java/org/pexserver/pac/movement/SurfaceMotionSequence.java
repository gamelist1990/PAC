package org.pexserver.pac.movement;

/** Packet-order state for Java movement next to walls and liquid surfaces. */
public final class SurfaceMotionSequence {
    public enum Anomaly { NONE, WALL_CLIMB, WALL_CLIP, LIQUID_GROUND_CLAIM, POWDER_SNOW_WALK, AIR_GROUND_CLAIM }
    private double x, y, z;
    private boolean initialized;
    private long lastAt;
    private double lastDy;
    private int wallRise, liquidClaims, powderSnowClaims, airGroundClaims;

    public Anomaly accept(boolean hasPosition, double packetX, double packetY, double packetZ,
                          boolean claimedGround, MotionEnvironment.Snapshot environment, long now) {
        return accept(hasPosition, packetX, packetY, packetZ, claimedGround,
                environment, false, now);
    }

    public Anomaly accept(boolean hasPosition, double packetX, double packetY, double packetZ,
                          boolean claimedGround, MotionEnvironment.Snapshot environment,
                          boolean unsupportedPowderSnow, long now) {
        if (!hasPosition && !initialized) return Anomaly.NONE;
        if (!hasPosition) {
            boolean usable = lastAt > 0 && now - lastAt < 250
                    && environment != null && now - environment.capturedAt() <= 200
                    && environment.near(x, y, z);
            if (!usable || !environment.wallAdjacent()) wallRise = 0;
            liquidClaims = usable && liquidSampleMatches(environment, x, y, z)
                    && environment.waterSurface() && !environment.ordinaryGround() && claimedGround
                    ? liquidClaims + 1 : 0;
            powderSnowClaims = usable && unsupportedPowderSnow
                    && !environment.ordinaryGround() && claimedGround ? powderSnowClaims + 1 : 0;
            airGroundClaims = usable && airSampleMatches(environment, x, y, z)
                    && environment.gravityAirborne() && claimedGround
                    ? airGroundClaims + 1 : 0;
            lastAt = now;
            if (powderSnowClaims >= 4) {
                powderSnowClaims = 0;
                return Anomaly.POWDER_SNOW_WALK;
            }
            if (airGroundClaims >= 4) {
                airGroundClaims = 0;
                return Anomaly.AIR_GROUND_CLAIM;
            }
            if (liquidClaims >= 4) {
                liquidClaims = 0;
                return Anomaly.LIQUID_GROUND_CLAIM;
            }
            // Preserve accumulated upward movement evidence across packets that
            // only update rotation or the on-ground bit.
            return Anomaly.NONE;
        }
        if (hasPosition && (!Double.isFinite(packetX) || !Double.isFinite(packetY)
                || !Double.isFinite(packetZ))) {
            initialized = false;
            wallRise = liquidClaims = powderSnowClaims = airGroundClaims = 0;
            lastDy = 0;
            return Anomaly.NONE;
        }
        double nx = hasPosition ? packetX : x;
        double ny = hasPosition ? packetY : y;
        double nz = hasPosition ? packetZ : z;
        double dy = ny - y;
        boolean previousUsable = initialized && lastAt > 0 && now - lastAt < 250
                && environment != null && now - environment.capturedAt() <= 200
                && environment.near(x, y, z);
        boolean usable = initialized && lastAt > 0 && now - lastAt < 250
                && environment != null && now - environment.capturedAt() <= 200
                && environment.near(nx, ny, nz);
        double impossibleWallRise = environment == null ? Double.POSITIVE_INFINITY
                : Math.max(1.5, environment.jumpStrength() + 0.5);
        boolean wallClip = previousUsable && environment.wallAdjacent()
                && environment.levitationAmplifier() < 0
                && dy > impossibleWallRise;
        // A wind charge or other vanilla impulse can lift a player alongside
        // a wall. Its rising velocity decays by gravity; Spider's repeated
        // wall climb instead keeps roughly the same upward step.
        boolean ballisticRise = lastDy > dy + 0.035 && lastDy - dy < 0.13;
        wallRise = usable && environment.wallAdjacent() && !ballisticRise
                && dy > 0.095 && dy < 0.29
                ? wallRise + 1 : 0;
        liquidClaims = usable && liquidSampleMatches(environment, nx, ny, nz)
                && environment.waterSurface() && !environment.ordinaryGround() && claimedGround
                ? liquidClaims + 1 : 0;
        boolean stationaryPowderSnowStep = Math.abs(dy) <= 0.001
                && Math.hypot(nx - x, nz - z) > 0.05;
        powderSnowClaims = usable && unsupportedPowderSnow && !environment.ordinaryGround()
                && (claimedGround || stationaryPowderSnowStep) ? powderSnowClaims + 1 : 0;
        airGroundClaims = usable && airSampleMatches(environment, nx, ny, nz)
                && environment.gravityAirborne() && claimedGround
                ? airGroundClaims + 1 : 0;
        x = nx; y = ny; z = nz;
        lastDy = dy;
        initialized = true;
        lastAt = now;
        if (wallClip) { wallRise = 0; return Anomaly.WALL_CLIP; }
        if (powderSnowClaims >= 4) { powderSnowClaims = 0; return Anomaly.POWDER_SNOW_WALK; }
        if (airGroundClaims >= 4) { airGroundClaims = 0; return Anomaly.AIR_GROUND_CLAIM; }
        if (wallRise >= 8) { wallRise = 0; return Anomaly.WALL_CLIMB; }
        if (liquidClaims >= 4) { liquidClaims = 0; return Anomaly.LIQUID_GROUND_CLAIM; }
        return Anomaly.NONE;
    }

    private boolean airSampleMatches(MotionEnvironment.Snapshot environment,
                                     double packetX, double packetY, double packetZ) {
        return Math.abs(environment.x() - packetX) <= 0.35
                && Math.abs(environment.y() - packetY) <= 0.20
                && Math.abs(environment.z() - packetZ) <= 0.35;
    }

    /** The environment's general prediction radius is too broad for surface materials. */
    private boolean liquidSampleMatches(MotionEnvironment.Snapshot environment,
                                        double packetX, double packetY, double packetZ) {
        return Math.abs(environment.x() - packetX) <= 0.35
                && Math.abs(environment.y() - packetY) <= 0.20
                && Math.abs(environment.z() - packetZ) <= 0.35;
    }
}

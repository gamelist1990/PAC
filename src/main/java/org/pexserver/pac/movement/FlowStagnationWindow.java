package org.pexserver.pac.movement;

import org.pexserver.pac.packet.JavaInputCapture;

/** Detects a player repeatedly remaining stationary in unobstructed flowing water. */
public final class FlowStagnationWindow {
    private double x, z, flowX, flowZ;
    private long lastAt;
    private boolean initialized;
    private int stillFrames;

    public boolean accept(boolean hasPosition, double packetX, double packetZ,
                          JavaInputCapture.Window input, WaterFlowEnvironment.Snapshot water,
                          long now) {
        return accept(hasPosition, packetX, packetZ, 0, input, water, now);
    }

    public boolean accept(boolean hasPosition, double packetX, double packetZ, float yaw,
                          JavaInputCapture.Window input, WaterFlowEnvironment.Snapshot water,
                          long now) {
        if (!hasPosition && !initialized) return false;
        double nx = hasPosition ? packetX : x;
        double nz = hasPosition ? packetZ : z;
        boolean idle = input == null || input.current() == null
                || !input.current().forward() && !input.current().backward()
                && !input.current().left() && !input.current().right()
                && !input.current().jump() && !input.current().shift();
        boolean stable = initialized && lastAt > 0 && now - lastAt < 250
                && water != null && now - water.capturedAt() < 200
                && Math.abs(water.x() - nx) < 1.5 && Math.abs(water.z() - nz) < 1.5
                && flowX * water.flowX() + flowZ * water.flowZ()
                    > 0.7 * Math.hypot(flowX, flowZ)
                    * Math.hypot(water.flowX(), water.flowZ());
        // EntityFluidInteraction applies a 0.0045 minimum water-current impulse
        // near rest; movement below that can safely be considered stagnant.
        boolean transverse = stable && input != null && input.current() != null
                && input.previous() == null && Float.isFinite(yaw)
                && !input.current().jump() && !input.current().shift()
                && transverseInput(input.current(), yaw, water);
        double displacement = idle ? Math.hypot(nx - x, nz - z)
                : transverse ? Math.abs((nx - x) * water.flowX() + (nz - z) * water.flowZ())
                    / Math.hypot(water.flowX(), water.flowZ()) : Double.POSITIVE_INFINITY;
        stillFrames = stable && displacement < (idle ? 0.003 : 0.0025)
                ? stillFrames + 1 : 0;
        x = nx; z = nz;
        lastAt = now;
        initialized = true;
        if (water != null) { flowX = water.flowX(); flowZ = water.flowZ(); }
        if (stillFrames < (idle ? 12 : 20)) return false;
        stillFrames = 0;
        return true;
    }

    private static boolean transverseInput(MotionPredictor.Input input, float yaw,
                                           WaterFlowEnvironment.Snapshot water) {
        double forward = input.forwardAxis(), strafe = input.strafeAxis();
        if (Math.hypot(forward, strafe) < 0.5) return false;
        double radians = Math.toRadians(yaw);
        double x = strafe * Math.cos(radians) - forward * Math.sin(radians);
        double z = forward * Math.cos(radians) + strafe * Math.sin(radians);
        double norm = Math.hypot(x, z) * Math.hypot(water.flowX(), water.flowZ());
        return norm > 0 && Math.abs(x * water.flowX() + z * water.flowZ()) / norm < 0.08;
    }
}

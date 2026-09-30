package org.pexserver.pac.check.shared;

import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

/** Combat view geometry helpers for captured attack rotations and target AABBs. */
final class CombatViewRay {
    private CombatViewRay() { }

    static boolean intersects(Vector origin, float yaw, float pitch,
                              BoundingBox box, double expansion, double maxDistance) {
        return Double.isFinite(intersectionDistance(origin, yaw, pitch, box, expansion, maxDistance));
    }

    /** Distance to the first point where the packet view ray enters an AABB. */
    static double intersectionDistance(Vector origin, float yaw, float pitch,
                                       BoundingBox box, double expansion, double maxDistance) {
        if (origin == null || box == null || !Float.isFinite(yaw) || !Float.isFinite(pitch)
                || !Double.isFinite(maxDistance) || maxDistance < 0) return Double.NaN;
        double yawRadians = Math.toRadians(yaw);
        double pitchRadians = Math.toRadians(pitch);
        double horizontal = Math.cos(pitchRadians);
        double dx = -Math.sin(yawRadians) * horizontal;
        double dy = -Math.sin(pitchRadians);
        double dz = Math.cos(yawRadians) * horizontal;
        double[] originValues = {origin.getX(), origin.getY(), origin.getZ()};
        double[] direction = {dx, dy, dz};
        double[] minimum = {box.getMinX() - expansion, box.getMinY() - expansion,
                box.getMinZ() - expansion};
        double[] maximum = {box.getMaxX() + expansion, box.getMaxY() + expansion,
                box.getMaxZ() + expansion};
        double near = 0;
        double far = maxDistance;
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(direction[axis]) < 1.0E-9) {
                if (originValues[axis] < minimum[axis] || originValues[axis] > maximum[axis]) return Double.NaN;
                continue;
            }
            double first = (minimum[axis] - originValues[axis]) / direction[axis];
            double second = (maximum[axis] - originValues[axis]) / direction[axis];
            if (first > second) {
                double swap = first;
                first = second;
                second = swap;
            }
            near = Math.max(near, first);
            far = Math.min(far, second);
            if (near > far) return Double.NaN;
        }
        return far >= 0 && near <= maxDistance ? Math.max(0, near) : Double.NaN;
    }

    /** Exact feasibility of a ray intersecting any linear interpolation of two AABBs.
     * Clip a convex polygon in (ray distance, interpolation fraction), rather than
     * expanding to the union box (which invents hittable space on diagonal moves).
     */
    static double sweptIntersectionDistance(Vector origin, float yaw, float pitch,
            BoundingBox from, BoundingBox to, double expansion, double maxDistance) {
        if (origin == null || from == null || to == null || !Float.isFinite(yaw)
                || !Float.isFinite(pitch) || !Double.isFinite(maxDistance) || maxDistance < 0)
            return Double.NaN;
        Vector direction = direction(yaw, pitch);
        double[] o = {origin.getX(), origin.getY(), origin.getZ()};
        double[] d = {direction.getX(), direction.getY(), direction.getZ()};
        double[] lo = {from.getMinX() - expansion, from.getMinY() - expansion, from.getMinZ() - expansion};
        double[] hi = {from.getMaxX() + expansion, from.getMaxY() + expansion, from.getMaxZ() + expansion};
        double[] nextLo = {to.getMinX() - expansion, to.getMinY() - expansion, to.getMinZ() - expansion};
        double[] nextHi = {to.getMaxX() + expansion, to.getMaxY() + expansion, to.getMaxZ() + expansion};
        java.util.List<double[]> polygon = new java.util.ArrayList<>(java.util.List.of(
                new double[] {0, 0}, new double[] {maxDistance, 0},
                new double[] {maxDistance, 1}, new double[] {0, 1}));
        for (int axis = 0; axis < 3; axis++) {
            if (!Double.isFinite(o[axis])) return Double.NaN;
            polygon = clip(polygon, -d[axis], nextLo[axis] - lo[axis], o[axis] - lo[axis]);
            polygon = clip(polygon, d[axis], hi[axis] - nextHi[axis], hi[axis] - o[axis]);
            if (polygon.isEmpty()) return Double.NaN;
        }
        return polygon.stream().mapToDouble(p -> p[0]).min().orElse(Double.NaN);
    }

    private static java.util.List<double[]> clip(java.util.List<double[]> polygon,
                                                double a, double b, double c) {
        java.util.List<double[]> result = new java.util.ArrayList<>();
        if (polygon.isEmpty()) return result;
        double[] previous = polygon.getLast();
        double previousValue = a * previous[0] + b * previous[1] - c;
        for (double[] current : polygon) {
            double value = a * current[0] + b * current[1] - c;
            if ((value <= 1.0e-9) != (previousValue <= 1.0e-9)) {
                double fraction = previousValue / (previousValue - value);
                result.add(new double[] {previous[0] + fraction * (current[0] - previous[0]),
                        previous[1] + fraction * (current[1] - previous[1])});
            }
            if (value <= 1.0e-9) result.add(current);
            previous = current; previousValue = value;
        }
        return result;
    }

    static Vector direction(float yaw, float pitch) {
        double yawRadians = Math.toRadians(yaw);
        double pitchRadians = Math.toRadians(pitch);
        double horizontal = Math.cos(pitchRadians);
        return new Vector(-Math.sin(yawRadians) * horizontal,
                -Math.sin(pitchRadians), Math.cos(yawRadians) * horizontal);
    }

    static boolean blockedBeforeTarget(double blockDistance, double targetDistance, double tolerance) {
        return Double.isFinite(blockDistance) && Double.isFinite(targetDistance)
                && blockDistance >= 0 && targetDistance >= 0
                && blockDistance + Math.max(0, tolerance) < targetDistance;
    }

    /**
     * Maximum forward projection of any point in the target AABB on the player's
     * horizontal facing axis. A negative value means the entire hitbox is behind.
     */
    static double maximumHorizontalProjection(Vector origin, float yaw, BoundingBox box) {
        if (origin == null || box == null || !Float.isFinite(yaw)) return Double.NaN;
        double radians = Math.toRadians(yaw);
        double forwardX = -Math.sin(radians);
        double forwardZ = Math.cos(radians);
        double centerX = (box.getMinX() + box.getMaxX()) * 0.5;
        double centerZ = (box.getMinZ() + box.getMaxZ()) * 0.5;
        double radiusX = (box.getMaxX() - box.getMinX()) * 0.5;
        double radiusZ = (box.getMaxZ() - box.getMinZ()) * 0.5;
        double centerProjection = (centerX - origin.getX()) * forwardX
                + (centerZ - origin.getZ()) * forwardZ;
        double aabbRadius = Math.abs(forwardX) * radiusX + Math.abs(forwardZ) * radiusZ;
        return centerProjection + aabbRadius;
    }

    /** Only classify a hit as impossible if even its nearest AABB edge is behind the player. */
    static boolean entirelyBehind(Vector origin, float yaw, BoundingBox box, double margin) {
        double maximumProjection = maximumHorizontalProjection(origin, yaw, box);
        return Double.isFinite(maximumProjection) && maximumProjection < -Math.max(0, margin);
    }
}

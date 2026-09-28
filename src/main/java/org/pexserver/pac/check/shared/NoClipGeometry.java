package org.pexserver.pac.check.shared;

import java.util.List;

/** Small, allocation-light geometry helpers for the experimental NoClip check. */
final class NoClipGeometry {
    private static final double EPSILON = 1.0E-9;

    record Aabb(double minX, double minY, double minZ,
                double maxX, double maxY, double maxZ) {
        Aabb {
            if (!(Double.isFinite(minX) && Double.isFinite(minY) && Double.isFinite(minZ)
                    && Double.isFinite(maxX) && Double.isFinite(maxY) && Double.isFinite(maxZ)))
                throw new IllegalArgumentException("AABB coordinates must be finite");
            if (minX > maxX || minY > maxY || minZ > maxZ)
                throw new IllegalArgumentException("AABB minimum must not exceed maximum");
        }

        boolean overlaps(Aabb other) {
            return maxX > other.minX + EPSILON && minX < other.maxX - EPSILON
                    && maxY > other.minY + EPSILON && minY < other.maxY - EPSILON
                    && maxZ > other.minZ + EPSILON && minZ < other.maxZ - EPSILON;
        }
    }

    record Hit(Aabb shape, double time) { }

    private NoClipGeometry() { }

    /**
     * Finds a collision that every plausible axis ordering would cross. Testing
     * all six orders accepts vanilla-style sliding and small step-up paths while
     * still catching movement that passes through the interior of a wall.
     */
    static Hit unavoidableHit(Aabb body, double dx, double dy, double dz,
                              List<Aabb> shapes, double tolerance,
                              boolean allowInitialOverlap) {
        int[][] orders = {
                {0, 1, 2}, {0, 2, 1}, {1, 0, 2},
                {1, 2, 0}, {2, 0, 1}, {2, 1, 0}
        };
        double[] deltas = {dx, dy, dz};
        Hit firstHit = null;
        for (int[] order : orders) {
            Hit pathHit = pathHit(body, order, deltas, shapes, tolerance, allowInitialOverlap);
            if (pathHit == null) return null;
            if (firstHit == null || pathHit.time() < firstHit.time()) firstHit = pathHit;
        }
        return firstHit;
    }

    private static Hit pathHit(Aabb body, int[] order, double[] deltas,
                              List<Aabb> shapes, double tolerance,
                              boolean allowInitialOverlap) {
        double x = 0, y = 0, z = 0;
        double travelled = 0;
        double total = Math.abs(deltas[0]) + Math.abs(deltas[1]) + Math.abs(deltas[2]);
        Hit earliest = null;
        for (int axis : order) {
            double amount = deltas[axis];
            if (Math.abs(amount) <= EPSILON) continue;
            double sx = x, sy = y, sz = z;
            if (axis == 0) x += amount;
            else if (axis == 1) y += amount;
            else z += amount;

            double dx = x - sx, dy = y - sy, dz = z - sz;
            for (Aabb shape : shapes) {
                if (body.overlaps(shape) && !allowInitialOverlap) continue;
                double time = segmentEntry(body, sx, sy, sz, dx, dy, dz, shape, tolerance);
                if (Double.isFinite(time)) {
                    double normalized = total <= EPSILON ? 0 : (travelled + Math.abs(amount) * time) / total;
                    if (earliest == null || normalized < earliest.time())
                        earliest = new Hit(shape, normalized);
                }
            }
            travelled += Math.abs(amount);
        }
        return earliest;
    }

    private static double segmentEntry(Aabb body, double startX, double startY, double startZ,
                                       double dx, double dy, double dz, Aabb shape, double tolerance) {
        double halfX = (body.maxX() - body.minX()) * 0.5;
        double halfY = (body.maxY() - body.minY()) * 0.5;
        double halfZ = (body.maxZ() - body.minZ()) * 0.5;
        double centerX = (body.minX() + body.maxX()) * 0.5 + startX;
        double centerY = (body.minY() + body.maxY()) * 0.5 + startY;
        double centerZ = (body.minZ() + body.maxZ()) * 0.5 + startZ;

        double minX = shape.minX() - halfX + tolerance;
        double maxX = shape.maxX() + halfX - tolerance;
        double minY = shape.minY() - halfY + tolerance;
        double maxY = shape.maxY() + halfY - tolerance;
        double minZ = shape.minZ() - halfZ + tolerance;
        double maxZ = shape.maxZ() + halfZ - tolerance;
        if (minX > maxX || minY > maxY || minZ > maxZ) return Double.NaN;

        double near = 0.0, far = 1.0;
        double[] interval = interval(centerX, dx, minX, maxX, near, far);
        if (interval == null) return Double.NaN;
        interval = interval(centerY, dy, minY, maxY, interval[0], interval[1]);
        if (interval == null) return Double.NaN;
        interval = interval(centerZ, dz, minZ, maxZ, interval[0], interval[1]);
        if (interval == null || interval[1] <= EPSILON) return Double.NaN;
        return Math.max(0, interval[0]);
    }

    private static double[] interval(double start, double delta, double min, double max,
                                     double near, double far) {
        if (Math.abs(delta) <= EPSILON)
            return start < min || start > max ? null : new double[]{near, far};
        double a = (min - start) / delta;
        double b = (max - start) / delta;
        if (a > b) { double temporary = a; a = b; b = temporary; }
        near = Math.max(near, a);
        far = Math.min(far, b);
        return near <= far + EPSILON ? new double[]{near, far} : null;
    }
}

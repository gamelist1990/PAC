package org.pexserver.pac.check.shared;

import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import java.util.List;

/** Evaluates every acknowledged/interpolating target and plausible attacker eye. */
final class CombatAttackGeometry {
    record Result(boolean viewMiss, boolean behind, double forwardProjection) { }
    static Result evaluate(List<Vector> origins, float yaw, float pitch,
            List<BoundingBox[]> segments, double expansion, double distance) {
        boolean miss = true, behind = true;
        double projection = Double.NEGATIVE_INFINITY;
        for (Vector origin : origins) for (BoundingBox[] segment : segments) {
            double ray = CombatViewRay.sweptIntersectionDistance(origin, yaw, pitch,
                    segment[0], segment[1], expansion, distance);
            if (Double.isFinite(ray)) return new Result(false, false,
                    Math.max(CombatViewRay.maximumHorizontalProjection(origin, yaw, segment[0]),
                            CombatViewRay.maximumHorizontalProjection(origin, yaw, segment[1])));
            double forward = Math.max(CombatViewRay.maximumHorizontalProjection(origin, yaw, segment[0]),
                    CombatViewRay.maximumHorizontalProjection(origin, yaw, segment[1]));
            projection = Math.max(projection, forward);
            behind &= Double.isFinite(forward) && forward < -0.03 - expansion;
        }
        return new Result(!origins.isEmpty() && !segments.isEmpty() && miss,
                !origins.isEmpty() && !segments.isEmpty() && behind, projection);
    }
}

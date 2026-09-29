package org.pexserver.pac.check.java.packet;

import org.pexserver.pac.check.core.AbstractCheck;
import org.pexserver.pac.check.core.PacketCheck;
import org.pexserver.pac.check.core.PacketContext;

public final class InvalidMovementCheck extends AbstractCheck implements PacketCheck {
    @Override public String key() { return "invalid-movement"; }

    @Override public void inspect(PacketContext context) {
        if (!context.flying().hasPositionChanged()) return;
        var location = context.location();
        if (invalidCoordinates(location.getX(), location.getY(), location.getZ())) {
            // These values cannot safely enter Paper's movement pipeline, even
            // when optional rollback/setback actions are globally disabled.
            context.event().setCancelled(true);
            flagLimited(context, "non-finite or out-of-bounds coordinates");
        }
    }

    static boolean invalidCoordinates(double x, double y, double z) {
        return !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || Math.abs(x) > 30_000_000
                || Math.abs(y) > 30_000_000
                || Math.abs(z) > 30_000_000;
    }
}

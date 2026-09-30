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

    public static boolean malformed(com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying flying) {
        var location = flying.getLocation();
        return malformed(flying.hasPositionChanged(), flying.hasRotationChanged(), location.getX(),
                location.getY(), location.getZ(), location.getYaw(), location.getPitch());
    }
    static boolean malformed(boolean position, boolean rotation, double x, double y, double z,
                             float yaw, float pitch) {
        return position && invalidCoordinates(x, y, z) || rotation && InvalidPitchCheck.invalidRotation(yaw, pitch);
    }
    public void rejectMalformed(org.pexserver.pac.PacPlugin plugin, java.util.UUID uuid) {
        if (plugin.enabled(uuid, this)) flagLimited(uuid, () -> plugin.flag(uuid, this, "malformed movement rejected before prediction"));
    }

    static boolean invalidCoordinates(double x, double y, double z) {
        return !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || Math.abs(x) > 30_000_000
                || Math.abs(y) > 30_000_000
                || Math.abs(z) > 30_000_000;
    }
}

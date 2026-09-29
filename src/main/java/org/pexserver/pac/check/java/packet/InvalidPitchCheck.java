package org.pexserver.pac.check.java.packet;

import org.pexserver.pac.check.core.AbstractCheck;
import org.pexserver.pac.check.core.PacketCheck;
import org.pexserver.pac.check.core.PacketContext;

public final class InvalidPitchCheck extends AbstractCheck implements PacketCheck {
    @Override public String key() { return "invalid-pitch"; }

    @Override public void inspect(PacketContext context) {
        if (!context.flying().hasRotationChanged()) return;
        float pitch = context.location().getPitch();
        float yaw = context.location().getYaw();
        if (invalidRotation(yaw, pitch)) {
            // Reject malformed protocol rotations independently of the
            // optional anti-cheat rollback switch.
            context.event().setCancelled(true);
            flagLimited(context, "invalid rotation");
        }
    }

    static boolean invalidRotation(float yaw, float pitch) {
        return !Float.isFinite(yaw) || !Float.isFinite(pitch)
                || pitch < -90.0f || pitch > 90.0f;
    }
}

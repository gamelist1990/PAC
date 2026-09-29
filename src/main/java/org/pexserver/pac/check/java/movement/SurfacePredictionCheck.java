package org.pexserver.pac.check.java.movement;

import org.pexserver.pac.check.core.AbstractCheck;
import org.pexserver.pac.check.core.PacketCheck;
import org.pexserver.pac.check.core.PacketContext;
import org.pexserver.pac.movement.SurfaceMotionSequence;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Covers motion that ordinary clear-ground and clear-air models cannot evaluate. */
public final class SurfacePredictionCheck extends AbstractCheck implements PacketCheck {
    private final ConcurrentHashMap<UUID, SurfaceMotionSequence> states = new ConcurrentHashMap<>();
    @Override public String key() { return "surface-prediction"; }
    @Override public boolean supportsBedrock() { return false; }

    @Override public void inspect(PacketContext context) {
        if (context.event().isCancelled()) return;
        if (context.timingUncertain()) {
            states.remove(context.uuid());
            return;
        }
        if (context.plugin().environment().authorizedFlightMovement(context.uuid())) {
            states.remove(context.uuid());
            return;
        }
        if (context.externalImpulse() != null
                || context.plugin().serverMotionGrant(context.uuid()) != null) {
            states.remove(context.uuid());
            return;
        }
        SurfaceMotionSequence state = states.computeIfAbsent(context.uuid(), ignored -> new SurfaceMotionSequence());
        var location = context.location();
        SurfaceMotionSequence.Anomaly anomaly;
        synchronized (state) {
            long now = System.currentTimeMillis();
            var powderSnow = context.plugin().environment().powderSnow(context.uuid());
            boolean unsupportedPowderSnow = !context.plugin().isBedrockPlayer(context.uuid())
                    && powderSnow != null && powderSnow.unauthorized()
                    && powderSnow.near(location.getX(), location.getY(), location.getZ(), now);
            anomaly = state.accept(context.flying().hasPositionChanged(),
                    location.getX(), location.getY(), location.getZ(),
                    context.flying().isOnGround(),
                    context.plugin().environment().get(context.uuid()), unsupportedPowderSnow, now);
        }
        if (anomaly == SurfaceMotionSequence.Anomaly.NONE) return;
        String detail = switch (anomaly) {
            case WALL_CLIMB -> "sustained wall climb without climbable support";
            case WALL_CLIP -> "impossible wall-adjacent vertical clip";
            case LIQUID_GROUND_CLAIM -> "repeated on-ground claim over unsupported liquid";
            case POWDER_SNOW_WALK -> "repeated on-ground claim while walking on powder snow without leather boots";
            case AIR_GROUND_CLAIM -> "repeated on-ground claim while server collision state confirms unsupported air";
            case NONE -> "";
        };
        flagLimited(context, detail);
        if (context.plugin().cancel(this, context.uuid())) {
            context.cancel(this);
            context.plugin().correctJavaToSafeGround(context.uuid());
        }
    }

    @Override public void forget(UUID uuid) { super.forget(uuid); states.remove(uuid); }
}

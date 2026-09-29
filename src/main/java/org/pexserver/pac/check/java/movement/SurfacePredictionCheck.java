package org.pexserver.pac.check.java.movement;

import org.pexserver.pac.check.core.AbstractCheck;
import org.pexserver.pac.check.core.PacketCheck;
import org.pexserver.pac.check.core.PacketContext;
import org.pexserver.pac.movement.SurfaceMotionSequence;
import org.pexserver.pac.movement.StableGroundTakeoffWindow;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Covers motion that ordinary clear-ground and clear-air models cannot evaluate. */
public final class SurfacePredictionCheck extends AbstractCheck implements PacketCheck {
    private final ConcurrentHashMap<UUID, SurfaceMotionSequence> states = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, StableGroundTakeoffWindow> takeoffs = new ConcurrentHashMap<>();
    @Override public String key() { return "surface-prediction"; }
    @Override public boolean supportsBedrock() { return false; }

    @Override public void inspect(PacketContext context) {
        if (context.event().isCancelled()) return;
        if (context.timingUncertain()) {
            states.remove(context.uuid());
            takeoffs.remove(context.uuid());
            return;
        }
        if (context.plugin().environment().authorizedFlightMovement(context.uuid())) {
            states.remove(context.uuid());
            takeoffs.remove(context.uuid());
            return;
        }
        if (context.externalImpulse() != null
                || context.plugin().serverMotionGrant(context.uuid()) != null) {
            states.remove(context.uuid());
            takeoffs.remove(context.uuid());
            return;
        }
        SurfaceMotionSequence state = states.computeIfAbsent(context.uuid(), ignored -> new SurfaceMotionSequence());
        StableGroundTakeoffWindow takeoff = takeoffs.computeIfAbsent(
                context.uuid(), ignored -> new StableGroundTakeoffWindow());
        var location = context.location();
        long now = System.currentTimeMillis();
        StableGroundTakeoffWindow.Sample takeoffSample;
        synchronized (takeoff) {
            var environment = context.plugin().environment();
            takeoffSample = takeoff.accept(context.flying().hasPositionChanged(),
                    location.getX(), location.getY(), location.getZ(),
                    environment.get(context.uuid()), environment.groundState(context.uuid()),
                    environment.groundTakeoffVelocity(context.uuid()),
                    environment.maxStepHeight(context.uuid()),
                    context.serverTiming().delayed(), now);
        }
        if (takeoffSample.excessive()) {
            flagLimited(context, () -> String.format(java.util.Locale.ROOT,
                    "stable-ground takeoff exceeded vanilla jump/step envelope: rise=%.3f jump=%.3f step=%.3f",
                    takeoffSample.rise(), takeoffSample.allowedJump(), takeoffSample.allowedStep()));
            if (context.plugin().cancel(this, context.uuid())) {
                context.cancel(this);
                context.plugin().correctJavaToSafeGround(context.uuid());
            }
            return;
        }
        SurfaceMotionSequence.Anomaly anomaly;
        synchronized (state) {
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
            case LIQUID_GROUND_CLAIM -> "repeated on-ground claim over unsupported liquid";
            case POWDER_SNOW_WALK -> "repeated on-ground claim while walking on powder snow without leather boots";
            case NONE -> "";
        };
        flagLimited(context, detail);
        if (context.plugin().cancel(this, context.uuid())) {
            context.cancel(this);
            context.plugin().correctJavaToSafeGround(context.uuid());
        }
    }

    @Override public void forget(UUID uuid) {
        super.forget(uuid);
        states.remove(uuid);
        takeoffs.remove(uuid);
    }
}

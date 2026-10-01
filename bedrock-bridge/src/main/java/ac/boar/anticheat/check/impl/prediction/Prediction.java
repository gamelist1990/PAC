package ac.boar.anticheat.check.impl.prediction;

import ac.boar.anticheat.Boar;
import ac.boar.anticheat.check.api.BaseCheck;
import ac.boar.anticheat.check.api.Check;
import ac.boar.api.anticheat.annotations.CheckInfo;
import ac.boar.anticheat.check.api.impl.OffsetHandlerCheck;
import ac.boar.anticheat.player.BoarPlayer;
import ac.boar.anticheat.prediction.engine.data.VectorType;
import ac.boar.anticheat.util.MathUtil;
import ac.boar.anticheat.util.math.Vec3;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;

import java.util.HashMap;
import java.util.Map;
import java.util.EnumSet;
import ac.boar.anticheat.check.impl.prediction.PredictionEvidence.Kind;

@CheckInfo(name = "Prediction")
public class Prediction extends BaseCheck implements OffsetHandlerCheck {
    private static final int JOIN_GRACE_TICKS = 20;
    private static final float MINIMUM_ACTIONABLE_OFFSET = 1.0E-3F;
    private static final float CLASSIFICATION_OFFSET = 0.05F;
    private static final float DIRECTION_MOVEMENT_SQUARED = 1.0E-4F;
    private static final float SPEED_EXCESS_SQUARED = 0.01F;

    private final Map<String, Check> checks = new HashMap<>();
    private final PredictionEvidence evidence = new PredictionEvidence();

    public Prediction(BoarPlayer player) {
        super(player);

        this.checks.put("Phase", new BaseCheck(player, "Phase", "", false));
        this.checks.put("Velocity", new BaseCheck(player, "Velocity", "", false));

        this.checks.put("Strafe", new BaseCheck(player, "Strafe", "", false));
        this.checks.put("Speed", new BaseCheck(player, "Speed", "", false));
        this.checks.put("Flight", new BaseCheck(player, "Flight", "", false));

        this.checks.put("Collisions", new BaseCheck(player, "Collisions", "", false));
    }

    @Override
    public void onPredictionComplete(float offset) {
        // Continuous client ticks remain valid during high ping and lag bursts.
        // Only missing inputs require the physics anchor to resynchronize.
        if (player.predictionResync) {
            resetEvidence();
            return;
        }
        if (player.tick < JOIN_GRACE_TICKS
                || player.sinceLoadingScreen < JOIN_GRACE_TICKS
                || offset < Math.max(player.getMaxOffset(), MINIMUM_ACTIONABLE_OFFSET)) {
            resetEvidence();
            return;
        }

        if (player.isDynamicMovementExempt()) {
            resetEvidence();
            return;
        }

        if (player.isVehicleTransitionExempt()) {
            resetEvidence();
            Boar.debug("[movement-debug] ignored prediction offset near vehicle tick=" + player.tick
                    + " offset=" + offset, Boar.DebugMessage.WARNING);
            return;
        }

        if (player.isWaterTransitionExempt()) {
            resetEvidence();
            Boar.debug("[movement-debug] ignored prediction offset during water transition tick="
                    + player.tick + " offset=" + offset
                    + " waterHeight=" + player.getFluidHeight(ac.boar.anticheat.data.Fluid.WATER),
                    Boar.DebugMessage.WARNING);
            return;
        }

        if (player.isItemUseTransitionExempt()) {
            resetEvidence();
            Boar.debug("[movement-debug] ignored prediction offset during item use tick="
                + player.tick + " offset=" + offset, Boar.DebugMessage.WARNING);
            return;
        }

        if (player.isEffectTransitionExempt()) {
            resetEvidence();
            Boar.debug("[movement-debug] ignored prediction offset during effect transition tick="
                + player.tick + " offset=" + offset,
                Boar.DebugMessage.WARNING);
            return;
        }

        if (player.isNearPartialHeightCollision()) {
            resetEvidence();
            Boar.debug("[movement-debug] ignored prediction offset near partial-height collision tick="
                + player.tick + " offset=" + offset, Boar.DebugMessage.WARNING);
            return;
        }

        if (player.isRecentStrongVerticalImpulse()
                && player.getInputData().contains(PlayerAuthInputData.VERTICAL_COLLISION)
                && !player.verticalCollision) {
            // An explosion/wind charge and the resulting bed collision can be
            // observed by the client before the compensated server collision.
            resetEvidence();
            return;
        }

        if (offset < CLASSIFICATION_OFFSET) {
            resetEvidence();
            return;
        }

        if (!shouldDoFail()) {
            resetEvidence();
            return;
        }

        EnumSet<Kind> suspicious = EnumSet.noneOf(Kind.class);
        Map<Kind, String> details = new HashMap<>();

        boolean claimedHorizontal = player.getInputData().contains(PlayerAuthInputData.HORIZONTAL_COLLISION);
        boolean claimedVertical = player.getInputData().contains(PlayerAuthInputData.VERTICAL_COLLISION);
        if (offset >= 0.25F
            && (claimedVertical != player.verticalCollision
            || claimedHorizontal != player.horizontalCollision)) {
            suspicious.add(Kind.Phase);
            details.put(Kind.Phase, "o: " + offset + ", expect: (" + player.horizontalCollision + "," + player.verticalCollision + "), actual: (" + claimedHorizontal + "," + claimedVertical + ")");
        }

        if (player.bestPossibility.getType() == VectorType.VELOCITY) {
            suspicious.add(Kind.Velocity);
        }

        if (offset >= 0.25F
                && player.unvalidatedTickEnd.distanceTo(player.velocity)
                < player.getMaxOffset()) {
            suspicious.add(Kind.Collisions);
        }

        Vec3 actual = player.unvalidatedPosition.subtract(player.prevUnvalidatedPosition);
        Vec3 predicted = player.position.subtract(player.prevUnvalidatedPosition);
        float squaredActual = actual.horizontalLengthSquared();
        float squaredPredicted = predicted.horizontalLengthSquared();
        if (squaredActual >= DIRECTION_MOVEMENT_SQUARED
                && squaredPredicted >= DIRECTION_MOVEMENT_SQUARED
                && opposingHorizontalMotion(actual, predicted)) {
            suspicious.add(Kind.Strafe);
            details.put(Kind.Strafe, "o: " + offset + ", expected direction: " + MathUtil.signAll(predicted).horizontalToString() + ", actual direction: " + MathUtil.signAll(actual).horizontalToString());
        }

        // Water-to-land acceleration is applied at different points by
        // Bedrock and the server prediction. The explicit transition exemption
        // handles normal exits; this additional margin avoids classifying the
        // small remaining surface impulse as Speed.
        float speedExcessThreshold = player.ticksSinceSwimming > 0
            && player.ticksSinceSwimming <= 20
            ? 0.04F
            : SPEED_EXCESS_SQUARED;
        // A speed classification needs both a prediction error and movement
        // outside a conservative vanilla ground envelope. A stale sprint flag
        // can underpredict an otherwise ordinary ~0.27 block/tick walk.
        double groundEnvelope = PredictionEvidence.speedEnvelope(player.getSpeed(), player.onGround);
        if (squaredActual - squaredPredicted > speedExcessThreshold
                && squaredActual > groundEnvelope * groundEnvelope) {
            suspicious.add(Kind.Speed);
            details.put(Kind.Speed, "o: " + offset + ", expected: " + squaredPredicted + ", actual: " + squaredActual);
        }

        if (Math.abs(player.position.y - player.unvalidatedPosition.y) >= 0.25F) {
            suspicious.add(Kind.Flight);
        }

        if (player.bestPossibility.getType() == VectorType.VELOCITY) {
            // Knockback has its own classification; do not also call it speed/flight.
            suspicious.retainAll(EnumSet.of(Kind.Phase, Kind.Velocity));
        }
        suspicious.removeIf(kind -> Boar.getConfig().disabledChecks().contains(kind.name()));
        EnumSet<Kind> confirmed = evidence.observe(player.tick, suspicious);
        if (confirmed.isEmpty()) return;

        float actionableThreshold = Math.max(
                Boar.getConfig().alertThreshold(), MINIMUM_ACTIONABLE_OFFSET);
        Boar.debug("[movement-debug] confirmed prediction tick=" + player.tick
                + " offset=" + offset + " kinds=" + confirmed
                + " predictedPos=" + player.position + " actualPos=" + player.unvalidatedPosition
                + " predictedDelta=" + player.velocity + " actualDelta=" + player.unvalidatedTickEnd,
                Boar.DebugMessage.WARNING);
        rewind();
        if (offset >= actionableThreshold) {
            for (Kind kind : confirmed) {
                fail(kind.name(), details.getOrDefault(kind, "o: " + offset));
            }
        }
    }

    private void resetEvidence() {
        evidence.reset();
    }

    /** A minor sign change in one component is common during legitimate turns. */
    static boolean opposingHorizontalMotion(Vec3 actual, Vec3 predicted) {
        double actualLength = Math.hypot(actual.x, actual.z);
        double predictedLength = Math.hypot(predicted.x, predicted.z);
        return actualLength > 0.08 && predictedLength > 0.08
                && (actual.x * predicted.x + actual.z * predicted.z)
                < -0.35 * actualLength * predictedLength;
    }

    private void rewind() {
        player.getTeleportUtil().rewind(player.tick);
    }

    public boolean shouldDoFail() {
        return player.tick >= JOIN_GRACE_TICKS
            && player.sinceLoadingScreen >= JOIN_GRACE_TICKS
            && player.tickSinceBlockResync <= 0
            && !player.insideUnloadedChunk
            && !player.getTeleportUtil().isTeleporting()
            && player.compensatedWorld.isChunkLoadedAt(
                player.position.x, player.position.z);
    }

    public void fail(String name, String verbose) {
        if (Boar.getConfig().disabledChecks().contains(name)) {
            return;
        }

        this.checks.get(name).fail(verbose);
    }
}

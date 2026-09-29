package org.pexserver.pac.movement;

import net.minecraft.util.Mth;

/** Candidate-based horizontal prediction for unobstructed, ordinary ground movement. */
public final class MotionPredictor {
    public record Motion(double dx, double dy, double dz) { }
    public record Result(double offset, Motion closest) { }
    public record Input(boolean forward, boolean backward, boolean left, boolean right,
                        boolean jump, boolean shift, boolean sprint) {
        public double forwardAxis() { return (forward ? 1 : 0) - (backward ? 1 : 0); }
        public double strafeAxis() { return (left ? 1 : 0) - (right ? 1 : 0); }
    }

    private static final double GROUND_FRICTION = (double) (0.6f * 0.91f);
    private static final double AIR_FRICTION = (double) 0.91f;
    /** Same near-perfect movement tolerance Grim uses to stop candidate iteration. */
    static final double EARLY_EXIT_OFFSET = 1.0e-5;
    // Player#getFlyingSpeed is 0.02f walking and 0.026f sprinting in air.
    // LocalPlayer.applyInput scales its input by 0.98 before travel.
    private static final double[] INPUTS = {-1, 0, 1};

    private MotionPredictor() { }

    public static Result predict(Motion previous, Motion actual, float yaw, boolean sprinting, boolean sneaking) {
        return predict(previous, actual, yaw, 0.1 * (sprinting ? 1.3 : 1.0), sneaking);
    }

    public static Result predict(Motion previous, Motion actual, float yaw,
                                 double movementSpeed, boolean sneaking) {
        return predict(previous, actual, yaw, movementSpeed, sneaking, false);
    }

    public static Result predict(Motion previous, Motion actual, float yaw,
                                 double movementSpeed, boolean sneaking, boolean usingItem) {
        // The 0.6f block friction gives the same acceleration as getSpeed().
        return predictCandidates(previous, actual, yaw, movementSpeed, GROUND_FRICTION,
                sneaking, usingItem);
    }

    public static Result predictAir(Motion previous, Motion actual, float yaw,
                                    boolean sprinting, boolean sneaking) {
        return predictAir(previous, actual, yaw, sprinting, sneaking, false);
    }

    public static Result predictAir(Motion previous, Motion actual, float yaw,
                                    boolean sprinting, boolean sneaking, boolean usingItem) {
        return predictCandidates(previous, actual, yaw,
                airAcceleration(sprinting),
                AIR_FRICTION, sneaking, usingItem);
    }

    public static Result predictInput(Motion previous, Motion actual, float yaw,
                                      double movementSpeed, boolean sneaking, boolean usingItem,
                                      Input input) {
        return predictOne(previous, actual, yaw, movementSpeed, GROUND_FRICTION,
                sneaking, usingItem, input.forwardAxis(), input.strafeAxis());
    }

    /** Vanilla 26.3 ground travel with the friction of a uniform full-block floor. */
    public static Result predictGround(Motion previous, Motion actual, float yaw,
                                       double movementSpeed, boolean sneaking, boolean usingItem,
                                       float blockFriction) {
        return predictGround(previous, actual, yaw, movementSpeed, sneaking, usingItem,
                blockFriction, 0.91f);
    }

    public static Result predictGround(Motion previous, Motion actual, float yaw,
                                       double movementSpeed, boolean sneaking, boolean usingItem,
                                       float blockFriction, float horizontalDrag) {
        return predictGround(previous, actual, yaw, movementSpeed, sneaking, usingItem,
                blockFriction, horizontalDrag, 1.0f);
    }

    public static Result predictGround(Motion previous, Motion actual, float yaw,
                                       double movementSpeed, boolean sneaking, boolean usingItem,
                                       float blockFriction, float horizontalDrag,
                                       float velocityMultiplier) {
        return predictCandidates(previous, actual, yaw, groundAcceleration(movementSpeed, blockFriction),
                groundRetention(blockFriction, horizontalDrag, velocityMultiplier),
                sneaking, usingItem);
    }

    public static Result predictGroundInput(Motion previous, Motion actual, float yaw,
                                            double movementSpeed, boolean sneaking, boolean usingItem,
                                            float blockFriction, Input input) {
        return predictGroundInput(previous, actual, yaw, movementSpeed, sneaking, usingItem,
                blockFriction, 0.91f, input);
    }

    public static Result predictGroundInput(Motion previous, Motion actual, float yaw,
                                            double movementSpeed, boolean sneaking, boolean usingItem,
                                            float blockFriction, float horizontalDrag, Input input) {
        return predictGroundInput(previous, actual, yaw, movementSpeed, sneaking, usingItem,
                blockFriction, horizontalDrag, 1.0f, input);
    }

    public static Result predictGroundInput(Motion previous, Motion actual, float yaw,
                                            double movementSpeed, boolean sneaking, boolean usingItem,
                                            float blockFriction, float horizontalDrag,
                                            float velocityMultiplier, Input input) {
        return predictOne(previous, actual, yaw, groundAcceleration(movementSpeed, blockFriction),
                groundRetention(blockFriction, horizontalDrag, velocityMultiplier),
                sneaking, usingItem, input.forwardAxis(), input.strafeAxis());
    }

    private static double groundAcceleration(double movementSpeed, float friction) {
        float speed = (float) movementSpeed;
        if (friction <= 0.6f) return speed;
        return speed * (0.21600002f / (friction * friction * friction));
    }

    public static double maximumGroundStep(Motion previous, double movementSpeed,
                                           float blockFriction, float horizontalDrag) {
        return maximumGroundStep(previous, movementSpeed, blockFriction, horizontalDrag, 1.0f);
    }

    public static double maximumGroundStep(Motion previous, double movementSpeed,
                                           float blockFriction, float horizontalDrag,
                                           float velocityMultiplier) {
        return Math.hypot(previous.dx(), previous.dz())
                * groundRetention(blockFriction, horizontalDrag, velocityMultiplier)
                + groundAcceleration(movementSpeed, blockFriction);
    }

    public static double maximumGroundStepClient(Motion previous, double movementSpeed,
                                                 float blockFriction, float horizontalDrag,
                                                 float sneakingSpeed, float itemUseMultiplier) {
        return maximumGroundStepClient(previous, movementSpeed, blockFriction, horizontalDrag,
                sneakingSpeed, itemUseMultiplier, 1.0f);
    }

    public static double maximumGroundStepClient(Motion previous, double movementSpeed,
                                                 float blockFriction, float horizontalDrag,
                                                 float sneakingSpeed, float itemUseMultiplier,
                                                 float velocityMultiplier) {
        double maximumInput = maximumClientInputLength(sneakingSpeed, itemUseMultiplier);
        return Math.hypot(previous.dx(), previous.dz())
                * groundRetention(blockFriction, horizontalDrag, velocityMultiplier)
                + groundAcceleration(movementSpeed, blockFriction) * maximumInput;
    }

    /** Conservative collision-free travel bound across position packets omitted by the client. */
    public static double maximumGroundTravelClient(Motion previous, double movementSpeed,
                                                   float blockFriction, float horizontalDrag,
                                                   float sneakingSpeed, float itemUseMultiplier,
                                                   int frames) {
        return maximumGroundTravelClient(previous, movementSpeed, blockFriction, horizontalDrag,
                sneakingSpeed, itemUseMultiplier, frames, 1.0f);
    }

    public static double maximumGroundTravelClient(Motion previous, double movementSpeed,
                                                   float blockFriction, float horizontalDrag,
                                                   float sneakingSpeed, float itemUseMultiplier,
                                                   int frames, float velocityMultiplier) {
        int count = Math.max(1, Math.min(40, frames));
        double speed = Math.hypot(previous.dx(), previous.dz());
        double retention = groundRetention(blockFriction, horizontalDrag, velocityMultiplier);
        double acceleration = groundAcceleration(movementSpeed, blockFriction)
                * maximumClientInputLength(sneakingSpeed, itemUseMultiplier);
        double distance = 0;
        for (int i = 0; i < count; i++) {
            speed = speed * retention + acceleration;
            distance += speed;
        }
        return distance;
    }

    private static double groundRetention(float blockFriction, float horizontalDrag,
                                          float velocityMultiplier) {
        return (double) blockFriction * horizontalDrag * velocityMultiplier;
    }

    static double maximumClientInputLength(float sneakingSpeed, float itemUseMultiplier) {
        // Symmetry reduces the nine key combinations to straight and diagonal.
        // Float rounding near the 1.0 clamp can make straight very slightly
        // larger than diagonal, so retain both candidates.
        ClientAxes straight = clientAxes(1, 0, sneakingSpeed, itemUseMultiplier);
        ClientAxes diagonal = clientAxes(1, 1, sneakingSpeed, itemUseMultiplier);
        return Math.min(1.0, Math.max(Math.hypot(straight.forward(), straight.strafe()),
                Math.hypot(diagonal.forward(), diagonal.strafe())));
    }

    public static double maximumAirStep(Motion previous, boolean sprinting, float horizontalDrag) {
        return Math.hypot(previous.dx(), previous.dz()) * horizontalDrag
                + airAcceleration(sprinting);
    }

    public static double maximumAirTravelClient(Motion previous, boolean sprinting,
                                                float horizontalDrag, float sneakingSpeed,
                                                float itemUseMultiplier, int frames) {
        int count = Math.max(1, Math.min(40, frames));
        double speed = Math.hypot(previous.dx(), previous.dz());
        double acceleration = airAcceleration(sprinting)
                * maximumClientInputLength(sneakingSpeed, itemUseMultiplier);
        double distance = 0;
        for (int i = 0; i < count; i++) {
            speed = speed * horizontalDrag + acceleration;
            distance += speed;
        }
        return distance;
    }

    public static double maximumAirVerticalTravel(double previousDy, double gravity,
                                                  float verticalDrag, boolean slowFalling,
                                                  int levitationAmplifier, float jumpStrength,
                                                  int frames) {
        int count = Math.max(1, Math.min(40, frames));
        return Math.max(verticalTravel(previousDy, gravity, verticalDrag,
                        slowFalling, levitationAmplifier, count),
                verticalTravel(jumpStrength, gravity, verticalDrag,
                        slowFalling, levitationAmplifier, count));
    }

    private static double verticalTravel(double velocity, double gravity, float verticalDrag,
                                         boolean slowFalling, int levitationAmplifier, int frames) {
        double total = 0;
        for (int i = 0; i < frames; i++) {
            velocity = AirPredictor.nextDisplacement(velocity, gravity, verticalDrag,
                    slowFalling, levitationAmplifier);
            total += velocity;
        }
        return Math.abs(total);
    }

    public static Result predictAirInput(Motion previous, Motion actual, float yaw,
                                         boolean sprinting, boolean sneaking, boolean usingItem,
                                         Input input) {
        return predictAirInput(previous, actual, yaw, sprinting, sneaking, usingItem, 0.91f, input);
    }

    public static Result predictAir(Motion previous, Motion actual, float yaw,
                                    boolean sprinting, boolean sneaking, boolean usingItem,
                                    float horizontalDrag) {
        return predictCandidates(previous, actual, yaw,
                airAcceleration(sprinting),
                horizontalDrag, sneaking, usingItem);
    }

    public static Result predictAirInput(Motion previous, Motion actual, float yaw,
                                         boolean sprinting, boolean sneaking, boolean usingItem,
                                         float horizontalDrag, Input input) {
        return predictOne(previous, actual, yaw,
                airAcceleration(sprinting),
                horizontalDrag, sneaking, usingItem,
                input.forwardAxis(), input.strafeAxis());
    }

    /** Minecraft 26.3 client input: keyboard normalization, 0.98 scale, item and sneak effects, square correction. */
    public static Result predictGroundClient(Motion previous, Motion actual, float yaw,
                                             double movementSpeed, float blockFriction,
                                             float horizontalDrag, float sneakingSpeed,
                                             float itemUseMultiplier) {
        return predictGroundClient(previous, actual, yaw, movementSpeed, blockFriction,
                horizontalDrag, sneakingSpeed, itemUseMultiplier, 1.0f);
    }

    public static Result predictGroundClient(Motion previous, Motion actual, float yaw,
                                             double movementSpeed, float blockFriction,
                                             float horizontalDrag, float sneakingSpeed,
                                             float itemUseMultiplier, float velocityMultiplier) {
        return predictCandidatesClient(previous, actual, yaw,
                groundAcceleration(movementSpeed, blockFriction),
                groundRetention(blockFriction, horizontalDrag, velocityMultiplier),
                sneakingSpeed, itemUseMultiplier);
    }

    public static Result predictGroundInputClient(Motion previous, Motion actual, float yaw,
                                                  double movementSpeed, float blockFriction,
                                                  float horizontalDrag, float sneakingSpeed,
                                                  float itemUseMultiplier, Input input) {
        return predictGroundInputClient(previous, actual, yaw, movementSpeed, blockFriction,
                horizontalDrag, sneakingSpeed, itemUseMultiplier, 1.0f, input);
    }

    public static Result predictGroundInputClient(Motion previous, Motion actual, float yaw,
                                                  double movementSpeed, float blockFriction,
                                                  float horizontalDrag, float sneakingSpeed,
                                                  float itemUseMultiplier, float velocityMultiplier,
                                                  Input input) {
        return predictOneClient(previous, actual, yaw,
                groundAcceleration(movementSpeed, blockFriction),
                groundRetention(blockFriction, horizontalDrag, velocityMultiplier),
                sneakingSpeed, itemUseMultiplier,
                input.forwardAxis(), input.strafeAxis());
    }

    public static Result predictAirClient(Motion previous, Motion actual, float yaw,
                                          boolean sprinting, float horizontalDrag,
                                          float sneakingSpeed, float itemUseMultiplier) {
        return predictCandidatesClient(previous, actual, yaw,
                airAcceleration(sprinting),
                horizontalDrag, sneakingSpeed, itemUseMultiplier);
    }

    public static Result predictAirInputClient(Motion previous, Motion actual, float yaw,
                                               boolean sprinting, float horizontalDrag,
                                               float sneakingSpeed, float itemUseMultiplier,
                                               Input input) {
        return predictOneClient(previous, actual, yaw,
                airAcceleration(sprinting),
                horizontalDrag, sneakingSpeed, itemUseMultiplier,
                input.forwardAxis(), input.strafeAxis());
    }

    private static double airAcceleration(boolean sprinting) {
        return (double) (sprinting ? 0.026f : 0.02f);
    }

    record ClientAxes(float forward, float strafe) { }

    static ClientAxes clientAxes(double forward, double strafe,
                                 float sneakingSpeed, float itemUseMultiplier) {
        // KeyboardInput normalizes Vec2(strafe, forward) before LocalPlayer
        // applies its 0.98, item-use and sneaking multipliers.
        float f = (float) forward, s = (float) strafe;
        float keyboardLength = Mth.sqrt(f * f + s * s);
        if (keyboardLength < 1.0E-4f) return new ClientAxes(0, 0);
        f /= keyboardLength;
        s /= keyboardLength;
        f *= 0.98f;
        s *= 0.98f;
        f *= itemUseMultiplier;
        s *= itemUseMultiplier;
        f *= sneakingSpeed;
        s *= sneakingSpeed;
        float length = Mth.sqrt(f * f + s * s);
        if (length == 0) return new ClientAxes(0, 0);
        float absForward = Math.abs(f), absStrafe = Math.abs(s);
        float ratio = absForward > absStrafe
                ? absStrafe / absForward : absForward / absStrafe;
        float distanceToSquare = Mth.sqrt(1.0f + Mth.square(ratio));
        float magnitude = Math.min(length * distanceToSquare, 1.0f);
        float inverseLength = 1.0f / length;
        return new ClientAxes(f * inverseLength * magnitude, s * inverseLength * magnitude);
    }

    private static Result predictOneClient(Motion previous, Motion actual, float yaw,
                                           double acceleration, double friction,
                                           float sneakingSpeed, float itemUseMultiplier,
                                           double forward, double strafe) {
        ClientAxes axes = clientAxes(forward, strafe, sneakingSpeed, itemUseMultiplier);
        float radians = yaw * 0.017453292f;
        double sin = Mth.sin((double) radians);
        double cos = Mth.cos((double) radians);
        double inputStrafe = axes.strafe(), inputForward = axes.forward();
        double inputLengthSquared = inputStrafe * inputStrafe + inputForward * inputForward;
        if (inputLengthSquared < 1.0E-7) return new Result(
                Math.hypot(actual.dx() - previous.dx() * friction,
                        actual.dz() - previous.dz() * friction),
                new Motion(previous.dx() * friction, 0, previous.dz() * friction));
        if (inputLengthSquared > 1.0) {
            double inverseLength = 1.0 / Math.sqrt(inputLengthSquared);
            inputStrafe *= inverseLength;
            inputForward *= inverseLength;
        }
        double inputX = inputStrafe * (double) (float) acceleration;
        double inputZ = inputForward * (double) (float) acceleration;
        double relativeX = inputX * cos - inputZ * sin;
        double relativeZ = inputZ * cos + inputX * sin;
        double dx = previous.dx * friction + relativeX;
        double dz = previous.dz * friction + relativeZ;
        return new Result(Math.hypot(actual.dx - dx, actual.dz - dz), new Motion(dx, 0, dz));
    }

    private static Result predictCandidatesClient(Motion previous, Motion actual, float yaw,
                                                  double acceleration, double friction,
                                                  float sneakingSpeed, float itemUseMultiplier) {
        float radians = yaw * 0.017453292f;
        double sin = Mth.sin((double) radians);
        double cos = Mth.cos((double) radians);
        double inputAcceleration = (double) (float) acceleration;
        double bestOffset = Double.POSITIVE_INFINITY;
        double bestDx = 0;
        double bestDz = 0;
        double residualX = actual.dx() - previous.dx() * friction;
        double residualZ = actual.dz() - previous.dz() * friction;
        // Invert the yaw rotation to try the input direction closest to the
        // observed acceleration first, as Grim does by sorting likely vectors.
        double localStrafe = residualX * cos + residualZ * sin;
        double localForward = residualZ * cos - residualX * sin;
        int preferredIndex = inputAxisIndex(localForward) * INPUTS.length
                + inputAxisIndex(localStrafe);
        for (int candidateOffset = 0; candidateOffset < INPUTS.length * INPUTS.length; candidateOffset++) {
            int candidateIndex = (preferredIndex + candidateOffset) % (INPUTS.length * INPUTS.length);
            double forward = INPUTS[candidateIndex / INPUTS.length];
            double strafe = INPUTS[candidateIndex % INPUTS.length];
            ClientAxes axes = clientAxes(forward, strafe, sneakingSpeed, itemUseMultiplier);
            double inputStrafe = axes.strafe(), inputForward = axes.forward();
            double inputLengthSquared = inputStrafe * inputStrafe + inputForward * inputForward;
            double dx, dz;
            if (inputLengthSquared < 1.0E-7) {
                dx = previous.dx() * friction;
                dz = previous.dz() * friction;
            } else {
                if (inputLengthSquared > 1.0) {
                    double inverseLength = 1.0 / Math.sqrt(inputLengthSquared);
                    inputStrafe *= inverseLength;
                    inputForward *= inverseLength;
                }
                double inputX = inputStrafe * inputAcceleration;
                double inputZ = inputForward * inputAcceleration;
                double relativeX = inputX * cos - inputZ * sin;
                double relativeZ = inputZ * cos + inputX * sin;
                dx = previous.dx() * friction + relativeX;
                dz = previous.dz() * friction + relativeZ;
            }
            double offset = Math.hypot(actual.dx() - dx, actual.dz() - dz);
            if (offset < bestOffset) {
                bestOffset = offset;
                bestDx = dx;
                bestDz = dz;
            }
            if (bestOffset <= EARLY_EXIT_OFFSET) break;
        }
        return new Result(bestOffset, new Motion(bestDx, 0, bestDz));
    }

    private static int inputAxisIndex(double value) {
        return value < 0 ? 0 : value > 0 ? 2 : 1;
    }

    private static Result predictOne(Motion previous, Motion actual, float yaw,
                                     double acceleration, double friction,
                                     boolean sneaking, boolean usingItem,
                                     double forward, double strafe) {
        double scale = (sneaking ? 0.3 : 1.0) * (usingItem ? 0.2 : 1.0);
        double f = forward * scale, s = strafe * scale;
        double length = Math.hypot(f, s);
        if (length > 1) { f /= length; s /= length; }
        double radians = Math.toRadians(yaw);
        double dx = previous.dx * friction + (s * Math.cos(radians) - f * Math.sin(radians)) * acceleration;
        double dz = previous.dz * friction + (f * Math.cos(radians) + s * Math.sin(radians)) * acceleration;
        return new Result(Math.hypot(actual.dx - dx, actual.dz - dz), new Motion(dx, 0, dz));
    }

    private static Result predictCandidates(Motion previous, Motion actual, float yaw,
                                            double acceleration, double friction,
                                            boolean sneaking, boolean usingItem) {
        double radians = Math.toRadians(yaw);
        double sin = Math.sin(radians);
        double cos = Math.cos(radians);
        double best = Double.POSITIVE_INFINITY;
        Motion closest = new Motion(0, 0, 0);
        for (double forward : INPUTS) {
            for (double strafe : INPUTS) {
                // Sneaking scales the input before Entity#getInputVector decides
                // whether its length exceeds one and needs normalization.
                double scale = (sneaking ? 0.3 : 1.0) * (usingItem ? 0.2 : 1.0);
                double f = forward * scale;
                double s = strafe * scale;
                double length = Math.hypot(f, s);
                if (length > 1) { f /= length; s /= length; }
                double dx = previous.dx * friction + (s * cos - f * sin) * acceleration;
                double dz = previous.dz * friction + (f * cos + s * sin) * acceleration;
                double error = Math.hypot(actual.dx - dx, actual.dz - dz);
                if (error < best) { best = error; closest = new Motion(dx, 0, dz); }
            }
        }
        return new Result(best, closest);
    }
}

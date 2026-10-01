package ac.boar.anticheat.prediction;

/** Ground jumps require simulated support, including when jump is held. */
public final class GroundJumpPolicy {
    private GroundJumpPolicy() {}

    public static boolean shouldJump(boolean onGround, boolean started, boolean jumping) {
        return onGround && (started || jumping);
    }
}

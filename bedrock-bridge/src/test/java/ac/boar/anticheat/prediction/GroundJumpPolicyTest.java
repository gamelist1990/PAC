package ac.boar.anticheat.prediction;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GroundJumpPolicyTest {
    @Test void staleGroundFlagCanRecoverOnlyFromVerifiedNearbySupport() {
        assertTrue(GroundJumpPolicy.shouldRecoverSupport(-0.0784F, 0));
        assertFalse(GroundJumpPolicy.shouldRecoverSupport(-0.0784F, -0.001F));
        assertFalse(GroundJumpPolicy.shouldRecoverSupport(0.3332F, 0));
    }

    @Test void heldJumpCanLaunchAgainAfterLanding() {
        assertTrue(GroundJumpPolicy.shouldJump(true, true, true));
        for (int tick = 0; tick < 10; tick++) {
            assertFalse(GroundJumpPolicy.shouldJump(false, false, true));
        }
        assertTrue(GroundJumpPolicy.shouldJump(true, false, true));
    }

    @Test void inputCannotCreateAnAirJumpOrJumpAfterRelease() {
        assertFalse(GroundJumpPolicy.shouldJump(false, true, true));
        assertFalse(GroundJumpPolicy.shouldJump(true, false, false));
        assertTrue(GroundJumpPolicy.shouldJump(true, true, false));
    }
}

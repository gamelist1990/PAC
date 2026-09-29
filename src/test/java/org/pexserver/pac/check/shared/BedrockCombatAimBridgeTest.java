package org.pexserver.pac.check.shared;

import org.junit.jupiter.api.Test;
import org.pexserver.pac.PacPlugin;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BedrockCombatAimBridgeTest {
    @Test void bedrockBridgeExposesInputModeAndAttackToSharedKillaura() throws Exception {
        assertNotNull(PacPlugin.class.getMethod("acceptBedrockAuthInput", UUID.class, long.class,
                double.class, double.class, double.class, double.class, double.class, double.class,
                float.class, float.class));
        assertNotNull(PacPlugin.class.getMethod("acceptBedrockAuthInput", UUID.class, long.class,
                double.class, double.class, double.class, double.class, double.class, double.class,
                float.class, float.class, String.class));
        assertNotNull(PacPlugin.class.getMethod("acceptBedrockAttack", UUID.class, int.class));
    }

    @Test void strictBedrockKillauraOnlyUsesMouseAndControllerFamilies() {
        assertTrue(KillAuraCheck.supportsBedrockInputMode("MOUSE"));
        assertTrue(KillAuraCheck.supportsBedrockInputMode("KEYBOARD_MOUSE"));
        assertTrue(KillAuraCheck.supportsBedrockInputMode("GAMEPAD"));
        assertTrue(KillAuraCheck.supportsBedrockInputMode("GAME_PAD"));
        assertTrue(KillAuraCheck.supportsBedrockInputMode("CONTROLLER"));

        assertFalse(KillAuraCheck.supportsBedrockInputMode("TOUCH"));
        assertFalse(KillAuraCheck.supportsBedrockInputMode("MOTION_CONTROLLER"));
        assertFalse(KillAuraCheck.supportsBedrockInputMode("VR"));
        assertFalse(KillAuraCheck.supportsBedrockInputMode("UNKNOWN"));
        assertFalse(KillAuraCheck.supportsBedrockInputMode(null));
    }
}

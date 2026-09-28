package org.pexserver.pac.check.shared;

import org.junit.jupiter.api.Test;
import org.pexserver.pac.PacPlugin;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class BedrockCombatAimBridgeTest {
    @Test void bedrockBridgeExposesAuthInputRotationToSharedKillaura() throws Exception {
        assertNotNull(PacPlugin.class.getMethod("acceptBedrockAuthInput", UUID.class, long.class,
                double.class, double.class, double.class, double.class, double.class, double.class,
                float.class, float.class));
    }
}

package org.pexserver.pac.check.shared;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class MxAimModelRuntimeTest {
    @Test void loadsAllBundledModelsAndRunsLegacyAndRnnInference() throws Exception {
        try (MxAimModelRuntime runtime = new MxAimModelRuntime(
                message -> { }, message -> fail(message), message -> fail(message))) {
            assertEquals(8, runtime.readyModelCount().get(30, TimeUnit.SECONDS));

            List<Float> legacyYaw = new ArrayList<>(600);
            List<Float> legacyPitch = new ArrayList<>(600);
            for (int i = 0; i < 600; i++) {
                legacyYaw.add((float) (Math.sin(i * 0.13) * 7.0));
                legacyPitch.add((float) (Math.cos(i * 0.17) * 3.0));
            }
            MxAimModelRuntime.Prediction legacy = runtime.predictLegacy(legacyYaw, legacyPitch)
                    .get(30, TimeUnit.SECONDS);
            assertEquals("MX legacy", legacy.architecture());
            assertNotNull(legacy.severity());
            assertNotNull(legacy.models());

            MxAimModelRuntime.Prediction rnn = runtime.predictRnn(
                    legacyYaw.subList(0, 150), legacyPitch.subList(0, 150)).get(30, TimeUnit.SECONDS);
            assertEquals("MX RNN", rnn.architecture());
            assertNotNull(rnn.severity());
            assertNotNull(rnn.detail());
        }
    }
}

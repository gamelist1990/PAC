package org.pexserver.pac.check.shared;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KillAuraCheckTest {
    @Test void roundedPitchAndSingleLargeAimTurnAreNotRandomizerEvidence() {
        MxAimHeuristic heuristic = new MxAimHeuristic();
        var input = new MxAimInput(-246.29993f, 20.541145f,
                246.29993f, 20.541145f, 0.0f, 0.0f, 20.541145f);

        var findings = heuristic.sample(input, 80, 80, false);

        assertFalse(findings.stream().anyMatch(finding -> finding.source().contains("randomizer flaw")),
                "a single rounded pitch value is normal aim input, not proof of a randomizer");
    }

    @Test void mxSuiteRemainsDeterministicForIdenticalSampleStreams() {
        MxAimSuite javaSuite = new MxAimSuite();
        MxAimSuite bedrockSuite = new MxAimSuite();
        long now = 1_000_000L;
        long lastAttack = now;
        float yaw = 0.0f, pitch = 0.0f;

        assertEquals(javaSuite.sample(yaw, pitch, now, lastAttack, false, false),
                bedrockSuite.sample(yaw, pitch, now, lastAttack, false, true));

        float[] yawSteps = {-4, -3, -6, -2, -5};
        float[] pitchSteps = {-3, -2, -5, -1, -4};
        // Let MX's own cinematic classifier observe an initial high-rate turn before counting windows.
        yaw -= yawSteps[0];
        pitch -= pitchSteps[0];
        now += 50;
        lastAttack = now;
        javaSuite.sample(yaw, pitch, now, lastAttack, false, false);
        bedrockSuite.sample(yaw, pitch, now, lastAttack, false, true);
        int rnnWindows = 0;
        int legacyWindows = 0;
        KillAuraCheck.ModelWindow firstRnn = null;
        for (int i = 0; i < 600; i++) {
            yaw -= yawSteps[i % yawSteps.length];
            pitch -= pitchSteps[i % pitchSteps.length];
            now += 50;
            lastAttack = now;
            MxAimSuite.Result javaResult = javaSuite.sample(yaw, pitch, now, lastAttack, false, false);
            MxAimSuite.Result bedrockResult = bedrockSuite.sample(yaw, pitch, now, lastAttack, false, true);
            assertEquals(javaResult, bedrockResult, "same rotation history must produce identical PAC findings");
            if (javaResult.modelWindows() != null) {
                if (javaResult.modelWindows().rnn() != null) {
                    rnnWindows++;
                    if (firstRnn == null) firstRnn = javaResult.modelWindows().rnn();
                }
                if (javaResult.modelWindows().legacy() != null) legacyWindows++;
            }
        }

        assertEquals(4, rnnWindows, "MX RNN consumes exact 150-rotation windows");
        assertEquals(1, legacyWindows, "MX legacy classifiers consume an exact 600-rotation window");
        assertNotNull(firstRnn);
        assertEquals(150, firstRnn.yaw().size());
        assertEquals(-4.0f, firstRnn.yaw().get(0));
        assertEquals(-3.0f, firstRnn.pitch().get(0));
    }

    @Test void integratedAimAnalysisRequiresItsSourceTwoWindowLinearPattern() {
        MxAimSuite suite = new MxAimSuite();
        long now = 5_000_000L;
        float yaw = 0.0f;
        List<MxAimSuite.Finding> findings = new ArrayList<>();
        suite.sample(yaw, 0.0f, now, now, false, false);

        for (int i = 0; i < 200; i++) {
            yaw += 3.0f;
            now += 50;
            MxAimSuite.Result result = suite.sample(yaw, 0.25f * ((i % 2 == 0) ? 1 : -1),
                    now, now, false, false);
            findings.addAll(result.findings());
        }

        assertTrue(findings.stream().anyMatch(finding -> finding.source().equals("MX aim analysis linear")),
                "the PAC Killaura path should run the imported MX analysis engine");
    }
}

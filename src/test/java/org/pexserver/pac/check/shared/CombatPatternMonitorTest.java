package org.pexserver.pac.check.shared;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CombatPatternMonitorTest {
    @Test void attacksAndSmoothRotationWithoutConfirmedHitsDoNotFlag() {
        var monitor = new CombatPatternMonitor();
        monitor.sampleRotation(0, 0, 1_000);
        for (int i = 1; i <= 100; i++) {
            long now = 1_000 + i * 50L;
            assertNull(monitor.attackPacket(i % 4, now));
            assertNull(monitor.sampleRotation(i, 0, now));
        }
    }

    @Test void lifecycleResetDiscardsConfirmedHitsAndPartialSwitchEvidence() {
        var monitor = new CombatPatternMonitor();
        monitor.confirmedHit(1, 1_000);
        monitor.confirmedHit(1, 1_001);
        monitor.attackPacket(1, 1_002);
        monitor.attackPacket(2, 1_020);
        monitor.attackPacket(3, 1_040);
        monitor.reset();
        for (int i = 0; i < 20; i++)
            assertNull(monitor.attackPacket(i % 4, 1_060 + i * 20L));
    }

    @Test void staleHitsDoNotValidateANewTargetSwitchBurst() {
        var monitor = new CombatPatternMonitor();
        monitor.confirmedHit(1, 1_000);
        monitor.confirmedHit(1, 1_001);
        for (int i = 0; i < 10; i++)
            assertNull(monitor.attackPacket(i % 4, 5_000 + i * 20L));
    }

    @Test void stableAimbotLikeRotationNeedsConfirmedCombatAndRepeatedEvidence() {
        CombatPatternMonitor monitor = new CombatPatternMonitor();
        long now = 1_000L;
        monitor.sampleRotation(0.0f, 0.0f, now);
        CombatPatternMonitor.Finding finding = null;

        for (int i = 1; i <= 50; i++) {
            now += 50L;
            if (i % 5 == 0) {
                monitor.attackPacket(42, now);
                monitor.confirmedHit(42, now);
            }
            finding = monitor.sampleRotation(i, 0.0f, now);
            if (finding != null) break;
        }

        assertNotNull(finding);
        assertEquals("aimbot-smoothing", finding.source());
        assertTrue(finding.metrics().get("confirmed_hits") >= 5.0);
        assertTrue(finding.metrics().get("rotation_delta_variance") < 0.0001);
    }

    @Test void variableAimDoesNotTriggerSmoothing() {
        CombatPatternMonitor monitor = new CombatPatternMonitor();
        long now = 2_000L;
        monitor.sampleRotation(0.0f, 0.0f, now);
        float yaw = 0.0f;
        float[] steps = {1.0f, 2.5f, 0.5f, 3.0f, 1.5f};

        for (int i = 1; i <= 100; i++) {
            now += 50L;
            if (i % 5 == 0) {
                monitor.attackPacket(42, now);
                monitor.confirmedHit(42, now);
            }
            yaw += steps[i % steps.length];
            assertNull(monitor.sampleRotation(yaw, (i % 3) * 0.25f, now));
        }
    }

    @Test void rapidTargetSwitchesNeedSeveralFastChangesAndPriorConfirmedHits() {
        CombatPatternMonitor monitor = new CombatPatternMonitor();
        long now = 10_000L;
        monitor.confirmedHit(1, now);
        monitor.confirmedHit(1, now + 1);
        monitor.attackPacket(1, now + 2);

        assertNull(monitor.attackPacket(2, now + 20));
        assertNull(monitor.attackPacket(3, now + 40));
        CombatPatternMonitor.Finding finding = monitor.attackPacket(4, now + 60);

        assertNotNull(finding);
        assertEquals("rapid-target-switch", finding.source());
        assertEquals(3.0, finding.metrics().get("rapid_switch_streak"));
    }

    @Test void yawDeltaWrapsAcrossTheSignedBoundary() {
        CombatPatternMonitor monitor = new CombatPatternMonitor();
        monitor.sampleRotation(359.0f, 0.0f, 1_000L);
        monitor.attackPacket(1, 1_050L);
        monitor.confirmedHit(1, 1_050L);
        monitor.sampleRotation(0.0f, 0.0f, 1_100L);

        // One wrapped degree is ordinary input; it must not poison/reset the profile.
        assertNull(monitor.sampleRotation(1.0f, 0.0f, 1_150L));
    }
}

package org.pexserver.pac.check.shared;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class XrayMiningProfileTest {
    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final XrayMiningProfile.Settings SETTINGS = new XrayMiningProfile.Settings(
            120_000L, 60, 0.08, 0.05, 0.15, 0.08, 18.0, 10.0);

    @Test void aSingleEnclosedOreIsNotEnoughToFlag() {
        XrayMiningProfile profile = new XrayMiningProfile();

        assertNull(profile.observe(1_000L, WORLD, 0, 20, 0,
                true, true, 5, false, true, SETTINGS));
        assertNull(profile.observe(1_100L, WORLD, 1, 20, 0,
                true, true, 5, false, true, SETTINGS));
    }

    @Test void repeatedHiddenOreFindingsAccumulateAndExportNumericEvidence() {
        XrayMiningProfile profile = new XrayMiningProfile();
        XrayMiningProfile.Finding finding = null;

        for (int i = 0; i < 60; i++) {
            assertNull(profile.observe(1_000L + i, WORLD, i, 20, 0,
                    false, false, 0, false, false, SETTINGS));
        }
        for (int i = 0; i < 3; i++) {
            finding = profile.observe(2_000L + i, WORLD, 100 + i, 10, 0,
                    true, true, 5, false, true, SETTINGS);
            if (i < 2) assertNull(finding);
        }

        assertNotNull(finding);
        assertTrue(finding.suspicion() >= SETTINGS.suspicionThreshold());
        assertTrue(finding.metrics().get("xray_window_blocks") >= 60.0);
        assertEquals(3.0, finding.metrics().get("xray_rare_ore_blocks"));
    }

    @Test void oreDensityAndHiddenOreEvidenceCombineOnlyAfterMinimumSampleSize() {
        XrayMiningProfile profile = new XrayMiningProfile();
        var settings = new XrayMiningProfile.Settings(120_000L, 20,
                0.08, 0.05, 0.15, 0.08, 11.0, 10.0);
        for (int i = 0; i < 19; i++) {
            assertNull(profile.observe(10_000L + i, WORLD, i, 20, 0,
                    false, false, 0, false, false, settings));
        }

        assertNull(profile.observe(10_100L, WORLD, 100, 10, 0,
                true, true, 5, false, true, settings));
        XrayMiningProfile.Finding finding = profile.observe(10_200L, WORLD, 101, 10, 0,
                true, true, 5, false, true, settings);

        assertNotNull(finding);
        assertTrue(finding.reason().contains("tracked-ore ratio"));
        assertTrue(finding.metrics().get("xray_tracked_ore_ratio") > 0.08);
    }

    @Test void repeatedDistantOreFindsContributeSuspicion() {
        XrayMiningProfile profile = new XrayMiningProfile();
        var settings = new XrayMiningProfile.Settings(120_000L, 60,
                0.08, 0.05, 0.15, 0.08, 8.0, 10.0);
        for (int i = 0; i < 3; i++) {
            assertNull(profile.observe(1_000L + i, WORLD, i * 20, 20, 0,
                    true, true, 5, false, false, settings));
        }
        XrayMiningProfile.Finding finding = profile.observe(1_004L, WORLD, 60, 20, 0,
                true, true, 5, false, false, settings);

        assertNotNull(finding);
        assertTrue(finding.reason().contains("distant ore finds"));
    }

    @Test void miningInAnotherWorldDoesNotCarryThePreviousWorldWindow() {
        XrayMiningProfile profile = new XrayMiningProfile();
        UUID otherWorld = UUID.fromString("00000000-0000-0000-0000-000000000002");
        profile.observe(1_000L, WORLD, 0, 20, 0, true, true, 5, false, true, SETTINGS);
        profile.observe(1_100L, WORLD, 1, 20, 0, true, true, 5, false, true, SETTINGS);

        XrayMiningProfile.Finding finding = profile.observe(1_200L, otherWorld, 2, 20, 0,
                true, true, 5, false, true, SETTINGS);

        assertNull(finding);
    }

    @Test void evidenceDecaysAndCanBeReportedAgainAfterCooldownByScoreRecovery() {
        XrayMiningProfile profile = new XrayMiningProfile();
        XrayMiningProfile.Finding first = null;
        for (int i = 0; i < 3; i++) {
            first = profile.observe(1_000L + i, WORLD, i, 20, 0,
                    true, true, 5, false, true, SETTINGS);
        }
        assertNotNull(first);

        // A sufficiently long clean period decays the internal suspicion and rearms the profile.
        XrayMiningProfile.Finding second = null;
        long now = 181_001L;
        for (int i = 0; i < 3; i++) {
            second = profile.observe(now + i, WORLD, 20 + i, 20, 0,
                    true, true, 5, false, true, SETTINGS);
        }
        assertNotNull(second);
    }
}

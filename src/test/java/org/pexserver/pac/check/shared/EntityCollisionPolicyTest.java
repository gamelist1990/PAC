package org.pexserver.pac.check.shared;

import org.bukkit.scoreboard.Team;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityCollisionPolicyTest {
    @Test void playersUseTeamCollisionRulesInsteadOfTheAdvisoryEntityFlag() {
        assertTrue(EntityCollisionPolicy.entityCollisionFlagAllows(true, false, false));
        assertTrue(EntityCollisionPolicy.entityCollisionFlagAllows(true, false, true));
        assertTrue(EntityCollisionPolicy.entityCollisionFlagAllows(true, true, false));
        assertTrue(EntityCollisionPolicy.entityCollisionFlagAllows(true, true, true));
    }

    @Test void mobCollisionExemptionsInvertTheFlagInBothDirections() {
        assertFalse(EntityCollisionPolicy.entityCollisionFlagAllows(false, false, false));
        assertTrue(EntityCollisionPolicy.entityCollisionFlagAllows(false, false, true));
        assertTrue(EntityCollisionPolicy.entityCollisionFlagAllows(false, true, false));
        assertFalse(EntityCollisionPolicy.entityCollisionFlagAllows(false, true, true));
    }

    @Test void scoreboardRulesOnlyAllowTheConfiguredTeamRelationship() {
        assertFalse(EntityCollisionPolicy.allows(Team.OptionStatus.NEVER, true));
        assertFalse(EntityCollisionPolicy.allows(Team.OptionStatus.FOR_OWN_TEAM, false));
        assertTrue(EntityCollisionPolicy.allows(Team.OptionStatus.FOR_OWN_TEAM, true));
        assertFalse(EntityCollisionPolicy.allows(Team.OptionStatus.FOR_OTHER_TEAMS, true));
        assertTrue(EntityCollisionPolicy.allows(Team.OptionStatus.FOR_OTHER_TEAMS, false));
        assertTrue(EntityCollisionPolicy.allows(Team.OptionStatus.ALWAYS, true));
        assertTrue(EntityCollisionPolicy.allows(Team.OptionStatus.ALWAYS, false));
        assertFalse(EntityCollisionPolicy.allowsPair(Team.OptionStatus.ALWAYS,
                Team.OptionStatus.NEVER, false));
        assertTrue(EntityCollisionPolicy.allowsPair(Team.OptionStatus.FOR_OWN_TEAM,
                Team.OptionStatus.FOR_OWN_TEAM, true));
    }

    @Test void paperWorldCollisionLimitsAndPlayerOnlyModeAreRespected() {
        assertFalse(EntityCollisionPolicy.allowsPaperWorldCollision(0, false, true, true, false));
        assertFalse(EntityCollisionPolicy.allowsPaperWorldCollision(8, true, false, false, false));
        assertFalse(EntityCollisionPolicy.allowsPaperWorldCollision(8, true, false, true, true));
        assertTrue(EntityCollisionPolicy.allowsPaperWorldCollision(8, true, true, true, true));
        assertTrue(EntityCollisionPolicy.allowsPaperWorldCollision(8, true, false, true, false));
        assertTrue(EntityCollisionPolicy.allowsPaperWorldCollision(8, false, false, false, true));
    }
}

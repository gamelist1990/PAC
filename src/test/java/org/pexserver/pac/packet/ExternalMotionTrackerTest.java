package org.pexserver.pac.packet;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalMotionTrackerTest {
    @Test void replacementDiscardsEarlierCombatImpulseAndItsTag() {
        ExternalMotionTracker tracker = new ExternalMotionTracker();
        UUID uuid = UUID.randomUUID();
        tracker.markCombatDamage(uuid, 1_000);
        tracker.addImpulse(uuid, 0.2, 0.3, 0, 1_010);
        tracker.velocity(uuid, 0.5, 0, 0, 1_600);

        ExternalMotionTracker.Impulse folded = tracker.since(uuid, 0, 1_600);
        assertEquals(0.5, folded.x());
        assertFalse(folded.combatKnockback());
        assertFalse(folded.additive());
    }

    @Test void combatImpulseAfterReplacementRemainsTaggedAndAdditive() {
        ExternalMotionTracker tracker = new ExternalMotionTracker();
        UUID uuid = UUID.randomUUID();
        tracker.velocity(uuid, 0.5, 0, 0, 1_000);
        tracker.markCombatDamage(uuid, 1_050);
        tracker.addImpulse(uuid, 0.2, 0.3, 0, 1_060);

        ExternalMotionTracker.Impulse folded = tracker.since(uuid, 0, 1_060);
        assertEquals(0.7, folded.x(), 1.0e-12);
        assertTrue(folded.combatKnockback());
        assertFalse(folded.additive());
    }

    @Test void distinctPluginVelocityAfterKnockbackIsNotRelabeledAsCombat() {
        ExternalMotionTracker tracker = new ExternalMotionTracker();
        UUID uuid = UUID.randomUUID();
        tracker.markCombatDamage(uuid, 1_000);

        tracker.velocity(uuid, 0.38, 0.40, 0.04, 1_010);
        long knockbackSequence = tracker.current(uuid, 1_010).sequence();
        assertTrue(tracker.current(uuid, 1_010).combatKnockback());

        // PacketEvents can observe the same knockback a second time after Bukkit.
        tracker.velocity(uuid, 0.3801, 0.4001, 0.0401, 1_025);
        assertTrue(tracker.current(uuid, 1_025).combatKnockback());

        // AirDash/Paper setVelocity shortly after the hit is a new authoritative
        // replacement, not another copy of the combat knockback.
        tracker.velocity(uuid, 0.80, 0.30, 0, 1_080);
        ExternalMotionTracker.Impulse airDash = tracker.current(uuid, 1_080);
        assertFalse(airDash.combatKnockback());

        ExternalMotionTracker.Impulse folded = tracker.since(uuid, knockbackSequence, 1_080);
        assertEquals(0.80, folded.x(), 1.0e-12);
        assertEquals(0.30, folded.y(), 1.0e-12);
        assertFalse(folded.combatKnockback());
        assertFalse(folded.additive());
    }
}

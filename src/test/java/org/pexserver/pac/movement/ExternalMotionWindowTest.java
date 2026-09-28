package org.pexserver.pac.movement;

import org.junit.jupiter.api.Test;
import org.pexserver.pac.packet.ExternalMotionTracker;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalMotionWindowTest {
    @Test void eachServerVelocitySequenceRebasesOnlyItsFirstMovementFrame() {
        var tracker = new ExternalMotionTracker();
        UUID uuid = UUID.randomUUID();
        tracker.velocity(uuid, 0.8, 0.42, 0, 1_000);
        var impulse = tracker.current(uuid, 1_010);
        assertNotNull(impulse);

        var window = new ExternalMotionWindow();
        assertTrue(window.rebase(impulse));
        assertFalse(window.rebase(impulse));
        assertFalse(window.rebase(impulse));

        tracker.velocity(uuid, -0.4, 0.2, 0, 1_200);
        assertTrue(window.rebase(tracker.current(uuid, 1_201)));
    }

    @Test void expiredOrInvalidImpulseCannotBlindPrediction() {
        var tracker = new ExternalMotionTracker();
        UUID uuid = UUID.randomUUID();
        tracker.velocity(uuid, Double.NaN, 0, 0, 1_000);
        assertNull(tracker.current(uuid, 1_001));

        tracker.velocity(uuid, 0.8, 0.42, 0, 1_000);
        var window = new ExternalMotionWindow();
        assertTrue(window.rebase(tracker.current(uuid, 1_100)));
        assertFalse(window.rebase(null));
        assertNull(tracker.current(uuid, 2_550));
    }

    @Test void velocityPacketsReplaceMotionWhileExplosionImpulseAddsToIt() {
        var window = new ExternalMotionWindow();
        var previous = new MotionPredictor.Motion(0.4, 0.2, -0.1);
        var velocity = new ExternalMotionTracker.Impulse(1, 1.3, 0.5, 0, 1_050, false);
        var replacement = window.seed(previous, velocity);
        assertEquals(1.3, replacement.dx(), 0);
        assertEquals(0.5, replacement.dy(), 0);
        assertEquals(0, replacement.dz(), 0);

        var explosion = new ExternalMotionTracker.Impulse(2, 0.2, 0.3, 0.4, 1_100, true);
        var additive = window.seed(previous, explosion);
        assertEquals(0.6, additive.dx(), 1.0e-12);
        assertEquals(0.5, additive.dy(), 1.0e-12);
        assertEquals(0.3, additive.dz(), 1.0e-12);
    }

    @Test void queuedPluginVelocityChangesFoldToTheVelocityTheClientWillUse() {
        var tracker = new ExternalMotionTracker();
        UUID uuid = UUID.randomUUID();
        tracker.velocity(uuid, 0.8, 0.4, 0, 1_000);
        long first = tracker.current(uuid, 1_000).sequence();
        tracker.addImpulse(uuid, 0.2, 0.1, -0.3, 1_001);
        tracker.velocity(uuid, -0.5, 0.25, 0.1, 1_002);
        tracker.addImpulse(uuid, 0.05, 0, 0.2, 1_003);

        var pending = tracker.since(uuid, first, 1_004);
        assertEquals(-0.45, pending.x(), 1.0e-12);
        assertEquals(0.25, pending.y(), 1.0e-12);
        assertEquals(0.3, pending.z(), 1.0e-12);
        assertFalse(pending.additive());
        assertNull(tracker.since(uuid, pending.sequence(), 1_004));
    }

    @Test void damageFollowedByServerVelocityTagsExpectedCombatKnockback() {
        var tracker = new ExternalMotionTracker();
        UUID uuid = UUID.randomUUID();
        tracker.markCombatDamage(uuid, 1_000);
        tracker.velocity(uuid, 0.4, 0.3, -0.2, 1_020);

        var impulse = tracker.since(uuid, 0, 1_021);
        assertNotNull(impulse);
        assertTrue(impulse.combatKnockback());
    }

    @Test void pluginVelocityWithoutRecentDamageIsNotLabeledAsCombatKnockback() {
        var tracker = new ExternalMotionTracker();
        UUID uuid = UUID.randomUUID();
        tracker.velocity(uuid, 0.8, 0.4, 0, 1_000);

        assertFalse(tracker.current(uuid, 1_001).combatKnockback());
    }

    @Test void multipleQueuedKnockbacksRemainAdditiveToTheExistingVelocity() {
        var tracker = new ExternalMotionTracker();
        UUID uuid = UUID.randomUUID();
        tracker.addImpulse(uuid, 0.1, 0.2, 0, 1_000);
        tracker.addImpulse(uuid, -0.05, 0.1, 0.3, 1_001);

        var pending = tracker.since(uuid, 0, 1_002);
        assertEquals(0.05, pending.x(), 1.0e-12);
        assertEquals(0.3, pending.y(), 1.0e-12);
        assertEquals(0.3, pending.z(), 1.0e-12);
        assertTrue(pending.additive());
    }
}

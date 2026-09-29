package org.pexserver.pac.movement;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SustainedSpeedEnvelopeTest {
    private static final long BASE_NANOS = System.nanoTime();
    @Test void repeatedVanillaSprintJumpsKeepMomentumAcrossLandingSnapshots() {
        sprintJumps(0, false);
    }

    @Test void repeatedSprintJumpsWithDelayedGroundSnapshotsAndCollisionGeometry() {
        sprintJumps(1, true);
        sprintJumps(2, true);
    }

    private void sprintJumps(int snapshotDelay, boolean geometry) {
        var envelope = new SustainedSpeedEnvelope();
        double x = 0, y = 64, horizontal = 0.28, vertical = 0;
        boolean grounded = true;
        boolean[] groundHistory = new boolean[161];
        groundHistory[0] = true;
        envelope.accept(x, y, 0, ground(0, x, y), null, BASE_NANOS, false);
        for (int tick = 1; tick <= 160; tick++) {
            if (grounded) {
                horizontal = horizontal * (0.6f * 0.91f) + 0.13f * 0.98f + 0.2;
                vertical = 0.42f;
            } else {
                horizontal = horizontal * 0.91f + 0.026f * 0.98f;
                vertical = (vertical - 0.08) * 0.98f;
            }
            x += horizontal;
            y = Math.max(64, y + vertical);
            grounded = y == 64;
            groundHistory[tick] = grounded;
            boolean sampledGround = groundHistory[Math.max(0, tick - snapshotDelay)];
            var collisions = geometry ? new MotionCollisionSnapshot(x - 3, 62, -3, x + 3, 70, 3,
                    0.6, 1.8, 0.6, 0.6,
                    List.of(new MotionCollisionSnapshot.Box(x - 3, 63, -3, x + 3, 64, 3)),
                    true, System.currentTimeMillis()) : null;
            var sample = envelope.accept(x, y, 0,
                    sampledGround ? ground(tick, x, y) : air(tick, x, y), collisions,
                    BASE_NANOS + tick * 50_000_000L, false);
            assertFalse(sample.flagged(), "vanilla jump tick " + tick
                    + " speed=" + sample.speed() + " legal=" + sample.legalSpeed());
        }
    }
    @Test void catchesPointFourFlightWhileStayingOnFlatGround() {
        var envelope = new SustainedSpeedEnvelope();
        envelope.accept(0, 64, 0, ground(0, 0, 64), null, BASE_NANOS, false);
        boolean flagged = false;
        for (int tick = 1; tick <= 12; tick++) {
            var sample = envelope.accept(tick * 0.4, 64, 0,
                    ground(tick, tick * 0.4, 64), null,
                    BASE_NANOS + tick * 50_000_000L, false);
            flagged |= sample.flagged();
            if (sample.flagged()) assertEquals(0, sample.rollbackAnchor().x(), 1.0e-9);
        }
        assertTrue(flagged, "flat flight must not reuse its observed speed as legal momentum forever");
    }

    @Test void delayedPluginAirDashUsesTheSentVelocityAndThenDecays() {
        var envelope = new SustainedSpeedEnvelope();
        envelope.accept(0, 64, 0, air(0, 0, 64), null, BASE_NANOS, false);
        envelope.serverVelocity(1.4, 0, 64, 0, BASE_NANOS + 50_000_000L);
        assertFalse(envelope.accept(0, 64, 0, air(1, 0, 64), null,
                BASE_NANOS + 100_000_000L, false).flagged());
        double x = 0, speed = 1.4;
        for (int tick = 2; tick <= 22; tick++) {
            x += speed;
            var sample = envelope.accept(x, 64, 0, air(tick, x, 64), null,
                    BASE_NANOS + tick * 50_000_000L + 50_000_000L, false);
            assertFalse(sample.flagged(), "authorized AirDash tail at tick " + tick);
            speed *= 0.91;
        }
    }

    @Test void serverVelocityDoesNotPermitConstantSpeedAfterDragShouldReduceIt() {
        var envelope = new SustainedSpeedEnvelope();
        envelope.serverVelocity(1.4, 0, 64, 0, BASE_NANOS);
        double x = 0;
        boolean flagged = false;
        for (int tick = 1; tick <= 28; tick++) {
            x += 1.4;
            flagged |= envelope.accept(x, 64, 0, air(tick, x, 64), null,
                    BASE_NANOS + tick * 50_000_000L, false).flagged();
        }
        assertTrue(flagged);
    }
    @Test void catchesWurstSpeedHackAcrossRepeatedLowHops() {
        var envelope = new SustainedSpeedEnvelope();
        double x = 0, y = 64;
        SustainedSpeedEnvelope.Sample finding = null;
        envelope.accept(x, y, 0, ground(0, x, y), null, BASE_NANOS, false);
        for (int tick = 1; tick <= 12; tick++) {
            boolean ground = tick % 3 == 1;
            if (!ground) y += tick % 3 == 2 ? 0.1 : -0.1;
            x += 0.66;
            var sample = envelope.accept(x, y, 0,
                    ground ? ground(tick, x, y) : air(tick, x, y), null,
                    BASE_NANOS + tick * 50_000_000L, false);
            if (sample.flagged()) { finding = sample; break; }
        }
        assertTrue(finding != null,
                "SpeedHack's 0.66 horizontal cap must be caught while its +0.1Y hop changes mode");
        assertEquals(0.66, finding.speed(), 1.0e-9);
        assertTrue(finding.legalSpeed() < 0.4,
                "ordinary sprint's sustainable vanilla envelope should stay below the Wurst cap");
        assertEquals(0, finding.rollbackAnchor().x(), 1.0e-9,
                "rollback should keep the first suspicious location");
    }

    @Test void catchesWurstSpeedAcrossTerrainVerticalAirFrames() {
        var envelope = new SustainedSpeedEnvelope();
        double x = 0;
        envelope.accept(x, 64, 0, ground(0, x, 64), null, BASE_NANOS, false);
        boolean flagged = false;
        for (int tick = 1; tick <= 12; tick++) {
            x += 0.66;
            flagged |= envelope.accept(x, 64.1, 0,
                    verticalAir(tick, x, 64.1), null,
                    BASE_NANOS + tick * 50_000_000L, false).flagged();
        }
        assertTrue(flagged,
                "terrain-safe vertical air must preserve motion-prediction evidence across a long low hop");
    }

    @Test void acceptsVanillaSprintSpeedAndDoesNotHideKnockbackAsAnInfraction() {
        var normal = new SustainedSpeedEnvelope();
        for (int tick = 1; tick <= 40; tick++) {
            var sample = normal.accept(tick * 0.28, 64, 0,
                    ground(tick, tick * 0.28, 64), null,
                    BASE_NANOS + tick * 50_000_000L, false);
            assertFalse(sample.flagged());
        }

        var impulse = new SustainedSpeedEnvelope();
        impulse.accept(0, 64, 0, ground(1, 0, 64), null,
                BASE_NANOS + 50_000_000L, false);
        var knockback = impulse.accept(0.8, 64, 0, ground(2, 0.8, 64), null,
                BASE_NANOS + 100_000_000L, true);
        assertFalse(knockback.flagged());
        double launchedX = 0.8;
        double launchedSpeed = 0.66;
        for (int tick = 3; tick <= 12; tick++) {
            launchedX += launchedSpeed;
            var sample = impulse.accept(launchedX, 64, 0,
                    ground(tick, launchedX, 64), null,
                    BASE_NANOS + tick * 50_000_000L, false);
            assertFalse(sample.flagged(), "a server impulse must retain its decaying momentum envelope");
            launchedSpeed *= 0.91;
        }
    }

        @Test void entityPushAllowanceIsModeledWhileEvidenceStillAccumulates() {
                var envelope = new SustainedSpeedEnvelope();
                var contact = new MotionCollisionSnapshot(-2, 62, -2, 3, 68, 2,
                                0.6, 1.8, 0.6, 0.6, List.of(), true, true, 1,
                                System.currentTimeMillis());
                envelope.accept(0, 64, 0, ground(0, 0, 64), null, BASE_NANOS, false);

                for (int tick = 1; tick <= 3; tick++) {
                        var sample = envelope.accept(tick * .16, 64, 0,
                                        ground(tick, tick * .16, 64), contact,
                                        BASE_NANOS + tick * 50_000_000L, false);
                        assertFalse(sample.flagged());
                }

                boolean flagged = false;
                for (int tick = 4; tick <= 15; tick++) {
                        flagged |= envelope.accept(tick * .66, 64, 0,
                                        ground(tick, tick * .66, 64), null,
                                        BASE_NANOS + tick * 50_000_000L, false).flagged();
                }
                assertTrue(flagged);
        }

    @Test void officialEntityPushImpulseIsRemovedBeforeSpeedEvaluation() {
        var envelope = new SustainedSpeedEnvelope();
        var contact = new MotionCollisionSnapshot(-2, 62, -2, 3, 68, 2,
                0.6, 1.8, 0.6, 0.6, List.of(), true, true, 1,
                false, MotionCollisionSnapshot.StepProfile.V1_21_PLUS,
                System.currentTimeMillis(),
                List.of(new MotionCollisionSnapshot.EntityPush(0.05, 0)));
        envelope.accept(0, 64, 0, ground(0, 0, 64), null, BASE_NANOS, false);

        var sample = envelope.accept(0.05, 64, 0,
                ground(1, 0.05, 64), contact, BASE_NANOS + 50_000_000L, false);
        assertFalse(sample.flagged());
        assertEquals(0, sample.speed(), 1.0e-12);
    }

    @Test void vanillaImpulseAcrossUnknownLaunchSampleRetainsThenDecaysMomentum() {
        var envelope = new SustainedSpeedEnvelope();
        envelope.accept(0, 64, 0, ground(0, 0, 64), null, BASE_NANOS, false);
        envelope.accept(0.8, 64.8, 0, unknown(1, 0.8, 64.8), null,
                BASE_NANOS + 50_000_000L, true);
        double x = 0.8;
        double speed = 0.73;
        for (int tick = 2; tick <= 16; tick++) {
            x += speed;
            var sample = envelope.accept(x, 64.8 + tick, 0,
                    air(tick, x, 64.8 + tick), null,
                    BASE_NANOS + tick * 50_000_000L, false);
            assertFalse(sample.flagged(), "the wind-charge launch must not become a speed infraction");
            speed *= 0.91;
        }
    }

    @Test void sustainedSpeedAfterImpulseCannotUseTheInitialMomentumForever() {
        var envelope = new SustainedSpeedEnvelope();
        envelope.accept(0, 64, 0, ground(0, 0, 64), null, BASE_NANOS, false);
        envelope.accept(0.8, 64, 0, ground(1, 0.8, 64), null,
                BASE_NANOS + 50_000_000L, true);
        double x = 0.8;
        boolean flagged = false;
        for (int tick = 2; tick <= 22; tick++) {
            x += 0.8;
            flagged |= envelope.accept(x, 64, 0, ground(tick, x, 64), null,
                    BASE_NANOS + tick * 50_000_000L, false).flagged();
        }
        assertTrue(flagged, "an impulse cannot excuse constant boosted speed after drag should slow it");
    }

    @Test void catchesWurstSpeedHackAcrossBriefUnknownGroundSamples() {
        var envelope = new SustainedSpeedEnvelope();
        double x = 0, y = 64;
        long started = BASE_NANOS;
        envelope.accept(x, y, 0, ground(0, x, y), null, started, false);
        envelope.accept(0.66, y + 0.1, 0, unknown(1, 0.66, y + 0.1), null,
                started + 50_000_000L, false);
        envelope.accept(1.32, y + 0.1, 0, unknown(2, 1.32, y + 0.1), null,
                started + 100_000_000L, false);
        var finding = envelope.accept(1.98, y + 0.1, 0,
                ground(3, 1.98, y + 0.1), null, started + 150_000_000L, false);
        assertTrue(finding.flagged(),
                "short ground/air sampling gaps must not erase Wurst's 1.8x acceleration evidence");
        assertEquals(0.66, finding.speed(), 1.0e-9);
        assertEquals(0, finding.rollbackAnchor().x(), 1.0e-9);
    }

    @Test void briefUnknownGroundSampleDoesNotFlagVanillaSprint() {
        var envelope = new SustainedSpeedEnvelope();
        long started = BASE_NANOS;
        envelope.accept(0, 64, 0, ground(0, 0, 64), null, started, false);
        assertFalse(envelope.accept(0.28, 64, 0, unknown(1, 0.28, 64), null,
                started + 50_000_000L, false).flagged());
        assertFalse(envelope.accept(0.56, 64, 0, ground(2, 0.56, 64), null,
                started + 100_000_000L, false).flagged());
    }

    @Test void customMovementSpeedDoesNotApplySneakingScaleWhileStanding() {
        var walking = new SustainedSpeedEnvelope();
        var sneaking = new SustainedSpeedEnvelope();
        var walkEnvironment = new MotionEnvironment.Snapshot(true, false, true, false,
                0, 1.0, 0, 64, 0, 1, System.currentTimeMillis());
        var sneakEnvironment = new MotionEnvironment.Snapshot(true, false, true, true,
                0, 1.0, 0, 64, 0, 1, System.currentTimeMillis());
        walking.accept(0, 64, 0, walkEnvironment, null, BASE_NANOS, false);
        sneaking.accept(0, 64, 0, sneakEnvironment, null, BASE_NANOS, false);

        double walkingLimit = walking.accept(0.1, 64, 0, walkEnvironment, null,
                BASE_NANOS + 50_000_000L, false).legalSpeed();
        double sneakingLimit = sneaking.accept(0.1, 64, 0, sneakEnvironment, null,
                BASE_NANOS + 50_000_000L, false).legalSpeed();
        assertTrue(walkingLimit > sneakingLimit,
                "the sneaking-speed attribute only scales input while the player sneaks");
    }

    @Test void collisionClippedSprintJumpKeepsItsVanillaHorizontalBoost() {
        var ceiling = new MotionCollisionSnapshot(-2, 62, -2, 3, 68, 2,
                0.6, 1.8, 0.6, 0.6,
                List.of(new MotionCollisionSnapshot.Box(-1, 65.863, -1, 1, 66.863, 1)),
                true, System.currentTimeMillis());
        var envelope = new SustainedSpeedEnvelope();
        envelope.accept(0, 64, 0, ground(0, 0, 64), ceiling, BASE_NANOS, false);
        var bonk = envelope.accept(0.5, 64.063, 0,
                ground(1, 0.5, 64.063), ceiling,
                BASE_NANOS + 50_000_000L, false);
        assertFalse(bonk.flagged(), "a sprint jump clipped by the ceiling is legal");
        assertTrue(bonk.legalSpeed() > 0.45,
                "the AABB-confirmed jump should retain the sprint impulse");
        assertFalse(envelope.accept(0.94, 64.063, 0,
                ground(2, 0.94, 64.063), ceiling,
                BASE_NANOS + 100_000_000L, false).flagged());
    }

    @Test void repeatedIceHeadBonksAccumulateLegitimateSprintMomentum() {
        var ceiling = new MotionCollisionSnapshot(-2, 62, -2, 20, 68, 2,
                0.6, 1.8, 0.6, 0.6,
                List.of(new MotionCollisionSnapshot.Box(-2, 65.8, -1, 20, 66.8, 1)),
                true, System.currentTimeMillis());
        var envelope = new SustainedSpeedEnvelope();
        double x = 0;
        envelope.accept(x, 64, 0, ice(0, x), ceiling, BASE_NANOS, false);
        double[] speeds = {0.40, 0.56, 0.70, 0.82, 0.92, 1.01};
        for (int tick = 1; tick <= speeds.length; tick++) {
            x += speeds[tick - 1];
            var sample = envelope.accept(x, 64, 0, ice(tick, x), ceiling,
                    BASE_NANOS + tick * 50_000_000L, false);
            assertFalse(sample.flagged(), "ice head bonk at tick " + tick);
            assertTrue(sample.legalSpeed() + 0.08 >= speeds[tick - 1],
                    "the collision-confirmed jump should retain accumulated impulse");
        }
    }

    @Test void matchingIceAccelerationWithoutCeilingStillFlags() {
        var empty = new MotionCollisionSnapshot(-2, 62, -2, 20, 68, 2,
                0.6, 1.8, 0.6, 0.6, List.of(), true, System.currentTimeMillis());
        var envelope = new SustainedSpeedEnvelope();
        double x = 0;
        envelope.accept(x, 64, 0, ice(0, x), empty, BASE_NANOS, false);
        boolean flagged = false;
        double[] speeds = {0.40, 0.56, 0.70, 0.82, 0.92, 1.01};
        for (int tick = 1; tick <= speeds.length; tick++) {
            x += speeds[tick - 1];
            flagged |= envelope.accept(x, 64, 0, ice(tick, x), empty,
                    BASE_NANOS + tick * 50_000_000L, false).flagged();
        }
        assertTrue(flagged, "ice alone cannot create repeated jump impulses");
    }

    @Test void walkingOntoClosedTrapdoorUsesItsRealCollisionHeight() {
        var trapdoor = new MotionCollisionSnapshot(-2, 62, -2, 3, 68, 2,
                0.6, 1.8, 0.6, 0.6,
                List.of(new MotionCollisionSnapshot.Box(0, 64, -0.5, 1, 64.1875, 0.5)),
                true, System.currentTimeMillis());
        var envelope = new SustainedSpeedEnvelope();
        long started = BASE_NANOS;
        var walking = new MotionEnvironment.Snapshot(true, false, false, false,
                0, 0.1, -0.5, 64, 0, 1, System.currentTimeMillis());
        envelope.accept(-0.5, 64, 0, walking, trapdoor, started, false);
        var step = envelope.accept(-0.15, 64.1875, 0,
                walking, trapdoor, started + 50_000_000L, false);
        assertFalse(step.flagged());
        assertTrue(step.legalSpeed() > 0.25,
                "the AABB-confirmed 3/16-block step must retain movement allowance");
    }

    @Test void artificialLowHopDoesNotGainSprintJumpAllowance() {
        var empty = new MotionCollisionSnapshot(-2, 62, -2, 3, 68, 2,
                0.6, 1.8, 0.6, 0.6, List.of(), true, System.currentTimeMillis());
        var envelope = new SustainedSpeedEnvelope();
        envelope.accept(0, 64, 0, ground(0, 0, 64), empty, BASE_NANOS, false);
        var hop = envelope.accept(0.66, 64.1, 0,
                ground(1, 0.66, 64.1), empty,
                BASE_NANOS + 50_000_000L, false);
        assertTrue(hop.flagged(), "a +0.1Y speed hop is not a vanilla jump or block step");
    }

    private static MotionEnvironment.Snapshot ground(int tick, double x, double y) {
        return new MotionEnvironment.Snapshot(true, false, true, false,
                0, 0.13, x, y, 0, tick, System.currentTimeMillis());
    }

    private static MotionEnvironment.Snapshot ice(int tick, double x) {
        return new MotionEnvironment.Snapshot(true, false, true, false, false,
                0, 0.13, x, 64, 0, tick, System.currentTimeMillis(),
                false, false, 0.98f, 0.08, 0.91f, 0.98f, 0.42f);
    }

    private static MotionEnvironment.Snapshot air(int tick, double x, double y) {
        return new MotionEnvironment.Snapshot(false, true, true, false,
                0, 0.13, x, y, 0, tick, System.currentTimeMillis());
    }

    private static MotionEnvironment.Snapshot unknown(int tick, double x, double y) {
        return new MotionEnvironment.Snapshot(false, false, true, false,
                0, 0.13, x, y, 0, tick, System.currentTimeMillis());
    }

    private static MotionEnvironment.Snapshot verticalAir(int tick, double x, double y) {
        return new MotionEnvironment.Snapshot(false, false, true, false, false,
                0, 0.13, x, y, 0, tick, System.currentTimeMillis(),
                false, false, 0.6f, 0.08, 0.91f, 0.98f, 0.42f,
                false, -1, true, 0.3f, 1.0f, 1.0f, 1.0f);
    }
}

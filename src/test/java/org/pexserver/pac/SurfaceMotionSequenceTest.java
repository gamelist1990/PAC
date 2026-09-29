package org.pexserver.pac;

import org.junit.jupiter.api.Test;
import org.pexserver.pac.movement.MotionEnvironment;
import org.pexserver.pac.movement.SurfaceMotionSequence;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SurfaceMotionSequenceTest {
    @Test void oneLiquidSnapshotCannotBecomeRepeatedEvidenceDuringServerLag() {
        var sequence = new SurfaceMotionSequence();
        var snapshot = surface(64, 1000, false, true);
        sequence.accept(true, 0, 64, 0, true, snapshot, 1000);
        for (int packet = 1; packet < 12; packet++)
            assertEquals(SurfaceMotionSequence.Anomaly.NONE,
                    sequence.accept(packet % 2 == 0, 0, 64, 0, true, snapshot, 1000 + packet));
    }
    @Test void oneAirSnapshotCannotBecomeRepeatedEvidenceDuringServerLag() {
        var sequence = new SurfaceMotionSequence();
        var snapshot = airborne(67, 1000);
        sequence.accept(true, 0, 67, 0, true, snapshot, 1000);
        for (int packet = 1; packet <= 12; packet++) {
            assertEquals(SurfaceMotionSequence.Anomaly.NONE,
                    sequence.accept(packet % 2 == 0, 0, 67, 0, true, snapshot, 1000 + packet));
        }
    }

    private static MotionEnvironment.Snapshot surface(double y, long at,
                                                       boolean wall, boolean liquid) {
        return new MotionEnvironment.Snapshot(false, false, false, false, false,
                0, 0.1, 0, y, 0, 1, at, wall, liquid);
    }

    private static MotionEnvironment.Snapshot airborne(double y, long at) {
        return new MotionEnvironment.Snapshot(false, true, false, false, false,
                0, 0.1, 0, y, 0, 1, at, false, false);
    }

    private static MotionEnvironment.Snapshot supportedWaterEdge(double y, long at) {
        return new MotionEnvironment.Snapshot(true, false, false, false, false,
                0, 0.1, 0, y, 0, 1, at, false, true);
    }

    @Test void repeatedSpiderRiseTriggersOnlyAfterNormalJumpEnvelope() {
        var sequence = new SurfaceMotionSequence();
        sequence.accept(true, 0, 64, 0, false, surface(64, 1000, true, false), 1000);
        for (int step = 1; step < 8; step++) {
            long at = 1000 + step * 50L;
            assertEquals(SurfaceMotionSequence.Anomaly.NONE,
                    sequence.accept(true, 0, 64 + step * 0.2, 0, false,
                            surface(64 + step * 0.2, at, true, false), at));
        }
        assertEquals(SurfaceMotionSequence.Anomaly.WALL_CLIMB,
                sequence.accept(true, 0, 65.6, 0, false,
                        surface(65.6, 1400, true, false), 1400));
    }

    @Test void deceleratingVanillaLaunchBesideWallIsNotSpider() {
        var sequence = new SurfaceMotionSequence();
        double y = 64;
        sequence.accept(true, 0, y, 0, false, surface(y, 1000, true, false), 1000);
        double dy = 0.8;
        for (int step = 1; step <= 16; step++) {
            y += dy;
            long at = 1000 + step * 50L;
            assertEquals(SurfaceMotionSequence.Anomaly.NONE,
                    sequence.accept(true, 0, y, 0, false,
                            surface(y, at, true, false), at));
            dy = (dy - 0.08) * 0.98;
        }
    }

    @Test void liquidGroundClaimsRequireRepeatedUnsupportedPackets() {
        var sequence = new SurfaceMotionSequence();
        sequence.accept(true, 0, 64, 0, true, surface(64, 1000, false, true), 1000);
        for (int step = 1; step < 4; step++) {
            long at = 1000 + step * 50L;
            assertEquals(SurfaceMotionSequence.Anomaly.NONE,
                    sequence.accept(true, 0, 64.05, 0, true,
                            surface(64.05, at, false, true), at));
        }
        assertEquals(SurfaceMotionSequence.Anomaly.LIQUID_GROUND_CLAIM,
                sequence.accept(true, 0, 64.05, 0, true,
                        surface(64.05, 1200, false, true), 1200));
    }

    @Test void alternatingJesusBypassOffsetsStillAccumulateLiquidGroundClaims() {
        var sequence = new SurfaceMotionSequence();
        sequence.accept(true, 0, 64.0, 0, true,
                surface(64.0, 1000, false, true), 1000);
        for (int step = 1; step < 4; step++) {
            long at = 1000 + step * 50L;
            double y = step % 2 == 1 ? 64.05 : 64.0;
            assertEquals(SurfaceMotionSequence.Anomaly.NONE,
                    sequence.accept(true, 0, y, 0, true,
                            surface(y, at, false, true), at));
        }
        assertEquals(SurfaceMotionSequence.Anomaly.LIQUID_GROUND_CLAIM,
                sequence.accept(true, 0, 64.05, 0, true,
                surface(64.05, 1200, false, true), 1200));
    }

    @Test void liquidOverlapDoesNotFlagWhenGroundStateConfirmsSolidSupport() {
        var sequence = new SurfaceMotionSequence();
        sequence.accept(true, 0, 64, 0, true, supportedWaterEdge(64, 1000), 1000);
        for (int step = 1; step <= 6; step++) {
            long at = 1000 + step * 50L;
            assertEquals(SurfaceMotionSequence.Anomaly.NONE,
                    sequence.accept(true, 0, 64, 0, true,
                            supportedWaterEdge(64, at), at));
        }
    }

    @Test void staleWaterSurfaceSnapshotDoesNotFollowPlayerOntoNearbyStep() {
        var sequence = new SurfaceMotionSequence();
        sequence.accept(true, 0, 64, 0, true, surface(64, 1000, false, true), 1000);
        for (int step = 1; step <= 8; step++) {
            long at = 1000 + step * 50L;
            double packetY = 64 + (step % 2 == 0 ? 0.25 : 0);
            assertEquals(SurfaceMotionSequence.Anomaly.NONE,
                    sequence.accept(true, 0.5, packetY, 0, true,
                            surface(64, at, false, true), at),
                    "a broad proximity match must not reuse a nearby water sample for a land step");
        }
    }

    @Test void snowShoeGroundClaimsNeedRepeatedPowderSnowContactWithoutLeatherBoots() {
        var sequence = new SurfaceMotionSequence();
        sequence.accept(true, 0, 64, 0, true, surface(64, 1000, false, false),
                false, 1000);
        for (int step = 1; step < 4; step++) {
            long at = 1000 + step * 50L;
            assertEquals(SurfaceMotionSequence.Anomaly.NONE,
                    sequence.accept(true, 0, 64, 0, true, surface(64, at, false, false),
                            true, at));
        }
        assertEquals(SurfaceMotionSequence.Anomaly.POWDER_SNOW_WALK,
                sequence.accept(true, 0, 64, 0, true,
                        surface(64, 1200, false, false), true, 1200));
    }

    @Test void powderSnowWalkingIsIgnoredWhenThePlayerHasLeatherBoots() {
        var sequence = new SurfaceMotionSequence();
        sequence.accept(true, 0, 64, 0, true, surface(64, 1000, false, false),
                false, 1000);
        for (int step = 1; step <= 8; step++) {
            long at = 1000 + step * 50L;
            assertEquals(SurfaceMotionSequence.Anomaly.NONE,
                    sequence.accept(true, 0, 64, 0, true,
                            surface(64, at, false, false), false, at));
        }
    }

    @Test void snowShoeMovementIsDetectedEvenWithoutGroundFlag() {
        var sequence = new SurfaceMotionSequence();
        sequence.accept(true, 0, 64, 0, false, surface(64, 1000, false, false),
                false, 1000);
        for (int step = 1; step < 4; step++) {
            long at = 1000 + step * 50L;
            assertEquals(SurfaceMotionSequence.Anomaly.NONE,
                    sequence.accept(true, step * 0.1, 64, 0, false,
                            surface(64, at, false, false), true, at));
        }
        assertEquals(SurfaceMotionSequence.Anomaly.POWDER_SNOW_WALK,
                sequence.accept(true, 0.4, 64, 0, false,
                        surface(64, 1200, false, false), true, 1200));
    }
    @Test void repeatedAirborneGroundSpoofTriggersAfterFourClaims() {
        var sequence = new SurfaceMotionSequence();
        sequence.accept(true, 0, 67.0, 0, false, airborne(67.0, 1000), 1000);
        for (int step = 1; step < 4; step++) {
            long at = 1000 + step * 50L;
            double y = 67.0 - step * 0.10;
            assertEquals(SurfaceMotionSequence.Anomaly.NONE,
                    sequence.accept(true, 0, y, 0, true, airborne(y, at), at));
        }
        assertEquals(SurfaceMotionSequence.Anomaly.AIR_GROUND_CLAIM,
                sequence.accept(true, 0, 66.6, 0, true,
                        airborne(66.6, 1200), 1200));
    }

    @Test void blockWalkFakeCobwebOrSnowSupportBecomesAirGroundClaim() {
        var sequence = new SurfaceMotionSequence();
        sequence.accept(true, 0, 67.0, 0, false, airborne(67.0, 1000), 1000);

        for (int step = 1; step < 4; step++) {
            long at = 1000 + step * 50L;
            assertEquals(SurfaceMotionSequence.Anomaly.NONE,
                    sequence.accept(true, step * 0.08, 67.0, 0, true,
                            airborne(67.0, at), at));
        }
        assertEquals(SurfaceMotionSequence.Anomaly.AIR_GROUND_CLAIM,
                sequence.accept(true, 0.32, 67.0, 0, true,
                        airborne(67.0, 1200), 1200));
    }

    @Test void isolatedAirborneGroundClaimDoesNotFlag() {
        var sequence = new SurfaceMotionSequence();
        sequence.accept(true, 0, 67.0, 0, false, airborne(67.0, 1000), 1000);
        assertEquals(SurfaceMotionSequence.Anomaly.NONE,
                sequence.accept(true, 0, 66.9, 0, true, airborne(66.9, 1050), 1050));
        assertEquals(SurfaceMotionSequence.Anomaly.NONE,
                sequence.accept(true, 0, 66.8, 0, false, airborne(66.8, 1100), 1100));
        for (int step = 1; step <= 3; step++) {
            long at = 1100 + step * 50L;
            double y = 66.8 - step * 0.1;
            assertEquals(SurfaceMotionSequence.Anomaly.NONE,
                    sequence.accept(true, 0, y, 0, true, airborne(y, at), at));
        }
    }


    @Test void vulcanStyleHugeWallRiseIsRejectedImmediately() {
        var sequence = new SurfaceMotionSequence();
        sequence.accept(true, 0, 64.0, 0, false,
                surface(64.0, 1000, true, false), 1000);

        assertEquals(SurfaceMotionSequence.Anomaly.WALL_CLIP,
                sequence.accept(true, 0, 73.6599696, 0, false,
                        surface(64.0, 1050, true, false), 1050));
    }

    @Test void highServerJumpStrengthDoesNotLookLikeWallClip() {
        var sequence = new SurfaceMotionSequence();
        var boosted = new MotionEnvironment.Snapshot(false, true, false, false, false,
                0, 0.1, 0, 64.0, 0, 1, 1000, true, false,
                0.6f, 0.08, 0.91f, 0.98f, 2.0f, false, -1, true);
        sequence.accept(true, 0, 64.0, 0, false, boosted, 1000);

        var next = new MotionEnvironment.Snapshot(false, true, false, false, false,
                0, 0.1, 0, 66.0, 0, 1, 1050, true, false,
                0.6f, 0.08, 0.91f, 0.98f, 2.0f, false, -1, true);
        assertEquals(SurfaceMotionSequence.Anomaly.NONE,
                sequence.accept(true, 0, 66.0, 0, false, next, 1050));
    }


    @Test void repeatedFastClimbExceedsVanillaLadderLimit() {
        var sequence = new SurfaceMotionSequence();
        var climb = new MotionEnvironment.ClimbSnapshot(true, 0, 64, 0, 1000);
        sequence.accept(true, 0, 64, 0, false, surface(64, 1000, false, false),
                false, climb, 1000);
        for (int step = 1; step < 3; step++) {
            long at = 1000 + step * 50L;
            double y = 64 + step * 0.2872;
            assertEquals(SurfaceMotionSequence.Anomaly.NONE,
                    sequence.accept(true, 0, y, 0, false,
                            surface(y, at, false, false), false,
                            new MotionEnvironment.ClimbSnapshot(true, 0, y, 0, at), at));
        }
        long at = 1150;
        double y = 64 + 3 * 0.2872;
        assertEquals(SurfaceMotionSequence.Anomaly.CLIMB_SPEED,
                sequence.accept(true, 0, y, 0, false,
                        surface(y, at, false, false), false,
                        new MotionEnvironment.ClimbSnapshot(true, 0, y, 0, at), at));
    }

    @Test void ordinaryLadderSpeedDoesNotFlag() {
        var sequence = new SurfaceMotionSequence();
        sequence.accept(true, 0, 64, 0, false, surface(64, 1000, false, false),
                false, new MotionEnvironment.ClimbSnapshot(true, 0, 64, 0, 1000), 1000);
        for (int step = 1; step <= 8; step++) {
            long at = 1000 + step * 50L;
            double y = 64 + step * 0.2;
            assertEquals(SurfaceMotionSequence.Anomaly.NONE,
                    sequence.accept(true, 0, y, 0, false,
                            surface(y, at, false, false), false,
                            new MotionEnvironment.ClimbSnapshot(true, 0, y, 0, at), at));
        }
    }

    @Test void climbClipIsRejectedFromPreviousLadderPosition() {
        var sequence = new SurfaceMotionSequence();
        sequence.accept(true, 0, 64, 0, false, surface(64, 1000, false, false),
                false, new MotionEnvironment.ClimbSnapshot(true, 0, 64, 0, 1000), 1000);
        assertEquals(SurfaceMotionSequence.Anomaly.CLIMB_CLIP,
                sequence.accept(true, 0, 69, 0, false,
                        surface(64, 1050, false, false), false,
                        new MotionEnvironment.ClimbSnapshot(true, 0, 64, 0, 1050), 1050));
    }


}

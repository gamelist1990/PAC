package org.pexserver.pac.movement;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class GroundSpoofDefenseTest {
    private MotionCollisionSnapshot floor(long now,boolean ceiling) {
        return new MotionCollisionSnapshot(-5,60,-5,5,74,5,.6,1.8,.6,.6,
            ceiling?List.of(new MotionCollisionSnapshot.Box(-5,63,-5,5,64,5),new MotionCollisionSnapshot.Box(-5,65.9,-5,5,66.9,5))
                :List.of(new MotionCollisionSnapshot.Box(-5,63,-5,5,64,5)),true,now);
    }
    private MotionEnvironment.Snapshot ground(long now) {
        return new MotionEnvironment.Snapshot(true,false,false,false,0,.1,0,64,0,(int)(now/50),now);
    }
    private MotionEnvironment.Snapshot air(double y,long now) {
        return new MotionEnvironment.Snapshot(false,true,false,false,0,.1,0,y,0,(int)(now/50),now);
    }
    @Test void vanillaFallingCoordinatesDoNotMakeForgedGroundBitLegal() {
        var window=new FalseGroundClaimWindow();double y=70,dy=0;int repairs=0,findings=0;
        for(int i=0;i<12;i++) {
            long now=1000+i*50;dy=AirPredictor.nextDisplacement(dy);y+=dy;
            var result=window.accept(true,0,y,0,true,air(y,now),floor(now,false),now,false);
            if(result.repair())repairs++;if(result.confirmed())findings++;
        }
        assertTrue(repairs>=8);assertTrue(findings>0);
    }
    @Test void ordinaryLandingAndGroundPositionlessPacketsAreNotRepaired() {
        var window=new FalseGroundClaimWindow();
        window.accept(true,0,64.2,0,false,air(64.2,1000),floor(1000,false),1000,false);
        assertFalse(window.accept(true,0,64,0,true,ground(1050),floor(1050,false),1050,false).repair());
        assertFalse(window.accept(false,0,0,0,true,ground(1100),floor(1100,false),1100,false).repair());
    }
    @Test void uncertaintyAndMissingCollisionGeometryAreNotNoFallEvidence() {
        var window=new FalseGroundClaimWindow();
        window.accept(true,0,70,0,true,air(70,1000),null,1000,false);
        assertFalse(window.accept(true,0,69.8,0,true,air(69.8,1050),null,1050,false).repair());
        assertFalse(window.accept(true,0,69.5,0,true,air(69.5,1100),floor(1100,false),1100,true).repair());
    }
    @Test void aobaSmallCriticalExcursionIsDetectedWithoutAClientSignature() {
        var window=new GroundCriticalWindow();
        assertFalse(window.accept(0,64,0,true,ground(1000),floor(1000,false),1000,false));
        assertFalse(window.accept(0,64+.03125,0,false,ground(1050),floor(1050,false),1050,false));
        assertFalse(window.accept(0,64+.0625,0,false,ground(1051),floor(1051,false),1051,false));
        assertTrue(window.accept(0,64,0,false,ground(1052),floor(1052,false),1052,false));
    }
    @Test void unrelatedSmallExcursionIsDetectedByPhysicsRatherThanExactOffsets() {
        var window=new GroundCriticalWindow();
        window.accept(0,64,0,true,ground(1000),floor(1000,false),1000,false);
        window.accept(0,64.024,0,false,ground(1050),floor(1050,false),1050,false);
        window.accept(0,64.082,0,false,ground(1060),floor(1060,false),1060,false);
        assertTrue(window.accept(0,64.003,0,false,ground(1070),floor(1070,false),1070,false));
    }
    @Test void headBonkAndOrdinaryJumpAreNotMiniCriticals() {
        var bonk=new GroundCriticalWindow();
        bonk.accept(0,64,0,true,ground(1000),floor(1000,true),1000,false);
        bonk.accept(0,64.08,0,false,ground(1050),floor(1050,true),1050,false);
        assertFalse(bonk.accept(0,64,0,false,ground(1100),floor(1100,true),1100,false));
        var jump=new GroundCriticalWindow();
        jump.accept(0,64,0,true,ground(1000),floor(1000,false),1000,false);
        jump.accept(0,64.42,0,false,ground(1050),floor(1050,false),1050,false);
        assertFalse(jump.accept(0,64,0,true,ground(1100),floor(1100,false),1100,false));
    }
}

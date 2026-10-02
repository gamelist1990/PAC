package org.pexserver.pac.movement;

import org.junit.jupiter.api.Test;
import org.pexserver.pac.packet.ExternalMotionTracker;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class KnockbackResponseWindowTest {
    private MotionEnvironment.Snapshot ground(long now) {
        return new MotionEnvironment.Snapshot(true,false,false,false,0,.1,0,64,0,(int)(now/50),now);
    }
    private MultiStepMotionPredictor.Frame frame(long now,boolean wall) {
        var box=new MotionCollisionSnapshot(-5,60,-5,8,75,5,.6,1.8,.6,.6,
            wall?List.of(new MotionCollisionSnapshot.Box(-5,63,-5,8,64,5),new MotionCollisionSnapshot.Box(.3,64,-5,1,70,5))
                :List.of(new MotionCollisionSnapshot.Box(-5,63,-5,8,64,5)),true,now);
        return new MultiStepMotionPredictor.Frame(0,List.of(new MotionPredictor.Input(false,false,false,false,false,false,false)),ground(now),false,box);
    }
    private KnockbackResponseWindow window(double x,double y,double z) {
        var w=new KnockbackResponseWindow();w.baseline(0,64,0);
        w.sent(new ExternalMotionTracker.Impulse(1,x,y,z,1000,false,true),1000);return w;
    }
    @Test void unacknowledgedPreHitSamplesNeverConfirmAntiKB() {
        var w=window(.8,0,0);
        for(int i=0;i<10;i++)assertFalse(w.sample(true,0,64,0,frame(1050+i*50,false),1050+i*50,false,false).confirmed());
    }
    @Test void acknowledgedFullHorizontalSuppressionCannotBecomeTheNewLegalBaseline() {
        var w=window(.8,0,0);w.acknowledge();
        assertFalse(w.sample(true,0,64,0,frame(1050,false),1050,false,false).confirmed());
        assertFalse(w.sample(true,0,64,0,frame(1100,false),1100,false,false).confirmed());
        assertTrue(w.sample(true,0,64,0,frame(1150,false),1150,false,false).confirmed());
    }
    @Test void suppressionWithOnlyPositionlessPacketsIsDetected() {
        var w=window(.8,.4,0);w.acknowledge();
        assertFalse(w.sample(false,0,0,0,frame(1050,false),1050,false,false).confirmed());
        assertFalse(w.sample(false,0,0,0,frame(1100,false),1100,false,false).confirmed());
        assertTrue(w.sample(false,0,0,0,frame(1150,false),1150,false,false).confirmed());
    }
    @Test void ordinaryFrictionResponseAndMovementBeforeReplyRemainLegal() {
        var w=window(.8,0,0);double x=0,v=.8;
        for(int i=1;i<=5;i++) {
            long now=1000+i*50;v*=.6*.91;x+=v;
            assertFalse(w.sample(true,x,64,0,frame(now,false),now,false,false).confirmed());
            if(i==2)w.acknowledge();
        }
    }
    @Test void aSolidWallCanLegitimatelyConsumeTheWholeImpulse() {
        var w=window(.8,.0,0);w.acknowledge();
        for(int i=1;i<=4;i++)assertFalse(w.sample(true,0,64,0,frame(1000+i*50,true),1000+i*50,false,false).confirmed());
    }
    @Test void missingGeometryOrRecoveryDoesNotCreateVelocityEvidence() {
        var w=window(.8,.4,0);w.acknowledge();
        assertFalse(w.sample(true,0,64,0,frame(1050,false),1050,true,false).confirmed());
        assertFalse(w.sample(true,0,64,0,frame(1100,false),1100,false,false).confirmed());
    }
    @Test void withholdingReplyKeepsMovementHeldUntilAcknowledged() {
        var w=window(.8,0,0);
        for(long now:new long[]{6001,6051,6101})assertTrue(w.sample(true,0,64,0,frame(now,false),now,false,false).expired());
        w.acknowledge();
        assertFalse(w.sample(true,.8*.6*.91,64,0,frame(6151,false),6151,false,false).expired());
    }
    @Test void queuedPreVelocityFrameAfterReplyIsNotEvidence() {
        var w=window(.8,0,0);w.acknowledge();
        assertFalse(w.sample(true,0,64,0,frame(1050,false),1050,false,false).confirmed());
        double x=0,v=.8;
        for(int i=2;i<6;i++) { long now=1000+i*50;v*=.6*.91;x+=v;
            assertFalse(w.sample(true,x,64,0,frame(now,false),now,false,false).confirmed()); }
    }
    @Test void ordinaryJumpResetPreservesHorizontalResponse() {
        var w=window(.8,.4,0);w.acknowledge();double x=0,y=64,vx=.8,vy=.42;
        for(int i=1;i<=5;i++) {
            long now=1000+i*50;
            var f=frame(now,false);
            var env=new MotionEnvironment.Snapshot(i==1,i!=1,false,false,0,.1,x,y,0,(int)(now/50),now);
            var input=new MotionPredictor.Input(false,false,false,false,i==1,false,false);
            var jumpFrame=new MultiStepMotionPredictor.Frame(0,List.of(input),env,i==1,f.collisions());
            vx*=i==1?.6*.91:.91;x+=vx;y+=vy;
            assertFalse(w.sample(true,x,y,0,jumpFrame,now,false,false).confirmed());
            vy=(vy-.08)*.98;
        }
    }
    @Test void legalAttackSlowdownAndPartialSuppressionAreDifferent() {
        var w=window(.8,0,0);w.acknowledge();double x=0,v=.8;
        for(int i=1;i<=4;i++) {long now=1000+i*50;if(i==1)v*=.6;v*=.6*.91;x+=v;
            assertFalse(w.sample(true,x,64,0,frame(now,false),now,false,i==1).confirmed());}
        var suppressed=window(.8,0,0);suppressed.acknowledge();
        assertFalse(suppressed.sample(true,.03,64,0,frame(1050,false),1050,false,false).confirmed());
        assertFalse(suppressed.sample(true,.06,64,0,frame(1100,false),1100,false,false).confirmed());
        assertTrue(suppressed.sample(true,.09,64,0,frame(1150,false),1150,false,false).confirmed());
    }
    @Test void additiveImpulseKeepsPreviouslyAppliedLegalCandidates() {
        var w=window(.8,0,0);w.acknowledge();double v=.8*.6*.91,x=v;
        assertFalse(w.sample(true,x,64,0,frame(1050,false),1050,false,false).confirmed());
        w.sent(new ExternalMotionTracker.Impulse(2,.4,0,0,1060,true,false),1060);w.acknowledge();
        v+=.4;
        for(int i=2;i<=5;i++) {long now=1000+i*50;v*=.6*.91;x+=v;
            assertFalse(w.sample(true,x,64,0,frame(now,false),now,false,false).confirmed());}
    }
    @Test void missingPreImpulseBaselineNeverInventsAResponse() {
        var w=new KnockbackResponseWindow();
        w.sent(new ExternalMotionTracker.Impulse(1,.8,.4,0,1000,false,true),1000);w.acknowledge();
        for(int i=1;i<5;i++)assertFalse(w.sample(true,i*.4,64+i*.3,0,frame(1000+i*50,false),1000+i*50,false,false).confirmed());
    }
    @Test void teleportLifecycleCannotReplayOldWorldCoordinates() {
        var w=window(.8,.4,0);w.invalidateBaseline();
        w.sent(new ExternalMotionTracker.Impulse(2,.8,.4,0,1050,false,true),1050);w.acknowledge();
        for(int i=1;i<5;i++)assertFalse(w.sample(true,100+i*.4,70+i*.3,0,frame(1050+i*50,false),1050+i*50,false,false).confirmed());
    }
    @Test void positionlessFirstPacketCannotSupplyAnUnknownBaseline() {
        var w=new KnockbackResponseWindow();
        w.sent(new ExternalMotionTracker.Impulse(1,.8,.4,0,1000,false,true),1000);w.acknowledge();
        assertFalse(w.sample(false,0,0,0,frame(1050,false),1050,false,false).confirmed());
        for(int i=2;i<5;i++)assertFalse(w.sample(true,100,70,0,frame(1000+i*50,false),1000+i*50,false,false).confirmed());
    }
    @Test void sprintJumpResetUsesTheActualHorizontalJumpImpulse() {
        var w=window(.8,.4,0);w.acknowledge();double x=0,y=64,z=0,vx=.8,vz=.2,vy=.42;
        for(int i=1;i<=5;i++) {
            long now=1000+i*50;
            var env=new MotionEnvironment.Snapshot(i==1,i!=1,true,false,0,.13,x,y,z,(int)(now/50),now);
            var input=new MotionPredictor.Input(false,false,false,false,i==1,false,true);
            var f=new MultiStepMotionPredictor.Frame(0,List.of(input),env,i==1,frame(now,false).collisions());
            double drag=i==1?.6*.91:.91;vx*=drag;vz*=drag;x+=vx;z+=vz;y+=vy;
            assertFalse(w.sample(true,x,y,z,f,now,false,false).confirmed());vy=(vy-.08)*.98;
        }
    }
    @Test void airborneJumpInputCannotResetAServerVerticalImpulse() {
        var w=new KnockbackResponseWindow();w.baseline(0,70,0);
        w.sent(new ExternalMotionTracker.Impulse(1,0,.4,0,1000,false,true),1000);w.acknowledge();
        for(int i=1;i<=3;i++) {
            long now=1000+i*50;
            var env=new MotionEnvironment.Snapshot(false,true,false,false,0,.1,0,70,0,(int)(now/50),now);
            var f=new MultiStepMotionPredictor.Frame(0,List.of(new MotionPredictor.Input(false,false,false,false,true,false,false)),env,true,frame(now,false).collisions());
            assertEquals(i==3,w.sample(true,0,70,0,f,now,false,false).confirmed());
        }
    }
    @Test void additiveVelocityAfterDragDoesNotDoubleScaleOrDoubleApplyTheImpulse() {
        var w=window(.8,0,0);w.acknowledge();double v=.8*.6*.91,x=v;
        assertFalse(w.sample(true,x,64,0,frame(1050,false),1050,false,false).confirmed());
        w.sent(new ExternalMotionTracker.Impulse(2,1.4,0,0,1060,true,false),1060);w.acknowledge();
        for(int i=2;i<=5;i++) {long now=1000+i*50;v=v*.6*.91+(i==2?1.4:0);x+=v;
            assertFalse(w.sample(true,x,64,0,frame(now,false),now,false,false).confirmed());}
    }
    @Test void delayedReplyAtTheFrameLimitStillRequiresALegalResponse() {
        var w=window(.8,0,0);
        for(int i=1;i<=100;i++)assertFalse(w.sample(true,0,64,0,frame(1000+i*50,false),1000+i*50,false,false).confirmed());
        assertTrue(w.sample(true,0,64,0,frame(6051,false),6051,false,false).expired());
        w.acknowledge();
        for(int i=1;i<=3;i++)assertEquals(i==3,w.sample(true,0,64,0,frame(6051+i*50,false),6051+i*50,false,false).confirmed());
    }
}

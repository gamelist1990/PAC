package org.pexserver.pac.movement;

import org.pexserver.pac.packet.ExternalMotionTracker;
import java.util.*;

/** Causal candidate replay. Observation never replaces an unapplied server impulse. */
public final class KnockbackResponseWindow {
    private record Path(double x,double y,double z,MotionPredictor.Motion velocity,boolean first) { }
    public record Finding(boolean confirmed,boolean expired,double residual,int samples) { }
    private static final MotionPredictor.Motion ZERO=new MotionPredictor.Motion(0,0,0);
    private final ArrayList<Path> paths=new ArrayList<>();
    private ExternalMotionTracker.Impulse impulse,combined;
    private double x,y,z;
    private MotionPredictor.Motion previous=ZERO;
    private boolean initialized,acknowledged;
    private int failedSamples,frames,acknowledgedFrames;
    private long sentAt;
    public void sent(ExternalMotionTracker.Impulse impulse,long now) {
        var old=this.impulse;
        var inherited=new ArrayList<Path>();
        if(impulse.additive())for(var p:paths)inherited.add(new Path(p.x,p.y,p.z,
                new MotionPredictor.Motion(p.velocity.dx()+impulse.x(),p.velocity.dy()+impulse.y(),
                        p.velocity.dz()+impulse.z()),true));
        combined=impulse.additive()&&old!=null&&!acknowledged&&now-sentAt>=0&&now-sentAt<=100
                ?new ExternalMotionTracker.Impulse(impulse.sequence(),old.x()+impulse.x(),old.y()+impulse.y(),
                        old.z()+impulse.z(),impulse.sentAt(),old.additive(),impulse.combatKnockback()):null;
        this.impulse=impulse;sentAt=now;acknowledged=false;
        paths.clear();paths.addAll(inherited);failedSamples=frames=acknowledgedFrames=0;
    }

    public void acknowledge() { if(impulse!=null&&!acknowledged) { acknowledged=true;frames=0; } }
    public void reset() { impulse=combined=null;paths.clear();failedSamples=frames=acknowledgedFrames=0; }
    public void invalidateBaseline() { reset();initialized=false;previous=ZERO; }
    public void baseline(double x,double y,double z) {
        if(initialized)previous=new MotionPredictor.Motion(x-this.x,y-this.y,z-this.z);
        this.x=x;this.y=y;this.z=z;initialized=true;
    }
    public Finding sample(boolean hasPosition,double nx,double ny,double nz,
            MultiStepMotionPredictor.Frame frame,long now,boolean uncertain,boolean attackSlowdown) {
        if(!hasPosition) { nx=x;ny=y;nz=z; }
        if(!initialized) { reset();if(hasPosition)baseline(nx,ny,nz);return new Finding(false,false,0,0); }
        if(impulse==null) { if(hasPosition)baseline(nx,ny,nz);return new Finding(false,false,0,0); }
        if(!acknowledged&&(now-sentAt>5000||frames>=100))return new Finding(false,true,0,0);
        var env=frame==null?null:frame.environment();
        var collision=frame==null?null:frame.collisions();
        if(uncertain||env==null||!GroundClaimPhysics.fresh(collision,now)
                ||!(env.ordinaryGround()||env.ordinaryAir())||env.specialVerticalSurface()
                ||env.stuckHorizontalMultiplier()<.999f||env.stuckVerticalMultiplier()<.999f
                ||now<env.capturedAt()||now-env.capturedAt()>200||++frames>100) {
            reset();if(hasPosition)baseline(nx,ny,nz);return new Finding(false,false,0,0);
        }
        // Every pre-reply physics frame can be the first frame applying velocity.
        // A final boundary seed accounts for receipt between the previous tick
        // and the reply. Later observations may not reseed server velocity.
        // A reply may be emitted by the network thread before an already queued
        // physics packet. Retain a second receipt boundary without counting it
        // as a velocity failure.
        if(acknowledged)acknowledgedFrames++;
        if(!acknowledged||acknowledgedFrames<=2) {
            seed(env,impulse);if(combined!=null)seed(env,combined);
        }
        ArrayList<Path> next=new ArrayList<>();
        double best=Double.POSITIVE_INFINITY;
        int attempts=0;
        double tolerance=hasPosition?.085:.115;
        for(Path path:paths) {
            var velocity=path.velocity;
            for(int slowdown=0;slowdown<(attackSlowdown?2:1);slowdown++) {
                var initial=slowdown==0?velocity:new MotionPredictor.Motion(velocity.dx()*.6,velocity.dy(),velocity.dz()*.6);
                for(var input:frame.inputs()) {
                    if(++attempts>4096) { reset();if(hasPosition)baseline(nx,ny,nz);return new Finding(false,false,0,0); }
                    var support=collision.resolve(path.x,path.y,path.z,0,-.0001,0,false);
                    boolean grounded=!support.isEmpty()&&support.stream().anyMatch(m->m.y()>-.000099);
                    if(grounded&&!Float.isFinite(env.groundFriction())) {
                        reset();if(hasPosition)baseline(nx,ny,nz);return new Finding(false,false,0,0);
                    }
                    for(int model=0;model<(path.first&&grounded?2:1);model++) {
                        boolean groundModel=grounded&&model==0;
                        var launch=grounded&&input.jump()&&env.sprinting()
                                ?MotionPredictor.sprintJumpImpulse(initial,frame.yaw()):initial;
                        float sneak=env.sneaking()?env.sneakingSpeed():1f;
                        var free=groundModel?MotionPredictor.predictGroundInputClient(launch,ZERO,frame.yaw(),env.movementSpeed(),
                                env.groundFriction(),env.horizontalDrag(),sneak,env.itemUseMultiplier(),input).closest()
                                :MotionPredictor.predictAirInputClient(launch,ZERO,frame.yaw(),env.sprinting(),
                                env.horizontalDrag(),sneak,env.itemUseMultiplier(),input).closest();
                        double vy=grounded&&input.jump()?env.surfaceJumpStrength():initial.dy();
                        var moves=collision.resolve(path.x,path.y,path.z,free.dx(),vy,free.dz(),grounded);
                        if(moves.isEmpty()) { reset();if(hasPosition)baseline(nx,ny,nz);return new Finding(false,false,0,0); }
                        for(var move:moves) {
                            double px=path.x+move.x(),py=path.y+move.y(),pz=path.z+move.z();
                            double error=Math.sqrt(square(px-nx)+square(py-ny)+square(pz-nz));
                            best=Math.min(best,error);
                            if(error<=tolerance) {
                                double nextY=Math.abs(move.y()-vy)>1e-7?0:
                                        AirPredictor.nextDisplacement(vy,env.gravity(),env.verticalDrag(),env.slowFalling(),env.levitationAmplifier());
                                next.add(new Path(px,py,pz,new MotionPredictor.Motion(
                                        Math.abs(move.x()-free.dx())>1e-7?0:free.dx(),nextY,
                                        Math.abs(move.z()-free.dz())>1e-7?0:free.dz()),false));
                            }
                        }
                    }
                }
            }
        }
        // Exhaustion is uncertainty, never evidence manufactured by beam pruning.
        if(next.size()>512) { reset();if(hasPosition)baseline(nx,ny,nz);return new Finding(false,false,0,0); }
        paths.clear();paths.addAll(next);
        if(acknowledged&&acknowledgedFrames>1)failedSamples=next.isEmpty()?failedSamples+1:0;
        boolean finding=acknowledged&&failedSamples>=2;
        if(hasPosition)baseline(nx,ny,nz);
        int samples=failedSamples;
        if(finding)reset();
        return new Finding(finding,false,best,samples);
    }
    private void seed(MotionEnvironment.Snapshot env,ExternalMotionTracker.Impulse impulse) {
        double vx=impulse.x(),vy=impulse.y(),vz=impulse.z();
        if(impulse.additive()) { vx+=previous.dx();vy+=previous.dy();vz+=previous.dz(); }
        paths.add(new Path(x,y,z,new MotionPredictor.Motion(vx,vy,vz),true));
        // Transport can deliver velocity before or after the client's drag
        // phase. Keep both phase orders instead of forcing a first-frame fit.
        double drag=env.horizontalDrag()*(env.ordinaryGround()?env.groundFriction():1);
        if(drag>.1) {
            paths.add(new Path(x,y,z,new MotionPredictor.Motion(vx/drag,vy,vz/drag),true));
            if(impulse.additive())paths.add(new Path(x,y,z,new MotionPredictor.Motion(
                    previous.dx()+impulse.x()/drag,
                    AirPredictor.nextDisplacement(previous.dy(),env.gravity(),env.verticalDrag(),env.slowFalling(),env.levitationAmplifier())+impulse.y(),
                    previous.dz()+impulse.z()/drag),true));
        }
        paths.add(new Path(x,y,z,new MotionPredictor.Motion(vx,AirPredictor.nextDisplacement(vy,env.gravity(),
                env.verticalDrag(),env.slowFalling(),env.levitationAmplifier()),vz),true));
    }
    private static double square(double x) { return x*x; }
}

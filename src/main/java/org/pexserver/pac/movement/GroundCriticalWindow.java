package org.pexserver.pac.movement;

/** Client-independent proof of a tiny unsupported rise/return that cannot be a vanilla jump. */
public final class GroundCriticalWindow {
    private boolean initialized, armed, unsupportedPeak;
    private double x,y,z,anchorX,anchorY,anchorZ,peak;
    private long startedAt;
    public boolean accept(double nx,double ny,double nz,boolean ground,
            MotionEnvironment.Snapshot env,MotionCollisionSnapshot collision,long now,boolean uncertain) {
        if(!Double.isFinite(nx)||!Double.isFinite(ny)||!Double.isFinite(nz)||uncertain||env==null
                ||now<env.capturedAt()||now-env.capturedAt()>200||!GroundClaimPhysics.fresh(collision,now)
                ||env.specialVerticalSurface()||env.surfaceJumpStrength()<.35f) {
            reset();return false;
        }
        if(!initialized) { x=nx;y=ny;z=nz;initialized=true;return false; }
        double dy=ny-y;
        boolean finding=false;
        if(!armed&&env.ordinaryGround()&&env.near(x,y,z)&&!ground&&dy>.004&&dy<env.surfaceJumpStrength()*.5
                &&Math.hypot(nx-x,nz-z)<.04
                &&GroundClaimPhysics.clearRise(collision,x,y,z,env.surfaceJumpStrength(),now)) {
            armed=true;anchorX=x;anchorY=y;anchorZ=z;peak=ny;startedAt=now;unsupportedPeak=false;
        }
        if(armed) {
            peak=Math.max(peak,ny);
            unsupportedPeak|=GroundClaimPhysics.unsupported(collision,nx,ny,nz,now);
            if(now<startedAt||now-startedAt>250||Math.hypot(nx-anchorX,nz-anchorZ)>.04
                    ||peak-anchorY>=env.surfaceJumpStrength()*.65) armed=false;
            else if(dy<-.004&&Math.abs(ny-anchorY)<.015&&unsupportedPeak&&peak-anchorY>.04) {
                finding=true;armed=false;
            }
        }
        x=nx;y=ny;z=nz;return finding;
    }
    public void reset() { initialized=false;armed=false;unsupportedPeak=false; }
}

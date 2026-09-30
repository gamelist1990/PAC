package org.pexserver.pac.movement;

/** A packet ground bit cannot override fresh collision geometry during a fall. */
public final class FalseGroundClaimWindow {
    public record Finding(boolean repair, boolean confirmed, int claims) { }
    private boolean initialized;
    private double x,y,z;
    private int claims;
    public Finding accept(boolean hasPosition, double nx,double ny,double nz, boolean ground,
            MotionEnvironment.Snapshot environment, MotionCollisionSnapshot collision, long now, boolean uncertain) {
        if (hasPosition && (!Double.isFinite(nx)||!Double.isFinite(ny)||!Double.isFinite(nz))) { reset(); return new Finding(false,false,0); }
        double px=hasPosition?nx:x, py=hasPosition?ny:y, pz=hasPosition?nz:z;
        boolean aligned=environment!=null && environment.near(px,py,pz);
        boolean eligible=initialized && !uncertain && aligned && environment.gravityAirborne()
                && !environment.specialVerticalSurface() && environment.stuckVerticalMultiplier()>=.999f
                && now>=environment.capturedAt() && now-environment.capturedAt()<=200
                && GroundClaimPhysics.unsupported(collision,px,py,pz,now);
        // The entire actual segment must be collision-free; endpoint-only tests
        // misclassify legitimate contact with steps and platforms between packets.
        if (eligible && hasPosition) {
            var moves=collision.resolve(x,y,z,px-x,py-y,pz-z,false);
            eligible=!moves.isEmpty() && moves.stream().allMatch(m -> Math.abs(m.x()-(px-x))<1e-7
                    && Math.abs(m.y()-(py-y))<1e-7 && Math.abs(m.z()-(pz-z))<1e-7);
        }
        boolean repair=eligible && ground;
        claims=repair?Math.min(20,claims+1):0;
        if(hasPosition) { x=nx;y=ny;z=nz;initialized=true; }
        return new Finding(repair,repair&&claims>=3,claims);
    }
    public void reset() { initialized=false;claims=0; }
}

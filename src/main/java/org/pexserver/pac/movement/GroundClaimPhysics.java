package org.pexserver.pac.movement;

/** Geometry proofs shared by false-ground and critical trajectory validation. */
public final class GroundClaimPhysics {
    private GroundClaimPhysics() { }
    public static boolean unsupported(MotionCollisionSnapshot collision, double x, double y, double z, long now) {
        if (!fresh(collision, now)) return false;
        var moves = collision.resolve(x,y,z,0,-0.04,0,false);
        return !moves.isEmpty() && moves.stream().allMatch(m -> Math.abs(m.y()+0.04)<1e-7);
    }
    public static boolean clearRise(MotionCollisionSnapshot collision, double x, double y, double z,
                                    double rise, long now) {
        if (!fresh(collision,now)) return false;
        var moves=collision.resolve(x,y,z,0,rise,0,false);
        return !moves.isEmpty() && moves.stream().allMatch(m -> Math.abs(m.y()-rise)<1e-7);
    }
    public static boolean fresh(MotionCollisionSnapshot collision,long now) {
        return collision!=null && collision.complete() && !collision.hardEntityCollisionPossible()
                && !collision.entityPushPossible() && now>=collision.capturedAt() && now-collision.capturedAt()<=200;
    }
}

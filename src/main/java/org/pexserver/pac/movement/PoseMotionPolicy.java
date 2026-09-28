package org.pexserver.pac.movement;

import org.bukkit.entity.Pose;

/** A swimming-shaped body on dry land still uses land physics with slow input. */
public final class PoseMotionPolicy {
    private PoseMotionPolicy() { }
    public static boolean slowInput(boolean sneaking, Pose pose, boolean gliding, boolean inWater) {
        return sneaking || pose == Pose.SNEAKING
                || !inWater && (pose == Pose.SWIMMING || pose == Pose.FALL_FLYING && !gliding);
    }

    public static final class Transition {
        private double width, height;
        private boolean initialized;
        private long until;
        public void sample(double width, double height, long now, int ping) {
            if (initialized && (Math.abs(this.width - width) > .001 || Math.abs(this.height - height) > .001))
                until = now + Math.max(100L, Math.min(300L, (long) ping + 100));
            this.width = width;
            this.height = height;
            initialized = true;
        }
        public boolean uncertain(long now) { return now < until; }
    }
}

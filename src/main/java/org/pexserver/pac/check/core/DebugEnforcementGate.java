package org.pexserver.pac.check.core;

/** Orders recording-mode changes against irreversible enforcement writes. */
public final class DebugEnforcementGate {
    @FunctionalInterface public interface Action { void run() throws Exception; }

    private volatile boolean recording;
    private volatile long generation;

    public boolean recording() { return recording; }
    public long generation() { return generation; }
    public synchronized boolean live(long expectedGeneration) {
        return !recording && generation == expectedGeneration;
    }

    public synchronized boolean setRecording(boolean enabled) {
        boolean changed = recording != enabled;
        if (changed) {
            recording = enabled;
            generation++;
        }
        return changed;
    }

    public synchronized boolean runWhenLive(long expectedGeneration, Action action) throws Exception {
        if (!live(expectedGeneration)) return false;
        action.run();
        return true;
    }
}

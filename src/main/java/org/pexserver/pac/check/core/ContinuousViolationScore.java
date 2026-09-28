package org.pexserver.pac.check.core;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Per-player evidence score with short-window combo amplification and time decay. */
public final class ContinuousViolationScore {
    public record Update(double score, double added, double multiplier, int streak) { }

    private static final class State {
        double score;
        long updatedAt = Long.MIN_VALUE;
        long lastSignalAt = Long.MIN_VALUE;
        int streak;
        boolean enforcementClaimed;
    }

    private final Map<UUID, State> states = new HashMap<>();

    public synchronized Update add(UUID uuid, long now, double weight,
                                   long halfLifeMillis, long repeatWindowMillis,
                                   double repeatMultiplier, double maximumMultiplier,
                                   double maximumScore) {
        State state = states.computeIfAbsent(uuid, ignored -> new State());
        decay(state, now, halfLifeMillis);

        if (!Double.isFinite(weight) || weight <= 0) {
            return new Update(state.score, 0, 1, state.streak);
        }

        if (state.lastSignalAt != Long.MIN_VALUE && now >= state.lastSignalAt
                && now - state.lastSignalAt <= repeatWindowMillis) {
            state.streak = Math.min(state.streak + 1, 64);
        } else {
            state.streak = 1;
        }
        double multiplierBase = atLeast(repeatMultiplier, 1, 1.5);
        double multiplierCap = atLeast(maximumMultiplier, 1, 8);
        double multiplier = Math.min(multiplierCap,
                Math.pow(multiplierBase, Math.max(0, state.streak - 1)));
        double cap = atLeast(maximumScore, 1, 1000);
        double added = Math.min(cap - state.score, weight * multiplier);
        if (!Double.isFinite(added) || added < 0) added = 0;
        state.score = Math.min(cap, state.score + added);
        state.lastSignalAt = now;
        return new Update(state.score, added, multiplier, state.streak);
    }

    /** Claims a punishment threshold once until the score decays below it. */
    public synchronized boolean claimThreshold(UUID uuid, double threshold) {
        State state = states.get(uuid);
        if (state == null) return false;
        if (state.score < threshold) {
            state.enforcementClaimed = false;
            return false;
        }
        if (state.enforcementClaimed) return false;
        state.enforcementClaimed = true;
        return true;
    }

    public synchronized void resetClaimBelow(UUID uuid, double threshold) {
        State state = states.get(uuid);
        if (state != null && state.score < threshold) state.enforcementClaimed = false;
    }

    public synchronized double score(UUID uuid, long now, long halfLifeMillis) {
        State state = states.get(uuid);
        if (state == null) return 0;
        decay(state, now, halfLifeMillis);
        return state.score;
    }

    public synchronized void reset(UUID uuid) { states.remove(uuid); }
    public synchronized void clear() { states.clear(); }
    public synchronized void releaseThreshold(UUID uuid) {
        State state = states.get(uuid);
        if (state != null) state.enforcementClaimed = false;
    }

    private static void decay(State state, long now, long halfLifeMillis) {
        if (state.updatedAt == Long.MIN_VALUE) {
            state.updatedAt = now;
            return;
        }
        if (now <= state.updatedAt) return;
        long elapsed = now - state.updatedAt;
        double halfLife = Math.max(1, halfLifeMillis);
        state.score *= Math.pow(0.5, elapsed / halfLife);
        if (state.score < 0.001) state.score = 0;
        state.updatedAt = now;
    }

    private static double atLeast(double value, double minimum, double fallback) {
        return Double.isFinite(value) ? Math.max(minimum, value) : fallback;
    }
}

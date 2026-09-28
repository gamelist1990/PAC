package org.pexserver.pac.packet;

import java.util.ArrayDeque;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Server-authoritative motion updates sent to a Java client. */
public final class ExternalMotionTracker {
    public record Impulse(long sequence, double x, double y, double z, long sentAt,
                          boolean additive, boolean combatKnockback) {
        public Impulse(long sequence, double x, double y, double z, long sentAt,
                       boolean additive) {
            this(sequence, x, y, z, sentAt, additive, false);
        }
    }

    private static final long MAX_AGE_MILLIS = 1_500;
    private static final int MAX_PENDING_UPDATES = 128;

    private static final class History {
        private final ArrayDeque<Impulse> updates = new ArrayDeque<>();
    }

    private final AtomicLong nextSequence = new AtomicLong();
    private final ConcurrentHashMap<UUID, History> histories = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> recentCombatDamage = new ConcurrentHashMap<>();

    /** Tags only server velocity updates that follow actual entity damage. */
    public void markCombatDamage(UUID uuid, long now) {
        if (uuid != null) recentCombatDamage.put(uuid, now);
    }

    public boolean recentCombatDamage(UUID uuid, long now) {
        Long damagedAt = recentCombatDamage.get(uuid);
        return damagedAt != null && now >= damagedAt && now - damagedAt <= 500;
    }

    public void velocity(UUID uuid, double x, double y, double z, long now) {
        record(uuid, x, y, z, now, false);
    }

    public void addImpulse(UUID uuid, double x, double y, double z, long now) {
        record(uuid, x, y, z, now, true);
    }

    private void record(UUID uuid, double x, double y, double z, long now, boolean additive) {
        if (uuid == null || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return;
        History history = histories.computeIfAbsent(uuid, ignored -> new History());
        synchronized (history) {
            prune(history, now);
            boolean combatKnockback = recentCombatDamage(uuid, now);
            history.updates.addLast(new Impulse(nextSequence.incrementAndGet(), x, y, z, now,
                    additive, combatKnockback));
            while (history.updates.size() > MAX_PENDING_UPDATES) history.updates.removeFirst();
        }
    }

    /** Returns the latest recent update for status and pending-correction checks. */
    public Impulse current(UUID uuid, long now) {
        History history = histories.get(uuid);
        if (history == null) return null;
        synchronized (history) {
            prune(history, now);
            return history.updates.peekLast();
        }
    }

    /**
     * Folds all updates since the last position packet into the client velocity
     * that should seed prediction. Set-velocity packets replace earlier motion;
     * additive knockback packets accumulate on top of the latest replacement.
     */
    public Impulse since(UUID uuid, long afterSequence, long now) {
        History history = histories.get(uuid);
        if (history == null) return null;
        synchronized (history) {
            prune(history, now);
            double x = 0, y = 0, z = 0;
            boolean replacement = false;
            boolean combatKnockback = false;
            Impulse last = null;
            for (Impulse update : history.updates) {
                if (update.sequence() <= afterSequence) continue;
                last = update;
                if (!update.additive()) {
                    x = update.x(); y = update.y(); z = update.z();
                    replacement = true;
                    combatKnockback = update.combatKnockback();
                } else {
                    x += update.x(); y += update.y(); z += update.z();
                    combatKnockback |= update.combatKnockback();
                }
            }
            return last == null ? null : new Impulse(last.sequence(), x, y, z,
                    last.sentAt(), !replacement, combatKnockback);
        }
    }

    private static void prune(History history, long now) {
        while (!history.updates.isEmpty()
                && now - history.updates.peekFirst().sentAt() > MAX_AGE_MILLIS)
            history.updates.removeFirst();
    }

    public void forget(UUID uuid) {
        histories.remove(uuid);
        recentCombatDamage.remove(uuid);
    }
}

package org.pexserver.pac.movement;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Retains distinct observed pushable contacts for the last three movement samples. */
final class EntityPushContactWindow {
    private static final int WINDOW_SIZE = 3;
    private static final int MAX_RECORDED_CONTACTS = 8;
    private final int[] samples = new int[WINDOW_SIZE];
    private final Set<UUID> pendingContacts = new HashSet<>(MAX_RECORDED_CONTACTS);
    private int next;

    void recordContact(UUID entityId) {
        if (entityId != null && (pendingContacts.contains(entityId)
                || pendingContacts.size() < MAX_RECORDED_CONTACTS)) pendingContacts.add(entityId);
    }

    int sample() {
        samples[next] = Math.min(MAX_RECORDED_CONTACTS, pendingContacts.size());
        pendingContacts.clear();
        next = (next + 1) % WINDOW_SIZE;
        return maximum();
    }

    int maximum() {
        int maximum = 0;
        for (int count : samples) maximum = Math.max(maximum, count);
        return maximum;
    }

    void clear() {
        Arrays.fill(samples, 0);
        pendingContacts.clear();
        next = 0;
    }
}

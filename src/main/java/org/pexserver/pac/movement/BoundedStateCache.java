package org.pexserver.pac.movement;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Main-thread, access-ordered cache whose values are validated against current state. */
final class BoundedStateCache<K, S, V> {
    private record Entry<S, V>(S state, V value) { }

    private final int maximumEntries;
    private final LinkedHashMap<K, Entry<S, V>> entries = new LinkedHashMap<>(128, 0.75f, true);

    BoundedStateCache(int maximumEntries) {
        if (maximumEntries < 1) throw new IllegalArgumentException("maximumEntries must be positive");
        this.maximumEntries = maximumEntries;
    }

    V get(K key, S state, Supplier<V> valueFactory) {
        Entry<S, V> cached = entries.get(key);
        if (cached != null && Objects.equals(cached.state(), state)) return cached.value();

        // A failed sample must not insert an empty entry or evict valid geometry.
        V value = Objects.requireNonNull(valueFactory.get(), "cached value");
        entries.put(key, new Entry<>(state, value));
        if (entries.size() > maximumEntries) entries.pollFirstEntry();
        return value;
    }

    void invalidate(K key) {
        entries.remove(key);
    }

    void invalidateIf(Predicate<? super K> predicate) {
        for (Iterator<Map.Entry<K, Entry<S, V>>> iterator = entries.entrySet().iterator(); iterator.hasNext();) {
            Map.Entry<K, Entry<S, V>> entry = iterator.next();
            if (predicate.test(entry.getKey())) {
                iterator.remove();
            }
        }
    }

    int size() { return entries.size(); }

}
